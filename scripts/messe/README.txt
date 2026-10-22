TRS Launcher – Messe-Ausgabe
============================

Was ist das?
  Eine eigene Fassung des TRS Launchers für den Messestand. Im Leerlauf läuft eine
  Präsentation im Vollbild über dem Launcher. Ein Klick, Tipp oder Tastendruck beendet
  sie und zeigt den echten Launcher – Besucher können ihn live ausprobieren.
  Nach einer Weile ohne Eingabe (Standard 90 Sekunden) erscheint „Noch da? 10 …“.
  Jede Eingabe bricht das ab, sonst geht es zurück zur Startseite und die
  Präsentation startet neu. Solange ein Spiel läuft, wartet die Präsentation.

Starten
  TRS-Launcher-Messe.exe doppelklicken. Keine Installation nötig.
  Voraussetzung: Microsoft Edge WebView2 (bei Windows 10/11 vorhanden).
  Der normal installierte TRS Launcher bleibt unberührt und kann parallel laufen
  (eigene Kennung, eigene Daten, kein Auto-Update, keine trs-launcher://-Links).
  Der Launcher startet im Vollbild, zuerst kommt die Start-Animation, danach die Präsentation.
  Minecraft ausprobieren: Ist kein Konto angemeldet, startet das Spiel im offiziellen
  Demo-Modus von Minecraft (Demo-Welt, kein Mehrspieler, kein Konto nötig). Ist ein
  Stand-Konto angemeldet, spielen Besucher mit diesem Konto.

Personal-Panel
  Strg + Umschalt + M  → Personal-PIN eingeben → Panel.
  Wurde die EXE mit --pin gebaut, gilt diese PIN ab Werk (im Panel änderbar; eine neu
  gebaute EXE mit neuer PIN setzt sie wieder auf die eingebaute). Sonst wird die PIN beim
  allerersten Öffnen festgelegt (4–8 Ziffern, zweimal eingeben).
  Nach 5 falschen Versuchen ist die Eingabe 30 Sekunden gesperrt.
  Im Panel:
    - Leerlaufzeit (Sekunden ohne Eingabe bis zum Countdown, 20–1800, Standard 90)
    - Sprache Deutsch/English (Präsentation und Launcher)
    - Personal-Modus: hebt die Besucher-Sperren auf, bis die Präsentation wieder startet
      (z. B. zum Anmelden eines Microsoft-Kontos oder für Einstellungen)
    - Vollbild an/aus
    - Präsentation jetzt starten / Demo zurücksetzen (Startseite, alle Dialoge zu)
    - PIN ändern
    - Launcher beenden

Schließen
  Alt+F4 und der Schließen-Knopf fragen nach der PIN (Besucher können den Launcher
  nicht beenden). Beenden geht auch über das Personal-Panel.

PIN vergessen?
  Launcher über den Task-Manager beenden und diesen Ordner löschen:
    %LOCALAPPDATA%\dev.theredstonee.trslauncher.messe\EBWebView
  Danach sind PIN (auf die eingebaute), Leerlaufzeit und Sprache zurückgesetzt (die Launcher-Daten bleiben).

Wo liegen die Daten?
  Launcher-Daten (Konten, Instanzen, Einstellungen, Spiele):
    %APPDATA%\TRS-Launcher-Messe
  Fenster, Logs und Browser-Speicher (PIN, Messe-Einstellungen):
    %LOCALAPPDATA%\dev.theredstonee.trslauncher.messe   bzw.   %APPDATA%\dev.theredstonee.trslauncher.messe
  Die Daten des normalen Launchers (%APPDATA%\TRS-Launcher) werden nicht angefasst.
  Liegt eine Datei „portable.txt“ neben der EXE, landen die Launcher-Daten stattdessen
  im Ordner „data“ neben der EXE.

Für Besucher gesperrt (außer im Personal-Modus)
  Microsoft-Konten anmelden/abmelden (auch im Einrichtungs-Assistenten), Instanzen löschen/verschieben/exportieren, Welten löschen,
  Teilen/Hochladen (Logs, Absturzberichte, Modpacks), Datenordner öffnen/umziehen,
  Ordner im Explorer und Links im Browser öffnen, Team-/Admin-Bereich, Creator-Dashboard,
  TRS-Dienste abschalten/TRS-Daten löschen, Einstellungen „Datenschutz“, „Speicher“,
  „Fernbedienung“, „Spiel-Standards“ (Hooks/JVM), „Java“, „Netzwerk“, Hooks je Instanz,
  Einrichtungs-Assistent neu starten. Updates gibt es in dieser Fassung keine.

Vorbereitung am Stand
  1. Launcher starten, Präsentation antippen.
  2. Strg+Umschalt+M → PIN eingeben bzw. festlegen → „Personal-Modus“ an (sonst ist das Anmelden gesperrt).
  3. Einrichtungs-Assistent durchgehen bzw. (optional, sonst Demo-Modus) unter „Accounts“ das Microsoft-Konto des Stands anmelden,
     Demo-Instanzen/Modpacks installieren und einmal starten.
  4. Strg+Umschalt+M → „Präsentation jetzt starten“ (schaltet den Personal-Modus wieder aus).

Präsentation aktualisieren
  Die Präsentation steckt in der EXE. Neue Fassung = neu bauen (im Repository):
    node scripts/messe/build.mjs --pitch <Ordner mit index.html + assets/> --out <Zielordner> [--pin <PIN>]
  (ohne --pitch wird die zuletzt importierte Präsentation bzw. der Platzhalter genommen;
   --debug für einen schnellen Test-Build, --with-client-mod legt die TRS-Client-Jars
   für Offline-Betrieb neben die EXE).
  Nur die Präsentation importieren: node scripts/messe/import-pitch.mjs <Ordner>
