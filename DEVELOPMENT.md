# Selbst an der App weiterarbeiten

Dieser Ordner ist ein vollständiges, bearbeitbares Android-Studio-/Gradle-Projekt und keine
dekompilierte APK. Änderungen sollten immer hier beginnen.

## Einrichten

1. Android Studio mit JDK 17 installieren.
2. Den entpackten Projektordner über **Open** öffnen.
3. Android SDK Platform 35 und Build Tools 35.0.0 installieren.
4. Die Gradle-Synchronisierung abwarten.
5. Ein Gerät mit Android 6.0 oder neuer per USB verbinden oder einen Emulator starten.

Wichtige Einstiegspunkte:

- `app/.../WeeklyDjShowsUi.kt`: Compose-Oberfläche und Navigation
- `app/.../MainViewModel.kt`: UI-Zustand und Aktionen
- `app/.../AppSettings.kt`: alle Einstellungen und ihre dauerhafte Speicherung
- `data-database/...`: Room-Tabellen und Abfragen
- `playback/...`: Player, Warteschlange, Wiederaufnahme und Downloads
- `resolver-newpipe/...`: YouTube-/SoundCloud-Auflösung
- `app/src/main/assets/curated-show-layout-v128.json`: Standard-Showkatalog

## Bauen

```bash
./gradlew :app:assembleDebug
./gradlew test
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
```

Die Debug-APK liegt danach unter `app/build/outputs/apk/debug/`. Ein Release ist zunächst
unsigniert; dafür gilt `SIGNING.md`.

## Eine neue Version veröffentlichen

1. `versionCode` erhöhen und `versionName` in `app/build.gradle.kts` setzen.
2. Änderungen bauen, testen und auf einem echten Gerät prüfen.
3. Mit demselben 1.3.0-Update-Schlüssel signieren.
4. APK und Quell-ZIP beispielsweise als kostenloses GitHub Release hochladen.
5. Erst danach die über GitHub Pages erreichbare `update.json` aktualisieren; Details stehen in
   `UPDATES.md`.

## NewPipe-Abhängigkeit aktualisieren

Die gepinnte Version steht in `resolver-newpipe/build.gradle.kts`. Bei einem neuen offiziellen
Extractor-Release:

1. Release Notes und Lizenzänderungen lesen.
2. Die Versionsnummer in diesem Modul ändern.
3. Eventuelle API-Änderungen in `NewPipeMediaResolver.kt`,
   `NewPipePlatformListingResolver.kt` und der PoToken-Hilfe anpassen.
4. YouTube, SoundCloud, geplante YouTube-Folgen, Warteschlange und Öffnen in NewPipe auf einem
   Gerät prüfen.
5. Eine neue App-Version bauen und regulär veröffentlichen.

Wenn die API kompatibel bleibt, ist das meist eine Änderung plus einige Stunden Tests. Ändert
YouTube seine Auslieferung oder NewPipe die PoToken-/Extractor-API, kann die Anpassung einen bis
mehrere Arbeitstage dauern. Die installierte NewPipe-App ist nur ein externer Fallback und ersetzt
nicht den eingebetteten Extractor.

## Feedbestand prüfen

```bash
tools/validate-feeds.sh \
  app/src/main/assets/curated-show-layout-v128.json \
  build/feed-validation.tsv
```

Nur sichere 404/410-Antworten oder ein erfolgreich geladener, leerer Feed sollten dauerhaft
ausgeblendet werden. 403, 429, Zeitüberschreitungen und 5xx sind vorübergehend und dürfen eine Show
nicht entfernen. Die App wendet dieselbe vorsichtige Regel bei normalen Aktualisierungen an.

