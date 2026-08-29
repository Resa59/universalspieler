# Weekly DJ Shows 1.3.1

Neuimplementierung der früheren AppYet-App **Weekly DJ Shows** als eigenständiges Android-Projekt.

## Parallel zur alten App

- alter Paketname: `com.global.dj.shows`
- neuer Paketname: `de.rdoe.weeklydjshows`
- Version: `1.3.1` (`versionCode 19`)
- App-Icon: unverändertes Original-Icon aus der gelieferten Alt-App-Rekonstruktion

Die neue App kann deshalb parallel zur alten App installiert werden. Alle zukünftigen Versionen dieses Zweigs müssen den neuen Paketnamen beibehalten, damit sie als Updates dieser Parallelversion installiert werden können.

## Enthaltene Funktionen

Version 1.3.1 ergänzt und korrigiert unter anderem Wort-/Musik-Kategorien, eine deduplizierte Verlaufsliste mit
vollständigen Wiedergabedaten, sichere Vollsicherungen mit optionalen Downloads, geplante
YouTube-Folgen, belastbare Wiederaufnahme nach einem Kaltstart, ein eigenes Mini-Overlay als Cover
oder Querformatkarte, Start-/Leerlaufautomatik, Diagnoseberichte sowie optionale App-/NewPipe-
Prüfungen. Die Room-Datenbank wurde dafür bewusst als sauberes Schema neu angelegt; eine Migration
aus Vorabständen ist für diese private Ausgabe nicht erforderlich.

- 138 aus der alten APK rekonstruierte Show-Feeds als Erstausstattung; der nachweislich mit HTTP
  404 entfernte inoffizielle mau5trap-Feed ist standardmäßig ausgeblendet
- kompakte mehrspaltige Startseite mit Show-Covern und direkter Filterung
- `1001Tracklists` steht mit eigenem Markenbild auf der Show-Startseite und öffnet direkt die
  integrierte Webseite mit Web-Zurück/Web-Vor
- persistente RSS-/Atom-Datenbank statt flüchtigem Feed-Cache
- zeitlich aggregierte, durchsuchbare Neuerscheinungsansicht
- Suche in eigenen Shows, Show-Folgen und bereits geladenen Folgen
- generationensichere Mehrquellensuche: alte Suchläufe können eine neuere Suche nicht überschreiben;
  Zwischenergebnisse bleiben bis zum finalen Ergebnis stabil
- direkte Eingabe von RSS-/Atom-, YouTube-Kanal- und YouTube-Playlist-URLs im Discovery-Suchfeld;
  YouTube-Kanäle/-Playlists werden als Plattformquelle gespeichert und nicht mehr auf den kleinen
  öffentlichen Atom-Feed reduziert
- explizite YouTube-Kanaltabs bleiben eigenständige Quellen: insbesondere `.../@kanal/streams`
  liest nur den Livestream-Tab statt Videos und Streams des gesamten Kanals zusammenzuführen;
  `.../videos` und `.../shorts` werden ebenso auf ihren jeweils gewählten Tab begrenzt
- bei einer exakten YouTube-Kanalsuche wird zusätzlich zum Gesamtkanal eine auswählbare
  `Livestreams`-Variante angeboten; auf der späteren Showseite bleibt `YouTube · Livestreams`
  sichtbar, damit mehrere Abos desselben Profils eindeutig auseinanderzuhalten sind
- Plattform-/Feed-Treffer lassen sich vor dem Abonnieren als Vorschau mit Beschreibung und den
  jüngsten Folgen öffnen; YouTube-/SoundCloud-/Spotify-Listings übernehmen dabei echte Titel-,
  Herausgeber-, Beschreibungs- und Artwork-Metadaten statt generischer Playlistnamen
- Treffer werden nur noch bei belastbarer Identität zusammengeführt: gleiche kanonische Quelle/
  Plattform-ID oder – zwischen verschiedenen Anbietern – exakt gleicher normalisierter Titel und
  Herausgeber ohne Mehrdeutigkeit. Ähnlicher Text, gleicher Host oder gleiches Artwork reichen
  ausdrücklich nicht; mehrere RSS-Verzeichnistreffer derselben Show ergeben nur eine RSS-Auswahl
- Android-Teilen-Ziel für Text aus YouTube, SoundCloud und anderen Apps: URLs werden auch aus
  Begleittext extrahiert, bekannte mobile Kurzlinks/Weiterleitungen kanonisiert und nur
  abonnierbare Kanal-/Profil-/Playlistziele automatisch hinzugefügt
- konkrete Namenssuchen priorisieren exakte/starke Titelmatches vor der Musik-Klassifizierungsgruppe,
  ohne die Klassifizierung selbst zu verfälschen
- anklickbare Episoden mit eigener Detailansicht und vollständiger Beschreibung
- lange Showbeschreibungen bleiben kompakt aufklappbar; eine einzelne Folgenseite zeigt ihre
  Beschreibung vollständig, weil dort kein nachfolgender Listeninhalt verdrängt wird; der frühere
  Plattform-/Original-URL-Infoblock auf Showseiten ist entfernt
- „Gefällt mir“-Übersicht
- Downloadübersicht mit echtem Downloadfortschritt und Offline-Wiedergabe derselben Episodenobjekte
- die Folgendetailseite zeigt denselben reaktiven Downloadstatus samt Warteschlange, Fortschritt,
  Abschluss und Fehlerzustand wie Folgenlisten und Downloadübersicht
- Offline-Cover für bewusst heruntergeladene Folgen
- dauerhaft gespeicherte Abspielposition, Gehört-Markierung und ein Verlauf mit genau einem
  sichtbaren Eintrag je Folge; die Detailseite nennt darunter alle Wiedergabedaten, und ein langer
  Druck kann den Verlaufseintrag löschen
- rahmenloser Episoden-Fortschrittsstreifen und dunklere Darstellung bereits gehörter Folgen
- Media3/ExoPlayer in einem `MediaLibraryService`
- Google Cast/Chromecast direkt aus dem Vollbild-Player. Audio-URL und Cover werden als getrennte
  Cast-Metadaten übertragen, sodass der Fernseher das Episoden-/Show-Cover anzeigen kann. Bereits
  lokal heruntergeladene Folgen werden beim Casten wieder auf ihre erreichbare Netzwerkquelle
  aufgelöst. Für Miracast/Smart View (u. a. kompatible Fire-TV-/Smart-TV-Konfigurationen) führt
  dieselbe Cast-Auswahl zu Androids systemeigener Bildschirmübertragung
- Mini-Player, Vollbild-Player, native schwebende Android-Picture-in-Picture-Ansicht und persistente,
  sortierbare Warteschlange; die PiP-Ansicht bleibt immer quadratisch und zeigt im Ruhezustand nur
  das Cover sowie den Wiedergabestand als Unterkante. Diese bleibt normalerweise weiß; nur bei
  tatsächlich weißer/nahezu weißer Bildunterkante wird aus dem Cover ein dunkler Farbakzent
  abgeleitet. Text, Verlauf und graue Restspur sind im Ruhezustand entfernt. Ein Tipp blendet die
  echten Android-Aktionen ein: wahlweise −10/Play-Pause/+30 oder vorherige Folge/Play-Pause/nächste
  Folge. Meldet Android mindestens fünf native PiP-Aktionsplätze, kombiniert eine ausreichend große
  PiP-Ansicht automatisch beide Gruppen. Die absolute Fenster- und Mindestgröße verwaltet Android
  selbst einschließlich Zwei-Finger-Vergrößerung auf unterstützten Geräten
- der eingeklappte Player innerhalb der App zeigt den aktuellen Wiedergabestand zusätzlich als
  schmalen Fortschrittsstreifen direkt an seiner Unterkante
- Warteschlangenaktionen zeigen ihren echten Zustand reaktiv: die drei Listenbalken bleiben
  sichtbar, während `+` nach erfolgreichem Einreihen durch ein grünes Häkchen in `BrandGreen`
  ersetzt wird; ein Druck auf dieses grüne Häkchen entfernt den Titel wieder aus der Warteschlange
- Mini- und Vollbild-Player unterstützen horizontale Gesten auf den Playerinhalten: nach links zur
  nächsten, nach rechts zur vorherigen Folge; die Zeitleiste des Vollbild-Players bleibt dabei eine
  reine Such-/Seek-Geste
- „Gefällt mir“ ist im Mini-Player, Vollbild-Player und in den Android-Medienaktionen verfügbar
- im Vollbild-Player führen Artwork/Folgentitel zur Folgendetailseite und der Podcastname zur
  zugehörigen Showseite
- Warteschlangen-Wiedergabe setzt angefangene Folgen am gespeicherten Stand fort; ein nur bei
  fortsetzbaren Einträgen sichtbarer Replay-Knopf erzwingt Start bei 0
- eigener monochromer Media-Notification-Statusleistenindikator und Musik-Metadaten für die
  Android-/One-UI-Medienoberfläche; Media3-Buttonpräferenzen belegen die fünf Slots fest mit
  Gefällt mir, 10 Sekunden zurück, Play/Pause, 30 Sekunden vor und nächste Folge, unabhängig davon,
  ob bereits weitere Titel in der Warteschlange liegen
- antippbare Media3-Benachrichtigung; Wegwischen der App aus „Letzte Apps“ pausiert Wiedergabe und
  beendet den Wiedergabedienst
- Audiofokus, Bluetooth-/System-Mediensteuerung und Pause bei getrenntem Audioausgang über Media3
- Bluetooth-Automatik aktualisiert die Geräteliste bei Ein-/Ausschalten und Verbindungen live,
  behält bereits eingelesene gekoppelte Geräte auch bei ausgeschaltetem Bluetooth sichtbar und
  lernt eine „zuletzt verbunden“-Reihenfolge. Für ausdrücklich gewählte automatische App-Starts
  kann der Nutzer „Über anderen Apps einblenden“ freigeben. Der Start verwendet auf Android 14/15
  zusätzlich die expliziten Sender-/Creator-Opt-ins für Background Activity Launches via
  `PendingIntent`; das war in 1.2.9 noch nicht umgesetzt. Für Samsung/Standby verlinkt die
  Einstellungsseite außerdem zur App-Akkueinstellung, weil auch Blitzer.de und Tasker für ihren
  Autostart uneingeschränkten Hintergrundbetrieb empfehlen. Ein lokaler Diagnoseeintrag zeigt nach
  einem Versuch, ob die Activity wirklich erreicht wurde; die antippbare Benachrichtigung bleibt
  als Rückfall
- direkte RSS-Audiodateien, HLS und DASH
- YouTube-/SoundCloud-Auflösung über NewPipe Extractor
- Mixcloud-Profile lassen sich als Showquelle abonnieren und über die öffentliche Mixcloud-API
  seitenweise aktualisieren. Da Mixcloud über seine API ausdrücklich keine Audio-Streams liefert,
  öffnet die Wiedergabe einer Mixcloud-Folge den offiziellen Mixcloud-Player in App oder Browser
- aktuelle appseitige YouTube-PoToken/WebView-Integration, gekapselt in `resolver-newpipe`
- kurzlebiger Resolver-Cache; Original-Plattform-URL bleibt die dauerhafte Quelle
- gezielter Nutzer-Fallback „In NewPipe öffnen“ bei Resolverfehlern
- vollständiges Discovery-Core-Modul für Mehrquellensuche, Music-Klassifizierung, Feedprüfung und Genres
- RSS-Suche parallel über Apple Podcasts, Podcast Index, Feedly Legacy und gPodder; Quellenstatus
  und zusammengeführte Fundstellen sind in der Discovery-Ansicht sichtbar
- Apple-Podcasts-Treffer übernehmen den von Apple gemeldeten Herausgeber-RSS und sind dadurch
  direkt abonnierbar; eingefügte/geteilte `podcasts.apple.com`-Links werden über ihre Podcast-ID
  auf diesen RSS aufgelöst und vor dem Hinzufügen verifiziert
- sehr große RSS-/Atom-Feeds bleiben für die Vorschau auf 4 MiB Netzwerk-/Dekompressionsdaten
  begrenzt, werden aber nicht mehr allein wegen dieses Limits verworfen: komplette Folgen aus dem
  gelesenen Präfix werden als gültige, begrenzte Vorschau angezeigt
- verifizierte tote/ungültige Feeds (z. B. dauerhafte HTTP-4xx-Antworten, kaputtes XML oder
  Nicht-Feed-Inhalte) werden aus den abonnierbaren Suchtreffern entfernt; Timeouts, Rate-Limits und
  temporäre Serverfehler gelten dagegen nicht vorschnell als dauerhaft tot
- keyless YouTube-/SoundCloud-Suche nach Kanälen/Profilen und Playlists über den bereits gepinnten
  NewPipe Extractor; einzelne Videos/Tracks, leere/Ein-Element-Playlists, YouTube-Auto-Mixes und
  offensichtlich folgenbezogene Playlist-Treffer werden nicht als Shows angeboten; dieselbe
  Folgenfilterung gilt für Spotify-Playlist-Suchergebnisse
- Abonnieren gefundener RSS- und Plattform-Ergebnisse direkt in die lokale Datenbank
- Duplikatschutz beim Abonnieren auch gegenüber voreingestellten und ausgeblendeten Alt-Shows;
  kanonisch gleiche Quellen und bei RSS exakt gleiche normalisierte Shownamen werden
  wiederverwendet; verschiedene Plattform-URLs sowie RSS-/Plattformvarianten dürfen bewusst
  koexistieren
- Plattformlisting für von NewPipe unterstützte YouTube-/SoundCloud-Kanäle, Profile und Playlists
- YouTube-Kanäle/-Playlists werden direkt über NewPipe-Listings aktualisiert; SoundCloud-Playlists
  ebenso, während SoundCloud-Profile weiterhin einen brauchbaren Profil-RSS verwenden dürfen
- öffentliche Spotify-Playlist-Suche mit Vorschau; beim Hinzufügen entsteht ausschließlich ein
  `Spotify · Playlist-Link`. Tracks werden nicht in die lokale Folgendatenbank übernommen, die
  Verknüpfung wird bei Synchronisierungen übersprungen und kann niemals die Ansicht „Neu“ füllen
- 160 MiB LRU-Diskcache ausschließlich für Show-Cover; ein App-Kaltstart stößt keinen Bulk-Download
  des gesamten Coverbestands mehr an. Das Startseitenraster wärmt nur nahe Cover; ein regulärer
  Hintergrund-Sync darf den Cache weiterhin ausschließlich bei ungetakteter Verbindung ergänzen
- Show-Ausblenden/-Löschen, eigenes Reihenfolge-Untermenü mit Drag & Drop plus umschaltbare
  A–Z-Ansicht; jede Show besitzt für „Neu“ die Modi alle Folgen, nur neueste Folge oder aus
- der lokale Anzeigename jeder Show ist frei umbenennbar, ohne die gespeicherte Feed-/Plattform-URL
  zu verändern; damit kann etwa ein YouTube-Profil den Namen der eigentlichen Radiosendung tragen
- unter Einstellungen lässt sich weiterhin nur die Show-Ansicht als lesbares JSON exportieren.
  Zusätzlich gibt es eine vollständige ZIP-Sicherung mit Shows, Folgen, Spielständen, Verlauf,
  Likes, Warteschlange und sämtlichen Einstellungen; Audiodownloads und Offline-Cover sind optional
- wiederholte Importe werden nach stabilen IDs zusammengeführt. Eigene Shows, ausgeblendete Shows
  und bewusste Verschiebungen bleiben erhalten; neue Standardshows fehlen nach einem Katalogupdate
  nicht. Neue Reihenfolge und veraltete Standardshows werden getrennt bestätigt
- Gedrückthalten einer Show bietet innerhalb ihrer Kategorie „Ganz nach oben“, „Ganz nach unten“
  und den Wechsel zwischen Wort- und Musik-Podcast
- Folgenkarten zeigen Veröffentlichungsdatum und Laufzeit statt eines redundanten „RSS“-Labels;
  auch Plattformvorschauen und Folgendetails übernehmen die bekannte Laufzeit
- Folgen ohne von der Quelle geliefertes Veröffentlichungsdatum werden nicht mehr mit ihrem lokalen
  Entdeckungszeitpunkt als scheinbar neu einsortiert, sondern hinter datierten Folgen angezeigt
- Pull-to-refresh auf `Shows`, `Neu` und innerhalb einer konkreten Podcastseite; im Podcast wird nur
  dieser Feed aktualisiert. Ein echter Prozess-Kaltstart stößt standardmäßig einmal eine
  Aktualisierung an, die in den Einstellungen abschaltbar ist; bloßes Zurückholen aus dem
  Hintergrund tut das nicht
- RSS-/Atom-Feeds werden vollständig gescannt, bevor das lokale Episodenlimit angewendet wird;
  dadurch funktionieren auch Feeds, die eine neue `itunes:season` erst hinter einer alten Staffel
  anhängen. Veröffentlichungsdatum und `itunes:season`/`itunes:episode` bestimmen die Auswahl statt
  der zufälligen XML-Reihenfolge; bis zu 1.000 Folgen pro normalem Feed bleiben lokal verfügbar
- persistente, schlüsselbasierte Scrollpositionen über Detailnavigation und Zurück: auch wenn beim
  Rücksprung neue Folgen hinzugekommen sind, wird wieder derselbe Show-/Folgeneintrag positioniert;
  das gilt nun auch beim Öffnen einer Folge aus der gescrollten Folgenübersicht einer konkreten Show
- Detailnavigation hält den tatsächlich genommenen Pfad, entfernt aber Zyklen: Folge → Show → Zurück
  führt korrekt wieder zur Folge; wird dagegen ein bereits im aktuellen Pfad vorhandenes Ziel erneut
  geöffnet, wird der vorhandene Eintrag aufgedeckt statt ein zweites Exemplar auf den Backstack zu legen
- getrennte bevorzugte Streamingqualität für WLAN und mobile Daten mit klaren Gleichzeichen bei
  64/128/160/256 kbit/s sowie `Maximal` für die beste verfügbare Audiospur; Downloads besitzen eine
  eigene Qualitätswahl und verwenden bei Plattformquellen die beste direkt speicherbare Audiospur
- fein abgestufte Podcast-Wiedergabegeschwindigkeit bei unveränderter Tonhöhe; jeder Wechsel auf
  einen neuen Titel setzt die Geschwindigkeit bewusst wieder auf 1,0×
- sechs-stündige WorkManager-Hintergrundaktualisierung plus optionaler Sync bei echtem Kaltstart;
  ausgeblendete Shows und komplett deaktivierte Wort-/Musikkategorien werden dabei nicht geladen

## Module

```text
app                UI, Navigation, AppGraph, Seed der Alt-Shows
core-model         neutrale Playback-/Resolvermodelle
data-database      Room: Shows, Folgen, Likes, Downloads, Positionen, Queue
data-feeds         RSS/Atom-Parser, Feed-Sync, WorkManager
playback           MediaLibraryService, Media3, Downloads, Player-Controller
resolver-api       neutrale Resolver-Schnittstellen
resolver-direct    lokale und direkte HTTP-/HLS-/DASH-Quellen
resolver-newpipe   NewPipe Extractor, Downloader, PoToken, Plattformlisting
show-discovery     bestehendes Discovery Core 0.1.0 als Quellmodul
ui-components      wiederverwendbare Compose-Komponenten
```

## NewPipe-Stand

Der Quellstand pinnt `NewPipeExtractor v0.26.4`. Die PoToken-Hilfe stammt aus NewPipe 0.29.0 und
ist auf den UI-losen Resolverteil reduziert. Die App kann optional nutzerverständlich auf einen
neueren Extractor- oder NewPipe-App-Stand hinweisen; sie aktualisiert diese interne Bibliothek aber
nicht zur Laufzeit. Der Ablauf für ein Entwicklerupdate steht in `DEVELOPMENT.md`.

## Speicherverhalten

Feed-Metadaten liegen in Room und sind klein. Show-Cover liegen in einem auf 160 MiB begrenzten
LRU-Diskcache. Der App-Start lädt den bekannten Showbestand nicht mehr pauschal vor; nach einem
planmäßigen Sync darf der Cache bei ungetakteter Verbindung ergänzt werden. Beim Scrollen werden
nahe Raster-Cover zusätzlich in den Arbeitsspeicher vorgewärmt. Auf
getakteten Verbindungen erzeugt dieses Vorauswärmen keine Massen-Netzwerkabrufe. Die reguläre
Feed-Aktualisierung übernimmt neue Artwork-URLs automatisch in diesen Cache. Reguläre Episodenbilder
werden nur im Arbeitsspeicher gehalten und fallen beim Scrollen/unter Speicherdruck wieder heraus.
Während ein Episodenbild lädt, dient das bereits gecachte Show-Cover als Fallback. Bewusste
Audio-Downloads und deren Offline-Cover zählen nicht als Cache und bleiben erhalten, bis der Nutzer
den Download löscht.

Eine heruntergeladene Folge wird nicht dupliziert. `EpisodeEntity` enthält sowohl die Feed-/Plattformquelle als auch den optionalen lokalen Dateipfad. Bei vollständigem Download gewinnt der lokale Pfad beim Abspielen immer vor Netzwerk oder Resolver.

## Bauen und prüfen

Vorausgesetzt werden JDK 17 sowie Android SDK 35 mit Build-Tools 35.0.0. Der Gradle-Wrapper ist Bestandteil des Projekts.

```bash
./gradlew :app:assembleDebug
./gradlew test
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
```

Version 1.3.0 verwendet Datenbankversion 7 und darf bei einem inkompatiblen Vorabstand bewusst neu
aufbauen. Die vorhandene 1.2.13-Test-APK hatte einen nicht mehr verfügbaren Schlüssel. Deshalb muss
sie einmal deinstalliert werden. Ab 1.3.0 gilt dauerhaft dieser neue Zertifikatsfingerabdruck:

`E2:24:54:89:2F:ED:43:53:1D:8F:F4:F4:71:24:B1:D3:96:BE:9E:19:1B:61:7C:38:40:CB:09:26:07:33:3D:C1`

Der private Schlüssel liegt nur im separaten Signatur-Übergabepaket. `SIGNING.md` erklärt die
unveränderliche Verwendung für Folgeversionen.

## Bewusste Grenzen von 1.3.0

- Spotify-Playlists besitzen keinen allgemeinen RSS-Feed und werden deshalb bewusst nicht als Feed
  simuliert. Die App kann öffentliche Metadaten zur Vorschau lesen; hinzugefügt wird aber nur ein
  direkter Spotify-Link ohne lokale Folgen oder „Neu“-Einträge.
- SoundCloud stellt einen Podcast-RSS-Feed auf Profilebene bereit, aber keinen allgemeinen RSS-Feed
  für jede Playlist. SoundCloud-Playlists werden deshalb direkt und seitenweise über NewPipe
  aktualisiert. Dasselbe gilt nun für YouTube-Playlists; dabei gilt eine Sicherheitsgrenze von
  5.000 Listeneinträgen. YouTube-Kanäle werden initial bis 2.000 Einträge eingelesen und danach mit
  einem 250-Einträge-Fenster auf neue Uploads geprüft.
- Mixcloud liefert über seine öffentliche API Metadaten und Uploadlisten, aber ausdrücklich keine
  Audio-Stream-URLs. Profile und Folgen sind deshalb abonnierbar, die eigentliche Wiedergabe öffnet
  jedoch den offiziellen Mixcloud-Player statt einen nicht bereitgestellten Stream in Media3 zu
  simulieren.
- Chromecast/Google Cast ist als native Remote-Wiedergabe integriert. Fire TV unterstützt je nach
  Modell/Setup Miracast-Bildschirmspiegelung; Amazons früheres Fling-SDK ist seit März 2026 aus dem
  Standardsupport, während Matter Casting für eine produktive Senderintegration eine separate
  Matter-/Attestierungsintegration auf Sender- und Empfängerseite erfordert. Die App verwendet
  deshalb für Fire TV/Smart TVs den systemeigenen Miracast-/Smart-View-Weg und kein veraltetes
  Fling-SDK.
- Der integrierte Offline-Downloader lädt progressive HTTP-Audioquellen. HLS/DASH werden online abgespielt, aber noch nicht segmentweise offline gespeichert.
- Android beschränkt Activity-Starts aus dem Hintergrund seit Android 10. Für den expliziten
  Bluetooth-Autostart kombiniert die App die Nutzerfreigabe „Über anderen Apps einblenden“ mit den
  seit Android 14/15 vorgesehenen `PendingIntent`-BAL-Opt-ins. Ohne Overlay-Freigabe kann One UI den
  Start weiterhin blockieren; die Fallback-Benachrichtigung bleibt deshalb erhalten. Die
  Akku-Ausnahme ist eine vom Nutzer wählbare Zuverlässigkeitseinstellung und wird nicht heimlich
  erteilt.
- Die native PiP-Mindestgröße sowie die maximale Zahl gleichzeitig sichtbarer nativer PiP-Aktionen
  kommen vom Android-System. Auf Geräten, die nur drei Aktionsplätze melden, kann die App trotz
  vergrößertem PiP nicht gleichzeitig die beiden vollständigen Dreiergruppen aus Spulen und
  Folgenwechsel anzeigen. Seit Android 12 springt ein Doppeltipp systemseitig zwischen der
  minimalen und maximalen PiP-Stufe; die App kann weder diese Mindestgröße weiter verkleinern noch
  erzwingen, dass ein neues PiP immer in der Minimalstufe startet.
- Die eigenständig nutzbare `show-discovery`-Bibliothek behält ihre offiziellen, schlüsselbasierten
  YouTube-/SoundCloud-Provider. In der Android-App werden genau diese beiden Provider durch den
  vorhandenen NewPipe-Adapter ersetzt. Spotify kann öffentliche Playlists ohne Nutzer-Token zur
  Suche/Vorschau finden; ein Hinzufügen speichert nur den externen Link.

## Lizenzhinweis

NewPipe Extractor und die adaptierten PoToken-Bestandteile stehen unter GPL-3.0-or-later. Daher wird dieser Quellstand einschließlich der entsprechenden Änderungen zusammen mit der APK unter den Bedingungen der beiliegenden `LICENSE` bereitgestellt. Die PoToken-Ursprungsdateien stammen aus der offiziellen NewPipe-Version 0.29.0 und sind im Modul `resolver-newpipe` funktional auf den benötigten Teil reduziert beziehungsweise angepasst.
