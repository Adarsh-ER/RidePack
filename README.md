# RidePack – native Android app (Kotlin + Jetpack Compose)

Features: offline map areas (MapLibre), live GPS, GPX route import with turn-by-turn,
places along the ride (fuel/food/viewpoint/repair/stay), ride-code pairing, rider tagging by ID,
phone-to-phone tracking over Bluetooth/Wi-Fi (Google Nearby, no mobile data), regroup alerts,
foreground service so tracking continues with the screen off.

## Build
Open this folder in Android Studio (it creates the Gradle wrapper), or from a terminal:

    gradle wrapper --gradle-version 8.7
    ./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk

## Test on phones (USB debugging on)
    adb devices
    ANDROID_SERIAL=<serialA> ./adb-check.sh
    ANDROID_SERIAL=<serialB> ./adb-check.sh
Phone A: Pair tab -> Create ride. Phone B: enter the code -> Join. Pair tab should show "1 radio link(s)".
Offline check: download an area (Offline tab) on Wi-Fi, then `ANDROID_SERIAL=<serial> ./adb-check.sh "" --offline`.

## Notes
- Not compiled or run yet; expect to fix a few build errors (library versions/APIs) on first build.
- Map style: default is OpenFreeMap "Liberty" (OpenStreetMap data, free, no API key) so roads, names and
  places show out of the box. Any MapLibre vector style URL can be pasted in the Offline tab, but only use
  one whose terms allow offline download.
- Needs Google Play services (Nearby, location). Radio range is a few hundred metres; no multi-hop relay yet.
- Anyone with the ride code can join; positions are not encrypted yet.

## Project layout
    settings.gradle.kts, build.gradle.kts, gradle.properties
    app/build.gradle.kts
    app/src/main/AndroidManifest.xml
    app/src/main/java/com/ridepack/app/*.kt
