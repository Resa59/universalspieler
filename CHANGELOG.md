# Changelog

## 1.3.1 — 2026-08-11

- SoundCloud wird wieder direkt abgespielt. Beendet ein laufender Download, wechselt dieselbe
  Folge ohne Positionsverlust auf die lokale Datei und beendet die Netzwerknutzung.
- Bei einer erneut begonnenen, bereits gehörten Folge zeigt der Kartenbalken den neuen Stand;
  die separate Gehört-Markierung bleibt erhalten.
- RSS-Treffer besitzen eine direkte Feedparser-Vorschau als Rückfall, und der Fortsetzen-Dialog
  hat eine kompakte, inhaltsabhängige Höhe.
- Das eigene Mini-Overlay bleibt innerhalb der Bildschirmbreite, nutzt flache Symbole, bildet den
  Queue-Zustand korrekt ab, merkt sich seine Position und verschwindet bei geöffneter Haupt-App.
  Die quadratische Ansicht blendet Pause/Spulen/Schließen erst bei Interaktion ein.
- 1001Tracklists steht zuerst unter Musik-Podcasts. 1001Tracklists, Spotify und Mixcloud werden
  nicht mehr als Quelle für das automatische Angebot der neuesten Folge angeboten.
- Die Medienbenachrichtigung reserviert die mobilen Android-Plätze für Queue zurück/vor und führt
  Gefällt mir sowie die Spulaktionen als nachgeordnete Aktionen.
- VersionCode ist 19; Datenbankversion und Signierschlüssel bleiben unverändert.

## 1.3.0 — 2026-08-11

- Navigation und Bibliothek sind neu geordnet; der App-Name trägt keinen Zusatz mehr.
- Kaltstart-Wiederaufnahme, vollständige Gehört-Markierung, deduplizierter löschbarer Verlauf und
  Rückkehr nur tatsächlich aus der Queue gestarteter, unterbrochener Folgen sind umgesetzt.
- Geplante YouTube-Folgen sind sichtbar gekennzeichnet, optional aus `Neu` ausblendbar und werden
  in der Warteschlange ausschließlich dann geprüft, wenn sie tatsächlich an der Reihe sind.
- Wort- und Musik-Podcasts besitzen getrennte Bereiche, Update-/Neu-/Sichtbarkeitseinstellungen
  sowie Aktionen für oben, unten und Kategorienwechsel.
- Zusätzlich zum Show-JSON gibt es vollständige ZIP-Sicherung und -Import mit optionalen Downloads.
  Der neue Standardkatalog-Merge schützt eigene Shows, Ausblendungen und absichtliche
  Verschiebungen und bestätigt Reihenfolge bzw. entfernte Standards getrennt.
- Kaltstart-Sync, Leerlaufende, Fortsetzen-/Neueste-Karte und die umfangreichen Bluetooth-
  Autostartvarianten sind einzeln konfigurierbar.
- Das eigene Mini-Overlay ist als quadratisches Cover oder Querformatkarte verfügbar; Android-PiP
  bleibt als separate Systemoption erhalten.
- App-/Katalogupdate-Grundlage, Diagnose-/Feedback-Teilen und optionale NewPipe-/Extractor-Prüfung
  sind integriert.
- Datenbankversion 7 baut die private Vorabdatenbank bei Bedarf bewusst neu auf. VersionCode ist 18.

## 1.2.13 — 2026-08-08

- Im Vollbild-Player sitzt rechts oben eine Cast-Auswahl. Chromecast/Google Cast übernimmt die
  laufende Folge samt Titel und separat übermitteltem Cover; bei lokalen Downloads wird für das
  Cast-Gerät wieder die erreichbare Onlinequelle verwendet. Miracast/Smart View ist als
  systemeigener Bildschirmübertragungs-Fallback für kompatible Fire-TV-/Smart-TV-Geräte verlinkt.
- Die PiP-Fortschrittskante prüft nun die tatsächliche letzte Pixelzeile des Covers in voller
  Bildauflösung. Nur wenn diese überwiegend weiß/nahezu weiß ist, wird ein dunkler Cover-Farbton
  verwendet; die in 1.2.11 überarbeiteten PiP-Buttons bleiben unverändert.
- Cover vom Buzzsprout-Storage (u. a. „The Martin Garrix Show“) werden mit einem expliziten
  App-User-Agent geladen. Damit umgeht die App die 403-Antwort, die der CDN für OkHttp-Standard-
  User-Agents liefert, ohne Bild-URLs umzuschreiben.
- Mixcloud-Profil-URLs wie `mixcloud.com/ArminvanBuuren/` sind abonnierbar und werden über die
  öffentliche Mixcloud-API seitenweise aktualisiert. Weil die offizielle API keine Audio-Streams
  bereitstellt, öffnet ein Play-Tipp den offiziellen Mixcloud-Player in App oder Browser.
- Media3 wurde für die native Cast-Integration auf 1.9.2 aktualisiert; Datenbankversion 4 bleibt
  unverändert.

## 1.2.12 — 2026-08-08

- Die PiP-Fortschrittskante bleibt im Normalfall unverändert weiß. Nur wenn die tatsächlichen
  untersten Bildzeilen überwiegend weiß oder nahezu weiß sind, wird eine kontrastierende
  Artwork-Akzentfarbe verwendet.
- Für diesen Sonderfall wird ähnlich einer Android-Artwork-Palette ein gewichteter Akzent aus dem
  ganzen Cover bestimmt: Farbigkeit, Helligkeit und die Häufigkeit der Pixel beeinflussen die
  Auswahl, während die weiße Fläche selbst praktisch kein Gewicht erhält. Monochrome Covers
  erhalten als letzte Rückfallebene ein dunkles Navy.
- Die PiP-Buttons und die übrige 1.2.11-Oberfläche bleiben unverändert.

## 1.2.11 — 2026-08-08

- Die PiP-Fortschrittskante bewertet jetzt gezielt die tatsächliche untere Coverkante. Sie nimmt
  eine helle oder dunkle, aus dem Cover abgeleitete Tönung mit dem jeweils besseren Kontrast; der
  frühere häufige Rückfall auf reines Weiß entfällt. Es gibt weiterhin keine Restspur, Schattierung
  oder Verlauf.
- Die PiP-Aktionssymbole sind sichtbar größer. Play/Pause und Folgenwechsel verwenden größere
  Vektoren; 10 Sekunden zurück und 30 Sekunden vor nutzen neu gezeichnete, saubere runde
  Seek-Symbole statt der bisherigen kleinen Eigenkonstruktion.

## 1.2.10 — 2026-08-08

- Bluetooth-Autostart verwendet für Android 14/15 nun einen Activity-`PendingIntent` mit den
  expliziten Background-Activity-Launch-Opt-ins auf Sender- und unter Android 15 auch Creator-Seite,
  statt nur `startActivity()` aus dem Bluetooth-Receiver aufzurufen. Die Nutzerfreigabe „Über
  anderen Apps einblenden“ bleibt Voraussetzung; für Samsung/Standby gibt es zusätzlich einen
  direkten Weg zu `App-Info → Akku`, wie ihn auch Blitzer.de/Tasker für zuverlässigen
  Hintergrundbetrieb empfehlen.
- Die Bluetooth-Seite merkt sich lokal den letzten Autostartversuch und kann unterscheiden, ob der
  Android-Aufruf lediglich versendet oder die `MainActivity` tatsächlich erreicht wurde. Die
  antippbare Fallback-Benachrichtigung bleibt erhalten.
- Der kleine eingeklappte Player in der App zeigt den Wiedergabestand jetzt als schmalen Streifen
  direkt an seiner Unterkante.
- Die schwebende quadratische PiP-Ansicht ist im Ruhezustand auf das Cover reduziert. Titel,
  Verlauf und graue Restspur entfallen; nur der bereits abgespielte Anteil färbt die 4-dp-Unterkante.
  Die Farbe übernimmt einen Hue des Covers und wird je nach Helligkeit des unteren Bildbereichs
  hell oder dunkel gewählt und anschließend auf ausreichenden Kontrast geprüft.
- Die nativen PiP-Aktionsgrafiken sind optisch größer. Wenn Android mindestens fünf PiP-Aktionen
  zulässt und das Fenster groß genug ist, werden Folgenwechsel und 10/30-Sekunden-Spulen gemeinsam
  um Play/Pause angeordnet; Geräte mit maximal drei Aktionsplätzen behalten die gewählte
  Dreierbelegung. Androids native Mindestgröße bleibt systemseitig vorgegeben.

## 1.2.9 — 2026-08-08

- Der `Shows`-Knopf funktioniert wieder direkt nach einem Kaltstart mit Startansicht `Neu`; die
  oberste Navigation stellt keine gespeicherte `Neu`-Destination mehr wieder her, wenn ausdrücklich
  `Shows` gewählt wurde.
- Die Android-Picture-in-Picture-Ansicht ist immer quadratisch. Nicht bedienbare, in das PiP
  gezeichnete Pseudo-Tasten sind entfernt; nach Antippen stellt Android drei echte Aktionen bereit:
  −10/Play-Pause/+30 oder vorherige Folge/Play-Pause/nächste Folge. Die Größenänderung bleibt bei
  Androids eigener Zwei-Finger-Steuerung.
- Die Media3-Systemsteuerung verwendet feste Media-Button-Slots. Auf kompatiblen Fünferansichten
  lautet die Reihenfolge unabhängig von der Warteschlange: Gefällt mir · −10 · Play/Pause · +30 ·
  nächste Folge.
- Gekoppelte Bluetooth-Geräte bleiben nach dem ersten Einlesen auch bei ausgeschaltetem Bluetooth
  sichtbar; Ein-/Ausschalten, Kopplungsänderungen und Verbindungen aktualisieren den Bildschirm
  automatisch. Verbindungsereignisse werden lokal als „zuletzt genutzt“ gespeichert und bestimmen
  künftig die Reihenfolge vor der alphabetischen Sortierung.
- Der pro Gerät wählbare Bluetooth-Autostart kann nun die von Android vorgesehene Freigabe „Über
  anderen Apps einblenden“ anfordern. Mit dieser ausdrücklichen Freigabe darf die Activity auch aus
  Hintergrund/Standby starten; der Bildschirm wird geweckt, die Gerätesperre aber nicht umgangen.
  Ohne Freigabe bleibt die antippbare Benachrichtigung als Rückfall.
- `1001Tracklists` besitzt auf der Show-Startseite ein eigenes Markenbild statt des generischen
  Kopfhörer-Platzhalters.
- Qualitätsangaben verwenden ein Gleichzeichen statt des missverständlichen Näherungszeichens.
  Zusätzlich gibt es `Maximal = beste verfügbare` und eine unabhängige Downloadqualität. Bei
  Plattformdownloads wird nur unter direkt speicherbaren Audiospuren gewählt; einzelne RSS-Dateien
  bleiben unverändert.
- Der variable Leerraum unter aufklappbaren Podcastbeschreibungen wurde reduziert, indem der
  übergroße Material-Button durch einen kompakten Textschalter ersetzt wurde.
- Die Folgenübersicht einer Show merkt sich vor dem Öffnen einer Folge die sichtbare Folge und den
  Pixelversatz. Zurück führt dadurch wieder an dieselbe Stelle, auch wenn sich die Liste inzwischen
  durch neue Folgen verschoben hat.

## 1.2.8 — 2026-08-08

- Die mitgelieferte Show-Ansicht übernimmt die gewünschte Reihenfolge: `1001Tracklists` steht als
  integrierte Webseite an Position 1, danach folgen die ersten 27 Podcasts exakt wie im gelieferten
  Export; alle weiteren sichtbaren Shows stehen alphabetisch und ausgeblendete Shows alphabetisch
  am Ende der Verwaltungsansicht.
- Pro Podcast gibt es für `Neu` drei Modi: alle Folgen, nur die neueste Folge oder keine Folge. Die
  ersten beiden Nachrichten-Podcasts starten mit „nur neueste Folge“. Für die ersten 17
  informationsorientierten RSS-Podcasts ist die automatische Feed-Bereinigung voreingestellt.
- RSS-Podcasts lassen sich manuell bereinigen oder beim Aktualisieren automatisch schlank halten.
  Entfernt werden ausschließlich lokal gespeicherte Folgen, die im erfolgreich gelesenen RSS-Feed
  nicht mehr vorkommen; Likes, Downloads und Warteschlangeneinträge sind zusätzlich geschützt.
- Die Showdetailseite ist kompakter, ihr Drei-Punkte-Menü enthält die `Neu`-Auswahl, Bereinigung und
  „Quell-Link kopieren“. Die Showbeschreibung bleibt kompakt aufklappbar; auf einer einzelnen
  Folgenseite wird die Beschreibung dagegen vollständig angezeigt.
- Die Show-Reihenfolge liegt in einem eigenen Einstellungs-Untermenü und unterstützt Drag & Drop.
  Die Startansicht nach einem echten App-Start ist standardmäßig `Neu`; unter „Startseite“ lässt
  sie sich auf `Shows` umstellen.
- Ein Pull-to-refresh innerhalb einer konkreten Podcastseite aktualisiert jetzt ausschließlich
  diesen Feed und besitzt einen eigenen Ladezustand; `Shows`/`Neu` aktualisieren weiterhin global.
- Bibliothek und Suche lassen ihre Reiter per Links-/Rechts-Wisch wechseln. Der kleine Player lässt
  sich nach oben zum großen Player ziehen, der große nach unten minimieren; Zurück funktioniert
  weiterhin wie bisher.
- Der große Player kann eine native Android-Picture-in-Picture-Miniansicht öffnen: Folgenbild,
  dezenter Verlauf, Fortschritt und kompakte Player-Symbole bleiben sichtbar. Drei Darstellungsgrößen
  und wahlweise Spul- oder Folgenwechsel-Befehle sind einstellbar; Android übernimmt Verschieben,
  Vollbild, Schließen und die geräteabhängige Größenänderung.
- Die Android-Mediensteuerung bietet wieder `Nächste Folge` rechts neben Play/Pause an; Previous
  bleibt zugunsten der Seek-Aktionen ausgeblendet, sodass Play/Pause in der Fünferbelegung mittig
  steht.
- `1001Tracklists` öffnet ohne Podcast-Detailseite direkt in einem integrierten WebView ohne
  Adresszeile. Die App-Leiste bietet Schließen sowie Web-Zurück/Web-Vor; System-Zurück arbeitet
  zunächst den Webverlauf ab.
- Bluetooth-Automatik ist pro bereits gekoppeltem Gerät getrennt konfigurierbar: App bei Verbindung
  öffnen und/oder eine vorhandene pausierte Wiedergabe fortsetzen. Für Androids mögliche
  Hintergrundstart-Sperre ist eine antippbare Fallback-Benachrichtigung vorgesehen.
- Datenbankversion 4 ergänzt `latestMode` und die automatische RSS-Bereinigung verlustfrei.

## 1.2.7 — 2026-08-07

- App-Kaltstart und Rückkehr aus dem Hintergrund lösen keinen vollständigen Netzwerk-Refresh mehr
  aus. Auch der pauschale Cover-Bulk-Prefetch beim Application-Start entfällt; manuelle Refreshes,
  Initialsync einer frischen Datenbank und der sechs-stündige WorkManager-Sync bleiben bestehen.
- Bei jedem echten Titelwechsel wird die Wiedergabegeschwindigkeit auf `1,0×` zurückgesetzt. Ein
  Resolver-Retry desselben Titels verändert die Geschwindigkeit nicht.
- Mini- und Vollbild-Player unterstützen Links-/Rechts-Wischen für nächsten/vorherigen Titel und
  besitzen einen reaktiven Gefällt-mir-Knopf. Die Zeitleiste des Vollbild-Players bleibt eine reine
  Seek-Geste.
- Die Android-Mediensteuerung erhält 10 Sekunden zurück, 30 Sekunden vor und Gefällt mir als eigene
  MediaSession-Aktionen; die appinterne Previous-/Next-Steuerung bleibt erhalten.
- Ein bereits grüner/abgehakter Warteschlangenknopf entfernt die Folge beim nächsten Druck wieder,
  statt im bestätigten Zustand funktionslos zu sein.
- Lange Show-, Vorschau- und Folgenbeschreibungen sind zunächst auf vier Zeilen mit Ellipse
  begrenzt und aufklappbar. Der Plattform-/Original-URL-Infoblock auf Showseiten wurde entfernt.
- Apple-Podcasts-Treffer übernehmen den von Apple gelieferten RSS-Feed. Direkte
  `podcasts.apple.com`-Links werden über ihre Podcast-ID aufgelöst und der Feed vor dem Hinzufügen
  geprüft.
- RSS-/Atom-Vorschauen behalten die 4-MiB-Grenze, können bei größeren Feeds aber vollständige
  Einträge aus dem begrenzten Präfix anzeigen, statt den gesamten Treffer allein wegen der Größe
  abzulehnen.
- Die Detailnavigation bleibt pfadtreu, verhindert aber doppelte Ziele im aktuellen Pfad. Dadurch
  funktioniert etwa `Folge → Show → Zurück → Folge` weiterhin natürlich, während erneutes Öffnen
  einer bereits im Pfad vorhandenen Folge/Show den vorhandenen Backstack-Eintrag verwendet und
  Rückwärtsnavigation keine Seite-zu-Seite-Zyklen mehr erzeugt.

## 1.2.6 — 2026-08-07

- RSS-/Atom-Synchronisierung bricht nicht mehr nach den ersten 250 XML-Einträgen ab. Der Parser
  scannt den vollständigen Feed und wählt erst anschließend chronologisch aus; dadurch werden
  aktuelle Folgen auch dann erkannt, wenn ein Anbieter eine neue Staffel hinter die alte Staffel
  an den Feed anhängt.
- `itunes:season` und `itunes:episode` werden als zusätzliche Ordnungsinformation ausgewertet, wenn
  ein Feed für einzelne Folgen kein belastbares Veröffentlichungsdatum liefert.
- Das lokale RSS-Limit wurde auf 1.000 Folgen erhöht. Der Nassau-Beach-Club-Fall mit 441 Folgen und
  zwei Staffeln passt damit vollständig in eine Show, statt Staffel 2 hinter dem früheren
  250-Einträge-Abbruch zu verlieren.
- Der voreingestellte Nassau-Feed verwendet auf frischen Installationen die kanonische HTTPS-Adresse
  `https://www.music-zone.es/PodcastNBI/NassauIbiza.xml`. Bestehende Test-Abos benötigen keine
  Migration; die bisherige HTTP-Adresse folgt weiterhin der Serverweiterleitung und wird mit der
  neuen Parserlogik normal aktualisiert.
- Beim Bereinigen langer Feeds werden undatierte Folgen nicht über ihren lokalen Fundzeitpunkt vor
  tatsächlich datierte Folgen geschoben.

## 1.2.5 — 2026-08-07

- Discovery-Treffer werden nicht mehr aufgrund ähnlicher Titel, gemeinsamer Hosts oder ähnlicher
  Metadaten zusammengeklebt. Plattformübergreifend greift der Text-Fallback nur noch bei exakt
  gleichem normalisiertem Titel und Herausgeber und nur, solange diese Identität nicht mehrdeutig
  ist; dadurch können Treffer nicht mehr transitiv völlig fremde Playlists miteinander verbinden.
- Mehrere Podcast-Verzeichnisse dürfen intern weiterhin unterschiedliche RSS-Adressen melden, in
  der Quellenauswahl wird davon aber nur der bestbewertete RSS-Feed gezeigt. Nicht integrierte
  Verzeichnis-/Plattformziele werden nicht mehr als scheinbar abonnierbare Varianten angeboten.
- YouTube-/SoundCloud- und Spotify-Playlist-Suchen filtern klare Folgenableitungen wie
  „The Anjunadeep Edition 248 …“ aus einer Suche nach der eigentlichen Sendung.
- RSS-/Atom-Kandidaten, die bei der vorhandenen Feedprüfung eindeutig als tot oder ungültig
  bestätigt werden, werden nicht mehr als abonnierbare Suchquelle angeboten. Reine
  Transport-Timeouts, HTTP 408/425/429 und 5xx-Antworten bleiben als vorübergehende Fehler erhalten.
- Eine exakte YouTube-Kanalsuche bietet neben dem Kanal zusätzlich dessen `Livestreams`-Tab an. Der
  vorhandene Vorschau-Resolver prüft ihn erst beim Öffnen, damit normale Suchen keine zusätzliche
  Netzwerkanfrage pro Kanaltreffer erzeugen.
- Die Showseite kennzeichnet die konkrete Plattformvariante dauerhaft, unter anderem
  `YouTube · Livestreams`, `YouTube · Kanal`, `YouTube · Playlist`, `SoundCloud · Profil` und
  `SoundCloud · Playlist`.
- Shows lassen sich lokal umbenennen. Dabei ändert sich ausschließlich der Anzeigename; gespeicherte
  Feed-/Plattform-URL und die gewählte Quellenvariante bleiben erhalten.
- Einstellungen bietet einen portablen JSON-Export/-Import der Show-Ansicht. Er enthält die
  hinzugefügten Feed-/Plattformquellen, konkrete Varianten wie YouTube-Livestreams, eigene Namen,
  Sichtbarkeit, „Neu“-Status, Ansichtsmodus und die exakte Startseitenreihenfolge. Beim Import werden
  gleiche Quellen wiedererkannt und zusätzliche vorhandene Shows nicht gelöscht.
- Plattformfolgen ohne echtes Veröffentlichungs-/Uploaddatum verwenden ihren lokalen Fundzeitpunkt
  nicht mehr als Ersatzdatum für die Sortierung. Undatierte Einträge stehen hinter datierten und
  können dadurch nicht mehr als vermeintlich neue Folge ganz oben erscheinen.
- Spotify-Playlists sind nach dem Hinzufügen keine lokalen Feeds mehr, sondern reine
  `Spotify · Playlist-Links`. Es werden keine Tracks importiert, Synchronisierungen überspringen
  sie, die globale „Neu“-Abfrage schließt sie zusätzlich explizit aus und die Showseite öffnet die
  Playlist direkt in Spotify bzw. über den gespeicherten Weblink.
- Datenbankversion 3 bleibt unverändert; für ältere Test-Abos wird bewusst keine neue Migration
  eingebaut.

## 1.2.4 — 2026-08-07

- Explizite YouTube-Kanaltabs werden nicht mehr zum Gesamtkanal aufgeweitet. Ein Abo von
  `https://www.youtube.com/@alyandfila/streams` liest ausschließlich den von NewPipe als
  `livestreams` ausgewiesenen Tab; normale Videos und Shorts werden nicht zugemischt.
- Dasselbe Scoping gilt für explizite `/videos`- und `/shorts`-Links. Ist ein ausdrücklich gewählter
  Tab vorübergehend nicht verfügbar, scheitert dessen Aktualisierung sichtbar, statt stillschweigend
  auf den gesamten Kanal zurückzufallen.
- Zusammengeführte Suchtreffer mit mehreren echten Bezugsquellen bieten in der Detailvorschau eine
  Quellenauswahl. RSS, YouTube, SoundCloud usw. lassen sich getrennt ansehen; Metadaten und
  Folgenvorschau werden pro gewählter Quelle neu geladen und nur diese Quelle wird abonniert.
- Der Plus-Knopf eines Mehrquellen-Treffers öffnet die Quellenauswahl, statt ungefragt den intern
  bevorzugten RSS-/Plattformtreffer zu abonnieren.
- Ein explizites YouTube-Tab-Abo darf neben dem gleichnamigen Gesamtkanal existieren; identische
  kanonische Tab-URLs bleiben weiterhin durch den URL-basierten Duplikatschutz eindeutig.
- Der Duplikatschutz eines zusammengeführten Suchtreffers vergleicht nur noch die vom Nutzer
  gewählte Quelle. Ein bereits abonnierter RSS-Ableger blockiert dadurch nicht das spätere bewusste
  Hinzufügen der YouTube- oder SoundCloud-Variante derselben Show.
- Die Aktion „Zur Warteschlange“ beobachtet die echte Queue. Nach erfolgreichem Einreihen bleibt das
  Wartelistensymbol mit seinen drei Balken erhalten, sein Plus wird aber durch die Check-Variante
  ersetzt und in demselben `BrandGreen` wie der grüne Offline-/Downloadstatus dargestellt. Nach dem
  Entfernen aus der Queue erscheint automatisch wieder das Plus.

## 1.2.3 — 2026-08-07

- Folgenkarten zeigen die bekannte Laufzeit anstelle des redundanten Episodenlabels „RSS“; Datum,
  Laufzeit und Offline-Status teilen sich die kompakte Metadatenzeile. Folgendetail und
  Plattformvorschau zeigen die Laufzeit ebenfalls.
- „Shows“ und „Neu“ besitzen Pull-to-refresh am oberen Listenanschlag. Zusätzlich startet jedes
  Öffnen der App automatisch einen vollständigen Aktualisierungslauf; ein kurzer Guard verhindert
  doppelte Läufe durch Rotation oder sofortiges Wiederöffnen.
- Neue YouTube-Kanal-/Playlist-Abos speichern die kanonische Plattform-URL als Quelle statt des
  gekürzten öffentlichen Atom-Feeds. Der bestehende NewPipe-Listingresolver folgt Continuations
  über mehrere Seiten.
- YouTube-/SoundCloud-Playlists werden bei der Aktualisierung bis zu 5.000 Einträge tief eingelesen.
  Kanäle erhalten initial bis zu 2.000 Einträge und prüfen anschließend jeweils die 250 neuesten;
  bis zu drei unabhängige Plattform-Abos werden parallel aktualisiert.
- Die YouTube-/SoundCloud-Suche akzeptiert weiterhin ausschließlich Container. Zusätzlich werden
  leere und Ein-Element-Playlists, YouTube-Auto-Mixes (`RD…`) sowie klare Folgenableitungen wie
  „The Anjunadeep Edition 171 …“ aus einer Suche nach „The Anjunadeep Edition“ verworfen. Dadurch
  bleiben echte Kanäle/Profile und dauerhafte Serien-/Sammelplaylists im Vordergrund.
- Pro Plattformfilter werden mehr Kandidaten geprüft, damit das Aussortieren der ungeeigneten
  Playlisttreffer nicht den eigentlich passenden Treffer aus der Ergebnisliste verdrängt.
- Es wird bewusst keine neue Migration für in älteren Testversionen gespeicherte YouTube-Abos
  eingebaut. Der frische Seed verwendet für seinen eingebauten YouTube-Eintrag direkt die
  Plattformquelle; bestehende Test-Abos bleiben unangetastet.
- Titelbasierter Duplikatschutz greift nur noch innerhalb derselben Show-Quellenart. Ein alter
  RSS-Podcast blockiert dadurch nicht das bewusste Hinzufügen einer gleichnamigen YouTube- oder
  SoundCloud-Variante; kanonisch identische Quellen bleiben weiterhin eindeutig.

## 1.2.2 — 2026-08-07

- Discovery-Treffer sind nun selbst anklickbar und öffnen vor dem Abonnieren eine Vorschau mit
  Beschreibung, Quellinformationen und den jüngsten Folgen. Der Plus-Knopf bleibt als direkter
  Schnellweg zum Hinzufügen erhalten.
- Direkte YouTube-/SoundCloud-/Spotify-Plattformlinks werden vor der Anzeige über das jeweilige
  Listing aufgelöst. Titel, Herausgeber, Beschreibung und Artwork ersetzen dadurch generische
  Platzhalter wie „YouTube-Playlist“ oder „SoundCloud-Playlist“; auch spätere Refreshes können
  bestehende Platzhalter verlustfrei mit echten Metadaten ergänzen.
- Das Android-Teilen-Menü bietet Weekly DJ Shows jetzt für Textfreigaben an. Die App extrahiert die
  erste Web-URL auch aus von Plattform-Apps erzeugtem Begleittext, folgt bekannten mobilen
  SoundCloud-/Spotify-Kurzlinks und kanonisiert Trackingparameter vor der Prüfung.
- Über das Teilen-Menü werden nur abonnierbare Container (Feed, Kanal, Profil oder Playlist)
  automatisch hinzugefügt. Einzelne Videos/Tracks werden bewusst abgewiesen.
- YouTube- und SoundCloud-Suche führen Kanal-/Profil- und Playlist-Suche parallel aus und behandeln
  Teilfehler getrennt; Spotify prüft mehr öffentliche Playlist-Kandidaten. Provider, die intern
  werfen oder am Suchzeitlimit enden, bleiben nicht mehr dauerhaft im Status „sucht …“ hängen.
- Feed-/Plattformbeschreibungen werden bis in die lokale Show übernommen. NewPipe-Listings geben
  außerdem Herausgeber und Beschreibung an die App weiter.
- Zurück von Show-/Folgendetails stellt auf „Shows“ und „Neu“ nicht nur einen numerischen Index,
  sondern den zuvor sichtbaren stabilen Show-/Folgenschlüssel samt Pixelversatz wieder her. Neue
  Einträge während des Detailaufrufs verschieben den Rücksprung dadurch nicht mehr.
- Im Vollbild-Player öffnen Artwork und Folgentitel die Folgendetailseite; der Podcastname öffnet die
  zugehörige Showseite.
- Die Folgendetailseite verwendet nun denselben reaktiven Downloadzustand wie Listen und Bibliothek
  und zeigt Warteschlange, laufenden Fortschritt, Abschluss und Fehler unmittelbar am Downloadknopf.

## 1.2.1 — 2026-08-07

- Eingefügte RSS-/Atom-URLs sowie YouTube-Kanal- und Playlist-URLs werden im Suchfeld direkt
  aufgelöst und als abonnierbares Ergebnis angezeigt. Für YouTube-Playlist-URLs wird der
  `playlist_id`-Atom-Feed gespeichert; der Plattformlink bleibt als Wiedergabe-/Fallbackquelle.
- Konkrete Suchbegriffe priorisieren exakte bzw. sehr starke Titelmatches vor der Musikgruppe.
  „The Anjunadeep Edition“ ist als Regressionstest hinterlegt; eine exakte YouTube-Playlist darf
  nicht mehr hinter nur allgemein als Musik klassifizierten Treffern verschwinden.
- Die keyless YouTube-Suche prüft mehr Kanal-/Playlist-Kandidaten, bevor sie das Endergebnis kappt.
- Neue Abonnements erhalten die oberste Position der eigenen Show-Reihenfolge statt der untersten.
- Jede Show besitzt auf der Detailseite den Schalter „Auf ‚Neu‘ anzeigen“. Daten und Folgen bleiben
  beim Ausschalten vollständig erhalten und werden nur aus der globalen Neuansicht gefiltert.
- Datenbankmigration 2→3 ergänzt dafür verlustfrei `hideFromLatest = false`.
- RSS-Feeds unter `feeds.soundcloud.com` werden nicht länger aufgrund ihres Hostnamens als
  „SoundCloud“ umetikettiert; RSS bleibt RSS. Neu abonnierte echte SoundCloud-Profile verwenden
  weiterhin ihren Profil-RSS, sofern NewPipe einen solchen Feed ermitteln kann.
- Die SoundCloud-Suche isoliert Nutzer- und Playlist-Fehler voneinander und versucht einen
  fehlgeschlagenen Teilabruf einmal erneut. Ein funktionierender Teil kann damit weiterhin Treffer
  liefern, statt den gesamten Provider auf „Fehler“ zu setzen.
- Beim Abonnieren werden kanonisch gleiche Feed-/Plattform-URLs sowie exakt normalisierte Shownamen
  gegen alle vorhandenen Shows geprüft. Dadurch kann insbesondere eine voreingestellte Alt-Show
  nicht noch einmal als zweite Show angelegt werden; ein ausgeblendeter Alt-Eintrag wird reaktiviert.
- Der dauerhafte Show-Covercache wurde von 48 auf 160 MiB vergrößert. Bei ungetakteter Verbindung
  werden alle bekannten Show-Cover im Hintergrund vorgeladen; das Startseitenraster wärmt zusätzlich
  die nächsten sichtbaren Cover in den Arbeitsspeicher. Mobilfunk löst keine Massen-Vorabdownloads aus.
- Scrollzustände liegen nun oberhalb der Navigationsziele und werden auch für Show-/Folgendetails
  wiederverwendet. Nach einem Detailaufruf führt Zurück deshalb an die vorherige Listenposition.
- Gedrückthalten einer Show im Startseitenraster bietet „Ganz nach oben verschieben“. Die Aktion
  wechselt nötigenfalls aus der A–Z-Ansicht zurück zur eigenen, weiterhin gespeicherten Reihenfolge.

## 1.2.0 — 2026-08-07

- Discovery-Suchen sind generationensicher: Eine neue Suche bricht die vorherige ab, verwirft
  verspätete Rückgaben und hält Zwischenergebnisse während des Ladens in stabiler Reihenfolge.
- YouTube-Kanäle und -Playlists werden nach Möglichkeit über die kleinen nativen Atom-Feeds
  aktualisiert. SoundCloud-Profile verwenden ihren nativen RSS-Feed; SoundCloud-Playlists behalten
  einen begrenzten NewPipe-Fallback.
- Öffentliche Spotify-Playlists werden ohne gespeicherten Nutzer-Token über öffentliche
  WebPlayer-Metadaten gesucht und aktualisiert. `addedAt` wird als Veröffentlichungsdatum in „Neu“
  übernommen; geschütztes Spotify-Audio bleibt in Spotify selbst.
- Alle Plattformlistings übernehmen vorhandene Veröffentlichungs-/Uploaddaten und besitzen eine
  deterministische Sortierung bei gleichen Zeitstempeln.
- Eine gemeinsame 6-Stunden-Synchronisierung bevorzugt Feed-/HTTP-Cachevalidierung und ruft
  Plattformlistings nur auf, wenn kein Feed existiert oder dessen Aktualisierung fehlgeschlagen ist.
- „Eigene Reihenfolge“ und „A–Z“ sind nun getrennte Ansichtsmodi. A–Z überschreibt die
  benutzerdefinierten `sortOrder`-Werte nicht mehr.
- Qualitätsstufen zeigen ihre Zielbitrate (64/128/160/256 kbit/s) direkt in den Einstellungen.
- Wiedergabegeschwindigkeit wird über feinere Zwischenstufen gewählt; der Pitch bleibt bei 1,0.
- Podcast-/Shownamen auf Folgenkarten, Verlauf, Queue, Suche und Folgendetail führen auf die
  zugehörige Showseite.
- Die MediaSession besitzt eine Activity-Startaktion, sodass ein Tipp auf die Medienbenachrichtigung
  die App öffnet. Wegwischen der App aus der Übersicht pausiert die Wiedergabe und stoppt den
  Mediendienst.
- Crashfix aus 1.1.1 bleibt erhalten: keine intrinsischen Compose-Messungen um Episoden-Artwork.

## 1.1.1 — 2026-08-07

- Absturz beim Rendern von Folgen mit eigenem Artwork behoben: keine intrinsische Messung mehr um
  `SubcomposeAsyncImage`; Episodencover werden stattdessen auf ein festes Darstellungsmaß begrenzt.
- Normale RSS-Quellen werden auf der Show-Startseite nicht mehr redundant mit „RSS“ beschriftet;
  Plattformlabels und Aktualisierungsfehler bleiben sichtbar.
- Warteschlangeneinträge übernehmen den gespeicherten Abspielstand. Fortsetzbare Einträge erhalten
  einen Replay-Knopf für einen bewussten Start bei 0.
- Ein bereits vollständig gehörter Titel startet bei 0; sobald dieser neue Durchlauf begonnen hat,
  kann dessen neuer Abspielstand wieder normal fortgesetzt werden.
- Beschreibungstext ist auswählbar/kopierbar; überzählige HTML-Absatzleerzeilen werden für kompakte
  Tracklisten auf normale Zeilenumbrüche reduziert.
- YouTube-Kanäle/-Playlists und SoundCloud-Profile/-Playlists werden in Suche und Stöbern ohne
  separate API-Schlüssel über NewPipe Extractor v0.26.4 gefunden. Einzeltracks/-videos bleiben
  bewusst von Show-Abonnements ausgeschlossen.
- SoundCloud-/YouTube-Plattformprofile können zusätzlich zum Playlist-Listing als Episodenquelle
  über den NewPipe-Adapter aktualisiert werden.
- Discovery zeigt Providerstatus und die Quellen jedes zusammengeführten Treffers an. Die reguläre
  RSS-Suche verwendet weiterhin Apple Podcasts, Podcast Index, Feedly Legacy und gPodder parallel.

Komponentenstand: Media3 1.5.1, Coil 2.7.0, NewPipe Extractor v0.26.4, targetSdk 35.
