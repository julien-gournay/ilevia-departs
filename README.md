# Ilévia Départs (Android)

App Android (Kotlin / Jetpack Compose) pour la métropole lilloise :

- choix **ligne → sens → arrêt** à partir du jeu open data « ilévia – Prochains passages bus et tramway » (MEL) ;
- **4 types de widgets** : bandeau, grand chiffre, liste de départs, grille (trajets choisis à la pose du widget) ;
- **trajets enregistrés** : temps de marche + marge, jours et plage horaire d'alerte ;
- **notification « Il est temps de partir ! »** ;
- options d'affichage : arrêt, heure de départ, heure où partir.

## Compiler / mettre à jour
Chaque push sur `main` lance GitHub Actions : l'APK **signé avec une clé fixe** (`app/ilevia.keystore`) est compilé
et publié dans les *Releases*. L'app (Réglages → Mise à jour) compare son numéro de version à la dernière release
et propose de l'installer par-dessus, sans désinstaller.

- Dépôt **public** : la mise à jour se fait en un geste dans l'app.
- Dépôt **privé** : Réglages → « Page des versions » ouvre la release dans le navigateur ; télécharger l'APK et l'installer.

⚠️ La clé de signature est dans le dépôt (usage personnel). Si vous publiez l'app pour d'autres, générez une clé
privée et stockez-la dans les *secrets* GitHub.

## Si l'API change
Tout est dans `IleviaApi.kt` : `ENDPOINTS` et les listes de noms de champs.
Les heures de l'API sont des heures de Paris écrites avec un « Z » trompeur (géré dans `parseTime`).
