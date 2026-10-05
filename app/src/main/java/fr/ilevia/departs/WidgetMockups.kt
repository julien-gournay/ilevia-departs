package fr.ilevia.departs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Exemple de passage pour dessiner les aperçus. */
private class Mock(val line: String, val direction: String, val mins: String, val dep: String, val leave: String)

private const val MOCK_STATION = "Chemin Des Vaches"

private val MOCKS = listOf(
    Mock("84", "Tourcoing Centre", "16", "08:12", "Partir 08:06"),
    Mock("86", "Gare Lille Europe", "Proche", "07:58", "Partez !"),
    Mock("82", "Tourcoing Pont de Neuville", "8", "08:04", "Partir 07:58"),
    Mock("84", "Les Glycines", "13", "08:09", "Partir 08:03"),
    Mock("86", "Comines Gare", "31", "08:27", "Partir 08:21"),
    Mock("82", "Armentières Gare", "40", "08:36", "Partir 08:30"),
)

/** Types de widget : 0 bandeau, 1 grand chiffre, 2 liste, 3 grille. */
@Composable
fun WidgetPreview(kind: Int, showDep: Boolean, showLeave: Boolean, showStation: Boolean, modifier: Modifier = Modifier) {
    when (kind) {
        0 -> BandMock(showDep, showLeave, showStation, modifier)
        1 -> BigMock(showDep, showLeave, showStation, modifier)
        2 -> BoardMock(showDep, showLeave, showStation, modifier)
        else -> GridMock(showDep, showStation, modifier)
    }
}

@Composable
private fun Surface(modifier: Modifier, padding: Dp, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.clip(shape)
            .background(if (dark) Color(0xFF1E1F24) else Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(padding),
    ) { content() }
}

@Composable
private fun primary() = MaterialTheme.colorScheme.onSurface

@Composable
private fun secondary() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun red() = MaterialTheme.colorScheme.error

@Composable
private fun Badge(line: String, round: Boolean, size: Dp) {
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    Box(
        Modifier.then(if (round) Modifier.size(size) else Modifier.height(size).widthIn(min = size))
            .clip(shape).background(lineColor(line)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            line, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp,
            modifier = if (round) Modifier else Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun Ellipsis(text: String, size: Int, color: Color, bold: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = size.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = modifier,
    )
}

// ───────── Bandeau ─────────
@Composable
private fun BandMock(showDep: Boolean, showLeave: Boolean, showStation: Boolean, modifier: Modifier) {
    Surface(modifier.fillMaxWidth().height(72.dp), 6.dp) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            MOCKS.take(4).forEach { m ->
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(m.line, round = false, size = 18.dp)
                        Spacer(Modifier.width(3.dp))
                        if (m.mins == "Proche") {
                            Ellipsis("Proche", 13, primary(), bold = true)
                        } else {
                            Text(m.mins, color = primary(), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("min", color = secondary(), fontSize = 9.sp, maxLines = 1)
                        }
                    }
                    if (showStation) Ellipsis(MOCK_STATION, 10, secondary())
                    if (showDep) Ellipsis("Dép. ${m.dep}", 10, primary())
                    if (showLeave) Ellipsis(m.leave, 10, red(), bold = true)
                }
            }
        }
    }
}

// ───────── Grand chiffre ─────────
@Composable
private fun BigMock(showDep: Boolean, showLeave: Boolean, showStation: Boolean, modifier: Modifier) {
    val m = MOCKS[0]
    Surface(modifier.size(150.dp), 10.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(m.line, round = false, size = 20.dp)
                if (showStation) {
                    Spacer(Modifier.width(6.dp))
                    Ellipsis(MOCK_STATION, 11, secondary())
                }
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(m.mins, color = primary(), fontSize = 54.sp, fontWeight = FontWeight.Bold)
                Text("MIN", color = primary(), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 14.dp))
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14000000)).padding(8.dp),
            ) {
                Ellipsis("→ ${m.direction}", 12, primary(), bold = true)
                if (showDep) Ellipsis("Dép. ${m.dep}", 11, secondary())
                if (showLeave) Ellipsis(m.leave, 11, red(), bold = true)
            }
        }
    }
}

// ───────── Liste de départs ─────────
@Composable
private fun BoardMock(showDep: Boolean, showLeave: Boolean, showStation: Boolean, modifier: Modifier) {
    Surface(modifier.fillMaxWidth().height(112.dp), 6.dp) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Prochains départs", color = primary(), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("⟳ 08:02", color = secondary(), fontSize = 11.sp)
            }
            MOCKS.take(2).forEach { m ->
                Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(m.line, round = true, size = 30.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Ellipsis(m.direction, 13, primary(), bold = true)
                        val sub = listOfNotNull(if (showStation) MOCK_STATION else null, if (showDep) "Dép. ${m.dep}" else null).joinToString(" · ")
                        if (sub.isNotEmpty()) Ellipsis(sub, 10, secondary())
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (m.mins == "Proche") "Proche" else "${m.mins} min", color = primary(), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (showLeave) Ellipsis(m.leave, 10, red(), bold = true)
                    }
                }
            }
        }
    }
}

// ───────── Grille ─────────
@Composable
private fun GridMock(showDep: Boolean, showStation: Boolean, modifier: Modifier) {
    Surface(modifier.fillMaxWidth().height(190.dp), 6.dp) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MOCKS.chunked(2).forEach { pair ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEach { m ->
                        Column(
                            Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(0x14000000)).padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Badge(m.line, round = true, size = 22.dp)
                                Text(
                                    if (m.mins == "Proche") "Proche" else "${m.mins} min", color = primary(), fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 6.dp),
                                )
                                if (showDep) Text(m.dep, color = secondary(), fontSize = 10.sp, textAlign = TextAlign.End)
                            }
                            Ellipsis(m.direction, 11, primary(), bold = true)
                            if (showStation) Ellipsis(MOCK_STATION, 9, secondary())
                        }
                    }
                }
            }
        }
    }
}
