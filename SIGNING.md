# Release-Signatur

Ab Version 1.3.0 muss jede APK mit demselben privaten Update-Schlüssel signiert werden. Der
zugehörige SHA-256-Zertifikatsfingerabdruck lautet:

`E2:24:54:89:2F:ED:43:53:1D:8F:F4:F4:71:24:B1:D3:96:BE:9E:19:1B:61:7C:38:40:CB:09:26:07:33:3D:C1`

Der Schlüssel und sein Passwort befinden sich ausschließlich im separaten privaten
Signatur-Übergabepaket. Dieses Paket mindestens zweimal verschlüsselt und an getrennten Orten
sichern. Wer Schlüssel und Passwort verliert, kann keine installierte 1.3.x-Version mehr per APK
aktualisieren. Wer beides erhält, kann Updates im Namen der App signieren.

Die alte 1.2.13-Test-APK hat ein anderes Zertifikat und muss deshalb vor der ersten Installation
von 1.3.0 einmal deinstalliert werden. Danach bleiben normale Updates möglich.

Beispiel nach `./gradlew :app:assembleRelease`:

```bash
zipalign -f -p 4 app-release-unsigned.apk app-release-aligned.apk
apksigner sign \
  --ks WeeklyDJShows-update-key.p12 \
  --ks-key-alias weekly-dj-shows \
  --ks-pass file:PASSWORD.txt \
  --key-pass file:KEY_PASSWORD.txt \
  --out WeeklyDJShows.apk \
  app-release-aligned.apk
zipalign -c -v 4 WeeklyDJShows.apk
apksigner verify --verbose --print-certs WeeklyDJShows.apk
```

`PASSWORD.txt` und `KEY_PASSWORD.txt` enthalten im Übergabepaket absichtlich dasselbe Passwort,
weil `apksigner` beide Eingaben getrennt liest.

