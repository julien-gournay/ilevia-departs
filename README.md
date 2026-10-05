# Ilévia Départs (Android)

App Android (Kotlin / Jetpack Compose) pour la métropole lilloise :

- choix **ligne → sens → arrêt** à partir du jeu open data « ilévia – Prochains passages bus et tramway » (MEL) ;
- **widget** d'écran d'accueil : prochain départ en minutes, heure où partir, 2 passages suivants (toucher = rafraîchir) ;
- **trajets enregistrés** : temps de marche + marge, jours et plage horaire d'alerte ;
- **notification « Il est temps de partir ! »** à l'heure calculée (départ − marche − marge).

## Lancer
1. Ouvrir le dossier dans Android Studio (Koala ou plus récent) — il génère le wrapper Gradle.
2. Lancer sur un appareil/émulateur Android 8+ (API 26+).
3. Créer un trajet (+), puis ajouter le widget « Ilévia Départs » sur l'écran d'accueil.
4. Accepter la notification (Android 13+) et, pour une alerte à la minute près, autoriser les « alarmes et rappels ».

## Si l'API change
Tout est dans `IleviaApi.kt` : liste `ENDPOINTS` (collections OGC API de data.lillemetropole.fr)
et listes de noms de champs (`STATION_KEYS`, `LINE_KEYS`, `DIRECTION_KEYS`, `TIME_KEYS`).

## Limites connues
- Android impose 15 min minimum entre deux vérifications en arrière-plan ; l'alerte précise est
  ensuite programmée par alarme (dans les 30 min avant le départ).
- Le flux contient tout le réseau (~2 Mo) : il est téléchargé puis filtré sur l'appareil.
- Métro et tram : le jeu open data couvre bus + tramway ; le métro n'y figure peut-être pas
  (le GTFS-RT https://proxy.transport.data.gouv.fr/resource/ilevia-lille-gtfs-rt serait alors à ajouter).
