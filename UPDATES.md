# Kostenlose Updates bereitstellen

Die App kann beim Start eine sehr kleine JSON-Datei abrufen, eine neue Version anzeigen, die APK
über Androids DownloadManager laden und anschließend den Systemdialog zur Installation öffnen.
Android verlangt dabei immer die Bestätigung des Nutzers. Jede APK muss mit demselben privaten
Schlüssel wie die installierte App signiert sein.

Die URL wird beim Build in `BuildConfig.UPDATE_MANIFEST_URL` eingebettet. Die übergebene 1.3.0-APK
hat absichtlich noch keine feste URL, weil noch kein endgültiges GitHub-Konto/Repository genannt
wurde. Bis zum ersten Build mit `-PweeklyDjShowsUpdateManifestUrl=...` meldet die Update-Seite daher
nur, dass keine Updatequelle eingerichtet ist.

## Kostenloser Veröffentlichungsweg

1. Ein öffentliches GitHub-Repository anlegen.
2. Die signierte APK als Datei eines GitHub Release hochladen.
3. `update-manifest.example.json` kopieren, Versionsnummer, Download-URL und Hinweise anpassen und
   beispielsweise über GitHub Pages bereitstellen.
4. Die App mit der stabilen HTTPS-Adresse dieser Datei bauen:

   ```bash
   ./gradlew :app:assembleRelease \
     -PweeklyDjShowsUpdateManifestUrl=https://BENUTZER.github.io/REPOSITORY/update.json
   ```

5. Bei jedem späteren Release zuerst die APK hochladen und danach `update.json` aktualisieren. So
   sehen installierte Apps nie einen noch nicht erreichbaren Download.

Für reine Show-Katalogänderungen sollte dieselbe Manifestdatei später zusätzlich eine signierte
Katalogdatei referenzieren. Der Quellstand trennt bereits gebündelte und selbst hinzugefügte Shows,
Standardpositionen, eigene Verschiebungen und Nachbaranker. Dadurch lassen sich unveränderte
Standardblöcke erneuern, während absichtlich verschobene und ausgeblendete Shows erhalten bleiben.
In 1.3.0 wird diese Zusammenführung bereits für einen mit einer App-Version gebündelten neuen
Katalog verwendet; das separate Herunterladen einer reinen Katalogdatei ist der nächste kleine
Ausbauschritt.

## NewPipe

Die interne Wiedergabe verwendet `NewPipeExtractor`, nicht die installierte NewPipe-App. Unter
`resolver-newpipe/build.gradle.kts` steht die gepinnte Extractor-Version. Bei einer neuen Version:

1. offizielle Release Notes und Lizenz prüfen;
2. Versionsnummer dort ändern;
3. Resolver-, YouTube-, SoundCloud- und geplante-Folgen-Tests ausführen;
4. APK neu bauen, auf einem Gerät testen und normal als App-Update veröffentlichen.

Die optionale Prüfung in der App vergleicht den eingebetteten Stand mit dem offiziellen neuesten
Extractor-Release. Eine installierte NewPipe-App wird getrennt betrachtet. Weil F-Droid und GitHub
unterschiedliche Signaturen verwenden können, öffnet die App dafür bewusst die offizielle
Installationsanleitung, statt ungefragt eine möglicherweise inkompatibel signierte APK zu ersetzen.
