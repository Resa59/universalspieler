# Weekly DJ Shows – Übergabe 1.3.1

Stand: 2026-08-11

Dieser Ordner ist der maßgebliche Quellstand von Version 1.3.1. Neue Versionen sollen von diesem
Projekt und nicht von einer dekompilierten oder umgepackten APK ausgehen. Private Signierdaten sind
absichtlich nicht Bestandteil des Quellprojekts.

## Maßgeblicher Stand

- Paket: `de.rdoe.weeklydjshows`
- Version: `1.3.1`
- `versionCode`: 19
- `minSdk`: 23
- `compileSdk` / `targetSdk`: 35
- Datenbankversion: 7 (destruktiver Neuaufbau dieser privaten Vorabversion ist erlaubt)
- JDK: 17
- Gradle: 8.9
- Android Gradle Plugin: 8.7.3
- Kotlin: 2.0.21
- Android Build Tools: 35.0.0
- NewPipe Extractor: `com.github.TeamNewPipe:NewPipeExtractor:v0.26.4`
- PoToken-WebView-Helfer: aus NewPipe 0.29.0 adaptiert

## Änderungen in 1.3.1

1. SoundCloud-Direktwiedergabe nutzt wieder den progressiven, auch vom Download bewährten Stream
   und denselben robusten OkHttp-Pfad wie die Downloadfunktion. Wird eine gerade laufende Folge
   fertig heruntergeladen, tauscht der Player die Quelle bei identischer Position, Geschwindigkeit
   und Play/Pause-Lage gegen die lokale Datei aus.
2. Gehört-Status und sichtbarer Fortschritt sind getrennt: Nach einem vollständigen Durchlauf bleibt
   die Folge als gehört markiert, eine neue Wiedergabe zeigt jedoch sofort deren neuen Stand.
3. Die Discovery-Vorschau parst RSS-/Atom-Feeds bei leerer Verifier-Vorschau direkt, ohne dabei
   schon Shows oder Folgen in Room einzutragen. Der Fortsetzen-Dialog ist inhaltsnah bemessen.
4. Das eigene Overlay begrenzt jede Kartenbreite auf das Display, nutzt rahmenlose Steuerungen und
   bildet Vorherige/Nächste aus tatsächlicher Player- und dauerhafter Queue-Lage ab. Das Quadrat
   zeigt Spulen, Play/Pause und Schließen erst nach einem Tipp beziehungsweise dauerhaft im
   pausierten Zustand. Positionen werden je Layout gespeichert.
5. Sobald die Haupt-App sichtbar ist, wird das eigene Overlay geschlossen. Beim nächsten bewusst
   ausgelösten Hintergrundwechsel erscheint es wieder an seiner gespeicherten Position.
6. 1001Tracklists ist der erste Eintrag unter Musik-Podcasts. 1001Tracklists, Spotify, Mixcloud und
   reine Plattformlinks sind keine auswählbaren Quellen für das automatische Neueste-Folge-Angebot.
7. Die Android-Mediensteuerung priorisiert Queue zurück/vor in den kompakten Seitenslots und
   Gefällt mir sowie Spulen in den zusätzlichen Systemplätzen.
8. Datenbankversion 7 und Updatezertifikat bleiben unverändert; eine installierte 1.3.0 wird mit
   ihren Daten regulär auf `versionCode` 19 aktualisiert.

## Änderungen in 1.3.0

1. Navigation: `Shows · Neu · Bibliothek · Suchen`; Bibliothek: `Verlauf · Gefällt mir · Downloads`.
   Der App-Name enthält keinen Zusatz „Neu“ mehr.
2. Playerstände werden periodisch und beim Pausieren/Beenden gespeichert und beim erneuten Start
   atomar an Media3 übergeben. Beendete Folgen bleiben mit vollem Balken erkennbar; eine erneut
   angespielte Folge ist trotzdem fortsetzbar.
3. Der Verlauf zeigt je Folge nur den letzten Eintrag, bietet in der Detailansicht alle
   Wiedergabedaten und lässt sich per langem Druck löschen.
4. Wort- und Musik-Podcasts haben getrennte Bereiche, Sichtbarkeits-/Neu-/Update-Einstellungen und
   Kategorieaktionen für ganz oben, ganz unten und Kategorienwechsel.
5. Ein portabler Show-JSON-Export und eine vollständige ZIP-Sicherung koexistieren. Die
   Vollsicherung kann Einstellungen, Spielstände, Verlauf, Likes, Warteschlange und optional
   Downloads samt Covern enthalten; wiederholte Importe werden stabil zusammengeführt.
6. Der Standardkatalog unterscheidet Standardblöcke von eigenen Verschiebungen. Updates bieten
   getrennte Bestätigungen für neue/geänderte Reihenfolge und entfernte Standardshows; eigene,
   ausgeblendete und neu hinzugefügte Shows bleiben erhalten.
7. Geplante YouTube-Inhalte sind gedimmt, extern blockiert und optional aus `Neu` ausblendbar. Sie
   dürfen in die Warteschlange, werden dort aber weder beim Einreihen noch regelmäßig geprüft:
   genau dann, wenn sie tatsächlich an der Reihe sind, erfolgt ein Versuch; bei Fehlschlag wird die
   nächste spielbare Folge genommen.
8. Wird eine aus der Warteschlange gestartete Folge manuell unterbrochen, kehrt nur diese Folge an
   Position eins zurück. Gewöhnliches Probehören erzeugt keine zusätzlichen Queue-Einträge.
9. Kaltstart-Sync, 10-Minuten-Leerlaufende, Fortsetzen-/Neueste-Folge-Karte, Bluetooth-Autostart und
   automatischer Mini-Start beim Hintergrundwechsel sind einzeln konfigurierbar.
10. Neben Android-PiP existiert ein eigenes Overlay als kleines Quadrat oder Querformatkarte mit
    Cover, Titel, Show, fünf Steuerungen und Fortschrittskante. Pausierte Cover werden abgedunkelt.
11. Kostenlose App-Updateprüfung, nutzerorientierte NewPipe-/Extractor-Prüfung, Diagnoseprotokoll
    sowie Teilen von Fehlern und Anregungen sind in den Einstellungen integriert.
12. Der endgültig mit HTTP 404 bestätigte inoffizielle mau5trap-Feed ist standardmäßig
    ausgeblendet; vorübergehende Feedfehler führen nicht zum Ausblenden.

## Änderungen in 1.2.13

1. Der Player besitzt rechts oben eine native Google-Cast-Auswahl. `CastPlayer` verbindet den
   vorhandenen lokalen ExoPlayer mit `RemoteCastPlayer`; laufende Position und Wiedergabezustand
   können damit auf einen Chromecast übertragen werden. Artwork wird als eigene Cast-Metadaten-
   Bild-URL übertragen und ist nicht an die Audio-URL gekoppelt. Bei Offline-Dateien wird vor der
   Übergabe eine erreichbare Remotequelle ermittelt.
2. Für Fire TV und andere Smart TVs bietet der Cast-Dialog zusätzlich Androids systemeigene
   Miracast-/Smart-View-Einstellung an. Das eingestellte Audio und Bild wird dabei als
   Bildschirmspiegelung übertragen. Das veraltete Amazon-Fling-SDK wird nicht eingebaut; Matter
   Casting ist keine austauschbare Senderbibliothek, sondern erfordert eine eigenständige
   Matter-/Attestierungsintegration.
3. Die PiP-Fortschrittskante analysiert die tatsächliche unterste Pixelzeile des geladenen Covers
   ohne vorherige 96-px-Verkleinerung. Erst wenn mindestens 60 % der sichtbaren Kantenpixel
   nahezu weiß sind, wird ein dunkler, aus dem Artwork abgeleiteter Hue verwendet. Die
   PiP-Aktionsbuttons bleiben unverändert.
4. Der globale Coil-Bildloader setzt einen App-User-Agent. Buzzsprouts Storage-CDN liefert damit
   die echten Martin-Garrix-Cover statt HTTP 403/Platzhalter, ohne die ungewöhnlichen
   `...? .jpg`-ähnlichen Feed-URLs umzuschreiben.
5. Mixcloud-Profile sind abonnierbare Plattformquellen. Der Resolver liest Profil und paginierte
   Cloudcasts über die öffentliche Mixcloud-API. Da Mixcloud über diese API keine Audio-Streams
   bereitstellt, öffnet Wiedergabe bewusst den offiziellen Mixcloud-Player in App/Browser; ein
   versteckter WebView oder das Extrahieren nicht freigegebener Streams wird nicht verwendet.
6. Media3 ist für die Cast-Integration auf 1.9.2 aktualisiert. Datenbankversion 4 bleibt
   unverändert; 1.2.13 benötigt keine Datenbankmigration.

## Änderungen in 1.2.12

1. Die PiP-Fortschrittskante ist wieder bewusst weiß, solange die tatsächliche unterste Coverkante
   nicht nahezu weiß ist. Die Sonderbehandlung wird erst aktiviert, wenn die Mehrheit der
   untersten circa vier Prozent des Bildes sehr hell und nur schwach gesättigt ist.
2. Im Weißrand-Sonderfall bestimmt die App aus dem ganzen Cover einen gewichteten Farbakzent.
   Farbigkeit, Helligkeit und Pixelhäufigkeit gehen in die Auswahl ein, während weiße/graue Flächen
   kaum beitragen; der Akzent wird bewusst dunkel genug für einen klaren Kontrast gegen Weiß
   erzeugt. Das folgt dem Prinzip einer Artwork-Palette, ohne eine neue Bibliothek oder einen
   zweiten Bilddownload einzuführen.
3. PiP-Aktionssymbole, Datenbank, Navigation und die übrige 1.2.11-Funktionalität bleiben
   unverändert; 1.2.12 benötigt keine Datenbankmigration.

## Änderungen in 1.2.11

1. Die PiP-Fortschrittskante wird nicht mehr aus einem großen unteren Bildbereich abgeleitet und
   fällt bei knappem Kontrast nicht mehr pauschal auf Weiß zurück. Stattdessen wird gezielt die
   tatsächliche untere Coverkante ausgewertet und aus der Coverfarbe eine helle sowie eine dunkle
   getönte Variante erzeugt. Auf hellem/weißem Rand gewinnt dadurch die dunkle, auf dunklem Rand
   die helle Variante; Restspur, Schatten und Verlauf bleiben weiterhin vollständig weg.
2. Die nativen PiP-Aktionsgrafiken verwenden größere 36-dp-Vektoren mit zusätzlich vergrößertem
   sichtbarem Glyphenbereich. Play/Pause und Folgenwechsel sind damit kräftiger; die bisherigen
   selbst konstruierten 10-/30-Sekunden-Grafiken wurden durch saubere runde Seek-Symbole ersetzt.
3. Datenbank, Navigation, Bluetooth-Automatik und die übrige 1.2.10-Funktionalität bleiben
   unverändert; 1.2.11 benötigt keine Datenbankmigration.

## Änderungen in 1.2.10

1. Der Bluetooth-Vordergrundstart läuft nicht mehr über einen nackten `Context.startActivity()`.
   Android 14 erhält beim Senden eines Activity-`PendingIntent` explizit
   `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`; auf Android 15 erhält zusätzlich bereits der Creator
   des `PendingIntent` dieses Opt-in. Damit entspricht der Start dem aktuellen BAL-Modell für einen
   notwendigen Hintergrundstart aus einem `BroadcastReceiver`. `SYSTEM_ALERT_WINDOW` bleibt die
   bewusst vom Nutzer erteilte BAL-Ausnahme.
2. Die Bluetooth-Seite verlinkt bei aktiven Autostartregeln zusätzlich zu den App-Akkueinstellungen
   (`App-Info → Akku → Uneingeschränkt`). Das bildet die für Standby-Zuverlässigkeit auch von
   Blitzer.de und Tasker dokumentierte Hersteller-/Akkuvoraussetzung ab, ohne selbst eine
   Akku-Ausnahme zu erzwingen. Ein lokaler Versuch/Erfolg-Marker unterscheidet künftig zwischen
   „Startauftrag gesendet“ und „MainActivity tatsächlich erreicht“.
3. Der eingeklappte Player innerhalb der App besitzt jetzt einen reaktiven Fortschrittsstreifen an
   der Unterkante; Position und Dauer stammen aus demselben `PlayerUiState` wie der Vollbildplayer.
4. Das quadratische PiP zeigt im Ruhezustand ausschließlich das Cover. Titel, dunkler Verlauf und
   graue Fortschrittsrestspur sind entfernt. Der Fortschritt ist eine 4-dp-Unterkante; ihre Farbe
   wird aus dem geladenen Cover gewonnen und gegen die Helligkeit des unteren Bildbereichs auf
   ausreichenden Hell-/Dunkel-Kontrast geprüft.
5. PiP-`RemoteAction`-Grafiken haben größere Intrinsic-Maße. Falls ein Android-Gerät mindestens fünf
   native PiP-Aktionen meldet und die PiP-Breite mindestens 150 dp erreicht, werden automatisch
   `Vorherige · −10 · Play/Pause · +30 · Nächste` kombiniert. Bei der auf vielen Smartphones
   üblichen Dreiergrenze bleibt die in den Einstellungen gewählte Dreierbelegung bestehen.
6. Datenbankversion 4, Showanordnung und vorhandene Nutzerdaten bleiben unverändert; 1.2.10 benötigt
   keine Datenbankmigration.

## Änderungen in 1.2.9

1. Die Top-Level-Navigation verwirft beim Wechsel zwischen `Shows`, `Neu`, Suche und Bibliothek die
   frühere gespeicherte Ziel-Destination. Dadurch funktioniert `Shows` auch direkt nach einem
   Kaltstart mit automatisch geöffnetem `Neu`; die hoisted Scrollzustände der vier Listen bleiben
   trotzdem erhalten.
2. Picture-in-Picture ist immer 1:1. Die bisher hineingezeichneten, im Android-PiP nicht direkt
   anklickbaren Player-Symbole und die scheinbare Größenwahl sind entfernt. Android zeigt nach
   Antippen drei echte `RemoteAction`s: Seek oder Folgenwechsel links/rechts und Play/Pause in der
   Mitte. Die Systemsteuerung übernimmt Vollbild, Schließen und die tatsächliche Größenänderung.
3. Die Media3-Session setzt sowohl Custom Layout als auch `MediaButtonPreferences`. Vier eigene
   Aktionen belegen `BACK_SECONDARY`, `BACK`, `FORWARD` und `FORWARD_SECONDARY`; der zentrale Slot
   bleibt dem nativen Play/Pause vorbehalten. Automatische Previous/Seek/Next-Befehle werden nur für
   den Media-Notification-Controller unterdrückt, damit One UI keine doppelten/verschobenen Knöpfe
   erzeugt.
4. Bluetooth-Gerätenamen und letzte Verbindungszeit werden lokal gespeichert. Die Liste bleibt bei
   ausgeschaltetem Bluetooth sichtbar, reagiert live auf Adapter-/Bond-/ACL-/Namensänderungen und
   sortiert bekannte zuletzt verbundene Geräte vor den übrigen. Android stellt die private
   Samsung-Verlaufssortierung nicht bereit; die App baut deshalb ab 1.2.9 ihren eigenen Verlauf auf.
5. Für `App bei Verbindung öffnen` kann der Nutzer `SYSTEM_ALERT_WINDOW` über die Systemseite „Über
   anderen Apps einblenden“ freigeben. Diese Android-BAL-Ausnahme erlaubt den explizit gewählten
   Bluetooth-Hintergrundstart. `MainActivity` verwendet ausschließlich für diesen Start
   `setTurnScreenOn(true)` und `setShowWhenLocked(true)`; die Tastensperre wird nicht aufgehoben.
   Ohne Freigabe bleibt die bereits vorhandene antippbare Benachrichtigung als Rückfall.
6. `1001Tracklists` erhält auf der Show-Startseite ein lokales Markenbild; der integrierte WebView
   und seine X-/Zurück-/Vorwärtsnavigation bleiben unverändert.
7. Streamingqualität zeigt nun `=` statt `≈`, bietet zusätzlich `MAXIMUM` und hat eine unabhängige
   Downloadqualität. Resolver-Caches sind qualitätsabhängig. Der Downloadpfad fordert bei
   Plattformquellen explizit progressive HTTP-Audiospuren an, damit `Maximal` nicht versehentlich
   ein nicht dateibasiert herunterladbares DASH/HLS-Manifest auswählt.
8. `ExpandableDescription` verwendet einen kompakten Textschalter statt der großen
   Material-TextButton-Touchfläche, wodurch der ungleichmäßige sichtbare Leerraum unter kurzen und
   aufklappbaren Showbeschreibungen reduziert wird.
9. Showdetailseiten speichern beim Öffnen einer Folge die sichtbare Episoden-ID plus Pixeloffset.
   Beim Zurückgehen wird die ID gegen die aktuelle Episodenliste aufgelöst; auch nach neu
   eingefügten Folgen landet die Ansicht wieder an derselben Stelle.
10. Die gebündelte 1.2.8-Showanordnung, Datenbankversion 4 und alle bestehenden Nutzerdaten bleiben
    unverändert; 1.2.9 benötigt keine Datenbankmigration.

## Verifikation

Version 1.3.1 wurde vollständig aus diesem Kotlin-Quellstand gebaut. Geprüft wurde:

- `:app:assembleDebug`: erfolgreich.
- `test`: erfolgreich; Android-Module ohne eigene Unit-Tests melden erwartungsgemäß `NO-SOURCE`.
- Der eigenständige Discovery-Prüflauf `TestMainKt` endet mit `ALL TESTS PASSED`.
- `:app:lintRelease`: erfolgreich, 0 Fehler; verbleibende Hinweise sind nicht blockierend.
- `:app:assembleRelease` einschließlich `lintVitalRelease`: erfolgreich. Debug-/Release-Kotlin,
  Room/KAPT, Ressourcen, Manifest, D8 und APK-Paketierung wurden damit ausgeführt.
- Das Release-APK wurde mit Build Tools 35.0.0 ausgerichtet und anschließend signiert;
  `zipalign -c` sowie `apksigner verify --verbose` sind erfolgreich (v1/v2/v3).
- Zertifikat-SHA-256: `E2:24:54:89:2F:ED:43:53:1D:8F:F4:F4:71:24:B1:D3:96:BE:9E:19:1B:61:7C:38:40:CB:09:26:07:33:3D:C1`.
- APK-Metadaten: Paket `de.rdoe.weeklydjshows`, `versionName=1.3.1`, `versionCode=19`,
  `targetSdk=35`.
- APK-SHA-256: `bbcbcdb3cca7035f7034d28a6de6909647ff3c36c34b875789ed4f9cd5106585`.

Es war in der Buildumgebung kein physisches Android-/One-UI-Gerät angeschlossen. Deshalb sind
OEM-/Netzwerk-spezifische Punkte zusätzlich auf echten Geräten zu prüfen: ein realer SoundCloud-
Direktstream samt Wechsel auf einen gerade fertiggestellten Download, RSS-Vorschauen verschiedener
Server, Overlaygrößen/-positionen und die konkreten Medienkontrollen von One UI sowie weiterhin
Chromecast, Smart View und der Bluetooth-Vordergrundstart.

## Regulärer Build

Voraussetzungen: JDK 17, Android SDK 35, Build Tools 35.0.0 und Gradle 8.9. Danach beispielsweise:

```bash
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:assembleRelease
```

Die Release-Ausgabe ist absichtlich nicht über eine im Repository hinterlegte SigningConfig
signiert. Vor einer Auslieferung wird der regulär erzeugte Release-Build zuerst ausgerichtet und
dann mit dem privaten Update-Schlüssel signiert.

## Unveränderliche Update-Signatur

Aktiver SHA-256-Zertifikatsfingerabdruck ab 1.3.0:

`E2:24:54:89:2F:ED:43:53:1D:8F:F4:F4:71:24:B1:D3:96:BE:9E:19:1B:61:7C:38:40:CB:09:26:07:33:3D:C1`

Für Folgeversionen niemals einen neuen Schlüssel erzeugen. Die 1.2.13-Test-APK verwendet das alte,
nicht mehr verfügbare Zertifikat und muss einmal deinstalliert werden. Für künftige Updates ist
1.3.1 die aktuelle Update-Basis.

## Pflicht für die nächste Version

Der Nutzer benötigt weiterhin beides: eine regulär gebaute, ausgerichtete, mit obigem Zertifikat
signierte APK und den exakt dazugehörigen vollständigen Quellstand als ZIP. Private Keystores und
Passwörter gehören nicht in das Quellarchiv.
