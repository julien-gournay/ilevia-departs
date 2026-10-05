package fr.ilevia.departs

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Une version publiée sur GitHub (Releases). [build] = numéro de build, comparé à BuildConfig.VERSION_CODE. */
data class Release(val build: Int, val apkUrl: String, val notes: String)

class PrivateRepoException : Exception("Dépôt privé ou introuvable")

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object NeedPermission : UpdateState
    data object PrivateRepo : UpdateState
    data class Error(val message: String) : UpdateState
}

object Updater {
    const val REPO = "julien-gournay/ilevia-departs"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** Dernière version publiée, ou null s'il n'y en a pas encore. */
    fun fetchLatest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "IleviaDeparts")
        try {
            when (c.responseCode) {
                200 -> Unit
                404 -> throw PrivateRepoException()
                403, 429 -> throw IllegalStateException("Limite GitHub atteinte, réessayez plus tard")
                else -> throw IllegalStateException("GitHub a répondu ${c.responseCode}")
            }
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val build = Regex("\\d+").find(json.optString("tag_name"))?.value?.toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(build, a.getString("browser_download_url"), json.optString("body"))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Télécharge l'APK dans le stockage de l'app (bloquant, à appeler hors du thread principal). */
    fun download(context: Context, r: Release, onProgress: (Int) -> Unit): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Stockage indisponible")
        val file = File(dir, "IleviaDeparts-${r.build}.apk")
        if (file.exists()) file.delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("Ilévia Départs – version ${r.build}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(file)),
        )
        while (true) {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) throw IllegalStateException("Téléchargement introuvable")
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return file
                    DownloadManager.STATUS_FAILED -> throw IllegalStateException("Échec du téléchargement")
                    else -> {
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            Thread.sleep(500)
        }
    }

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** État de la mise à jour, observé par l'interface Compose. */
class UpdateController(private val context: Context) {
    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set

    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME

    suspend fun check() {
        state = UpdateState.Checking
        state = try {
            val r = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            if (r != null && r.build > currentBuild) UpdateState.Available(r) else UpdateState.UpToDate
        } catch (e: PrivateRepoException) {
            UpdateState.PrivateRepo
        } catch (e: Exception) {
            UpdateState.Error(e.message ?: "Erreur réseau")
        }
    }

    suspend fun install(r: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            state = UpdateState.NeedPermission
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        state = UpdateState.Downloading(0)
        state = try {
            val file = withContext(Dispatchers.IO) { Updater.download(context, r) { state = UpdateState.Downloading(it) } }
            context.startActivity(Updater.installIntent(context, file))
            UpdateState.Available(r)
        } catch (e: Exception) {
            UpdateState.Error(e.message ?: "Échec de la mise à jour")
        }
    }
}
