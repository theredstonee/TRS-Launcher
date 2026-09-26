[English](PRIVACY.md) · [**Deutsch**](PRIVACY.de.md) · [Español](PRIVACY.es.md)

# Datenschutz

Der TRS Launcher läuft auf deinem Computer. Er hat **keine Telemetrie, keine Analyse, keine Absturzberichte und keine
Werbung**. Informationen an einen Server des TRS-Launcher-Projekts schickt er nur, wenn du die optionalen
[TRS-Dienste](#trs-dienste) (Umhänge, Freunde, Online-Status) einschaltest. Ohne deine Einwilligung sendet der Launcher
dorthin nichts.

Zu anderen Diensten verbindet sich der Launcher nur, wenn das für etwas nötig ist, worum du ihn gebeten hast:

| Dienst | Wann | Was gesendet wird |
|---|---|---|
| Microsoft / Xbox Live / Minecraft-Dienste | Anmelden, Spiel starten | Übliche OAuth-Anmeldung; dein Minecraft-Zugriffstoken beim Spielstart |
| Mojang (`piston-meta`, `libraries`, `resources`) | Eine Version installieren oder starten | Download-Anfragen für Spieldateien |
| Mojang-Sitzungsserver (`sessionserver.mojang.com`) | Anmeldung bei den TRS-Diensten (nur nach deiner Zustimmung) | Dieselbe „join“-Anfrage wie bei der Anmeldung auf einem Minecraft-Server: dein Zugriffstoken, deine UUID und eine einmalige Challenge |
| Mojang-Profildienste (`api.mojang.com`, `sessionserver.mojang.com`, `textures.minecraft.net`) | Skin per Spielername importieren, Spielergesichter anzeigen (Freunde, Admin-Suche) | Der gesuchte Spielername bzw. die UUID; der Download des Skin-Bildes |
| Die Website eines Links, den du eingibst | Nur wenn du einen Skin „per Link“ importierst | Eine normale Download-Anfrage für dieses Bild (nur HTTPS, ohne Cookies oder Konten) |
| TRS-Dienste (`trs-launcher.theredstonee.de`, bisher auch `api.theredstonee.de`) | Nur nach deiner Zustimmung, siehe [unten](#trs-dienste) | Deine UUID, dein Name, deine Umhang-Wahl, Freunde und Online-Status |
| Maven-/Meta-Server von Fabric, Quilt, Forge, NeoForge | Einen Modloader installieren | Download-Anfragen |
| Modrinth (`api.modrinth.com`, `cdn.modrinth.com`) | Inhalte durchsuchen, installieren oder aktualisieren | Suchanfragen, Datei-Hashes installierter Mods (für die Update-Prüfung) |
| CurseForge (`api.curseforge.com`; Dateien und Bilder von `edge.forgecdn.net`, `mediafilez.forgecdn.net`, `media.forgecdn.net`) | Nur wenn du CurseForge als Quelle wählst, ein CurseForge-Modpack installierst, Inhalte von CurseForge installiert hast oder eine CurseForge-Instanz mit fehlenden Dateien importierst | Suchanfragen und Filter, die Projekt- und Datei-IDs von CurseForge-Inhalten (für Details und die Update-Prüfung), Download-Anfragen. Wie bei jeder Anfrage im Internet gehört deine IP-Adresse dazu. Ein CurseForge-Konto brauchst du nicht – der Launcher weist sich mit seinem eigenen API-Schlüssel aus, nicht mit Daten über dich. |
| Minecraft-Server in deiner Serverliste | Live-Status anzeigen | Ein üblicher Serverlisten-Ping |
| mclo.gs | Nur wenn du auf „Log teilen“ klickst und bestätigst | Der gewählte Log (neuester Log, ein älterer Log oder ein Absturzbericht), ohne Zugriffstokens und ohne deinen Windows-/Linux-Benutzernamen |
| GitHub (`github.com`) | Nach Launcher-Updates suchen | Eine Anfrage nach dem Update-Manifest |
| Discord-App auf deinem Computer (nur lokal, kein Internet) | Solange der Launcher offen ist und „Discord-Status zeigen“ an ist (Standard), siehe [unten](#discord) | Dein Discord-Status: „Im TRS Launcher“ bzw. Minecraft-Version, Modloader und Spielzeit des laufenden Spiels |

Account-Tokens werden nur auf deinem Computer gespeichert, verschlüsselt mit Windows DPAPI. Beim Deinstallieren wird das
Programm entfernt; deine Daten in `%APPDATA%\TRS-Launcher` kannst du jederzeit löschen.

## Import aus anderen Launchern

Wenn du „Aus anderem Launcher importieren“ öffnest, sucht der Launcher **auf deinem eigenen Rechner** nach anderen
Launchern (offizieller Minecraft Launcher, CurseForge-App, Modrinth App, Prism/MultiMC, Lunar Client, Badlion, Feather,
OneClient, ATLauncher, GDLauncher, TLauncher) und liest deren Instanz-Listen. Davon verlässt nichts deinen Rechner,
und die Dateien der anderen Launcher werden nur gelesen, nie verändert – ihre Datenbanken werden aus einer temporären
Kopie gelesen, die gleich danach gelöscht wird. Anmeldedaten anderer Launcher (Konto- und Token-Dateien) werden nie
gelesen oder kopiert. Beim Import werden Welten, Mods, Pakete, Einstellungen und Serverliste in die neue TRS-Instanz
kopiert; die Projekt- und Datei-IDs, die der andere Launcher gespeichert hat, bleiben erhalten, damit Updates möglich
sind. Nur wenn Dateien einer CurseForge-Instanz fehlen, lädt der Launcher sie von CurseForge (siehe Tabelle oben).

## Kontowechsel im Spiel (TRS Client)

- **Spiel über den TRS Launcher gestartet:** Der TRS Client kann deine Launcher-Konten anzeigen und ohne Neustart
  wechseln. Wählst du ein Konto, gibt der Launcher dem Spiel ein frisches Minecraft-Zugriffstoken – nur über eine lokale
  Verbindung auf deinem Computer (`127.0.0.1`), verschlüsselt und nur an den Spielprozess, den er selbst gestartet hat.
  Der Schlüssel dafür geht beim Start im Speicher an das Spiel und landet nie auf der Festplatte. Dabei verlässt nichts
  deinen PC. „Konto hinzufügen“ im Spiel öffnet die normale Microsoft-Anmeldung des Launchers im Browser.
- **Spiel ohne TRS Launcher gestartet:** Im Spiel hinzugefügte Konten melden sich direkt bei Microsoft, Xbox Live und den
  Minecraft-Diensten an (mit der eigenen Anmelde-App des TRS Launchers). Gespeichert wird nur das Refresh-Token,
  verschlüsselt (Windows DPAPI, sonst AES mit einer Schlüsseldatei in deinem Benutzerordner), in
  `config/trsclient/accounts.json` dieses Spielordners. Zugriffstokens bleiben im Arbeitsspeicher. Entfernst du ein
  Konto im Spiel, wird es gelöscht.
- Für die kleinen Gesichter in der Liste lädt das Spiel den Skin von `textures.minecraft.net` und bei Bedarf das
  öffentliche Profil von `sessionserver.mojang.com`.

## Garderobe im Spiel (TRS Client)

Die Garderobe des TRS Clients (Skins, Outfits, Umhänge, Emotes und der Skin-Editor) geht nur für das online, was du
dort tust:

- **Skin anwenden oder Minecraft-Umhang wählen** schickt das Skin-Bild und die Armform (Classic/Slim) bzw. die
  Umhang-Wahl mit deinem Minecraft-Zugangs-Token direkt an Mojang (`api.minecraftservices.com`) – genau wie der
  offizielle Launcher. Um deine Umhänge zu zeigen, liest das Spiel dort dein Minecraft-Profil und lädt die Bilder von
  `textures.minecraft.net`.
- **Skin per Spielername hinzufügen** sucht den Namen bei Mojang (`api.mojang.com`, `sessionserver.mojang.com`) oder,
  mit eingeschalteten TRS-Diensten, über die TRS-Dienste und lädt diesen Skin von `textures.minecraft.net`.
- **Skin per Link hinzufügen** lädt genau dieses eine Bild von der eingegebenen Adresse – nur HTTPS, ohne Cookies und
  nie von Adressen in deinem lokalen Netz.
- **Skin aus einer Datei hinzufügen** öffnet den Dateidialog deines Systems; die Datei wird nur auf deinem PC gelesen.
- **Mit eingeschalteten TRS-Diensten** sind deine Skins in der Garderobe dieselben „Meine Skins“ wie im Launcher (mit
  deinem TRS-Konto synchronisiert, siehe unten), und deine Favoriten, Outfits (Name, Skin, Umhang) und die Plätze des
  Emote-Rads werden in einem kleinen Eintrag „wardrobe“ deines TRS-Kontos gespeichert, damit sie auf jedem PC gleich
  sind. Die Wahl eines TRS-Umhangs wird in deinem TRS-Konto gespeichert; nach dem Anwenden eines Skins meldet das Spiel
  das den TRS-Diensten, damit andere TRS-Spieler den neuen Skin früher sehen.
- **Ohne TRS-Dienste** bleibt alles auf deinem PC unter `config/trsclient/wardrobe/` im Spielordner.

## Discord

Läuft die Discord-App auf deinem Computer, zeigt der Launcher einen Status auf deinem Discord-Profil („Spielt TRS
Launcher“): „Im TRS Launcher“, solange nur der Launcher offen ist, und beim Spielen die **Minecraft-Version, den
Modloader (z. B. Fabric) und wie lange du schon spielst**. Server-Adressen, Instanz- oder Spielernamen zeigt er nie.

- Der Launcher spricht nur mit der Discord-App **auf deinem eigenen Computer** (Discords lokale Schnittstelle, eine
  Named Pipe bzw. ein lokaler Socket). Er selbst sendet dafür nichts ins Internet und braucht deine Discord-Anmeldung
  nicht.
- Die Discord-App zeigt diesen Status dann auf deinem Profil an – **öffentlich sichtbar für alle, die dein
  Discord-Profil sehen können** (Freunde, Mitglieder gemeinsamer Server). Was Discord damit macht, regelt
  [Discords Datenschutzerklärung](https://discord.com/privacy).
- Beim Schließen des Launchers verschwindet der Status. Läuft Discord nicht, passiert nichts.
- Der Status ist **ab Werk an** und jederzeit abschaltbar unter *Einstellungen → Datenschutz → Discord-Status zeigen*
  (oder in Discord unter *Benutzereinstellungen → Aktivitäts-Privatsphäre*).

## TRS-Dienste

Die TRS-Dienste bringen TRS-Umhänge, eine Freundesliste und einen Online-Status in den Launcher und in die TRS-Client-Mod.
Sie sind **aus, bis du im Launcher zustimmst** (vor der ersten Anmeldung erklärt ein kurzer Hinweis, was gespeichert
wird). Unter *Einstellungen → Datenschutz* kannst du sie jederzeit wieder ausschalten.

### So funktioniert die Anmeldung

Der Launcher meldet sich mit deinem Minecraft-Account genauso an, wie ein Minecraft-Server einen Spieler prüft: Er holt
sich beim TRS-Server eine einmalige Challenge, bestätigt sie mit deinem Minecraft-Zugriffstoken beim Sitzungsserver von
Mojang, und der TRS-Server fragt bei Mojang nach, ob das passiert ist. **Der TRS-Server sieht weder dein Passwort noch
dein Minecraft-Zugriffstoken.** Danach stellt er ein eigenes Token aus, das der Launcher mit Windows DPAPI verschlüsselt
auf deinem Computer speichert und nie an Webinhalte oder an das Spiel weitergibt. Die TRS-Client-Mod meldet sich selbst
über die Spielsitzung an.

### Freunde im Spiel (TRS Client)

Sind die TRS-Dienste an, zeigt die TRS-Client-Mod deine Freundesliste im Spiel. Sie fragt Freunde, Anfragen und
Blockierte beim TRS-Server nur ab, solange der Freunde-Bildschirm (bzw. Serverliste/Pausenmenü mit Freunde-Anzeige)
offen ist – etwa alle 30 Sekunden, sonst alle 90 Sekunden oder gar nicht. Anfragen, Entfernen und Blockieren werden nur
gesendet, wenn du sie anklickst. Die Gesichter der Freunde kommen vom öffentlichen Profildienst von Mojang
(`sessionserver.mojang.com`, `textures.minecraft.net`) und bleiben nur im Arbeitsspeicher. Die Mod speichert davon
nichts auf deinem Computer – außer den Servern, die du in der Serverliste anheftest
(`config/trsclient/server-pins.json`, nur Adressen, wird nirgendwohin gesendet).

### TRS-Symbol im Spiel (TRS Client)

Der TRS Client zeigt ein kleines TRS-Symbol neben den Namen von Spielern, die **gerade mit TRS spielen**: solange sie
mit dem TRS Client in einer Welt oder auf einem Server sind oder ein vom TRS Launcher gestartetes Spiel läuft. Wer nur
den Launcher offen hat oder mit einem anderen Client spielt, bekommt kein Symbol. Dafür meldet der TRS Client etwa
einmal pro Minute „im Spiel“ (Version, Modloader und – nur mit „Server teilen“ – die Serveradresse), solange du in
einer Welt oder auf einem Server bist; der Launcher tut dasselbe, solange ein von ihm gestartetes Spiel läuft. Beides
ist der unten beschriebene Online-Status und endet, wenn du die Welt verlässt bzw. das Spiel beendet ist.

- **Wer es sieht:** Ob jemand gerade spielt, sehen nur Spieler, die **selbst gerade im Spiel** sind – praktisch andere
  TRS-Spieler auf demselben Server, neben Namen, die sie ohnehin sehen. Alle anderen bekommen „kein Symbol“. Dein
  eigenes Symbol siehst du immer. Von dir blockierte Spieler sehen es nie.
- **Abschalten:** „TRS-Symbol zeigen“ (*Einstellungen → Datenschutz*) blendet dein Symbol für alle aus. Den
  Online-Status auf „niemand“ zu stellen versteckt dich nur in Freundeslisten – das Symbol **nicht**.

### Umhänge mit Freunden teilen

Einen Umhang, den du hochgeladen hast, kannst du mit einem Freund teilen, sobald das Team ihn freigegeben hat. Dein
Freund bekommt im Launcher und im Spiel ein Angebot und entscheidet, ob er es annimmt. Dafür speichert der TRS-Server,
welcher Umhang wem von wem wann angeboten wurde und ob das Angebot angenommen ist. Nach dem Annehmen kann dein Freund
den Umhang wie seinen eigenen tragen, und andere TRS-Spieler sehen ihn an ihm. Ein Freund darf den Umhang an seine
eigenen Freunde weitergeben (höchstens 20 Spieler je Umhang).

- **Wer was sieht:** Der Ersteller sieht alle, die den Umhang haben oder ein offenes Angebot dafür (mit ihrem
  Minecraft-Namen), auch Spieler, an die ein Freund ihn weitergegeben hat. Wer den Umhang hat, sieht, von wem er ihn
  bekommen hat, den Namen des Erstellers und die Spieler, an die er ihn selbst weitergegeben hat.
- **Beenden:** Der Ersteller kann den Umhang jederzeit jedem wieder wegnehmen; dann verlieren ihn auch alle, an die
  dieser Spieler ihn weitergegeben hat. Jeder kann einen geteilten Umhang zurückgeben. Wenn du einen Freund entfernst
  oder einen Spieler blockierst, fallen offene Angebote zwischen euch weg; schon angenommene Umhänge bleiben, bis jemand
  sie zurücknimmt. Wird der Umhang gelöscht, vom Team abgelehnt oder ein TRS-Account gelöscht, sind die betroffenen
  Teilungen sofort weg.

### Was gespeichert wird

| Daten | Wozu |
|---|---|
| Minecraft-UUID und Spielername | Um deinen TRS-Account zu erkennen und Freunden deinen Namen zu zeigen |
| Zeitpunkt der Account-Erstellung und der letzten Anmeldung | Account-Verwaltung und Schutz vor Missbrauch |
| Sitzungs-Tokens (nur als SHA-256-Hashes, 30 Tage gültig, höchstens 10 pro Account) | Damit du angemeldet bleibst |
| Deine Datenschutz-Einstellungen (TRS-Symbol, Umhang für andere sichtbar, Online-Status sichtbar für Freunde/niemanden, Server teilen) | Damit sich die Dienste an deine Entscheidungen halten |
| Dein gewählter Umhang, per Code freigeschaltete oder vom Team vergebene Umhänge | Um anderen TRS-Spielern deinen Umhang zu zeigen |
| Umhänge, die du hochlädst (das Bild, neu kodiert ohne Metadaten), ihr Prüfstatus und ein optionaler Name | Umhang-Uploads; jeder Upload wird vom Team geprüft, bevor andere ihn sehen |
| Meldungen, die du zu Umhängen anderer Spieler abgibst (Grund, optionale Notiz) | Moderation |
| Geteilte Umhänge: welche Umhänge du wem angeboten hast (und wer sie weitergegeben hat), offene Angebote an dich und die Umhänge, die Freunde mit dir geteilt haben, jeweils mit Zeitpunkt und ob angenommen | Umhänge mit Freunden teilen (siehe oben) |
| Freunde, Freundschaftsanfragen und Blockierungen | Die Freundesliste |
| Online-Status: „online im Launcher“ oder „im Spiel“ (vom Launcher oder vom TRS Client) mit Version und Modloader und – nur wenn du „Server teilen“ eingeschaltet hast – die Serveradresse | Um Freunden zu zeigen, was du spielst, und sie nachkommen zu lassen; für das TRS-Symbol, während du spielst (siehe oben) |
| Nur mit eingeschaltetem „Mit TRS-Konto synchronisieren“: deine eigenen Skins aus „Meine Skins“ (das Bild, neu kodiert ohne Metadaten, Name und Modell), deine eigenen Mod-Presets (Namen und Modrinth-Projekt-IDs, keine Dateien oder Ordnerpfade) sowie Theme, Akzentfarbe und Sprache, jeweils mit dem Zeitpunkt der letzten Änderung; gelöschte Skins und Presets werden kurz vermerkt | Damit sie auf allen PCs gleich sind, auf denen du diesen Minecraft-Account nutzt |
| Nur mit eingeschalteten TRS-Diensten und eingeschaltetem „Mit TRS-Konto synchronisieren“ im TRS Client (im Spiel): deine TRS-Client-Einstellungen – welche Module an sind und ihre Einstellungen, HUD-Layouts und -Profile, die TRS-Tasten der Module, der Config-Modus für Leistungs-Mods, ob du die Einführung abgeschlossen hast (und das gewählte Modul-Paket) und welche „NEU“-Einträge du geöffnet hast – je Teil mit dem Zeitpunkt der letzten Änderung; keine Wegpunkte, keine Server-Adressen, keine Dateien, Pfade oder Tokens | Damit der TRS Client auf allen PCs und Spielordnern mit diesem Minecraft-Account gleich ist und die Einführung nur einmal erscheint |
| Nur mit eingeschalteten TRS-Diensten: der Garderoben-Eintrag des TRS Clients – deine Lieblings-Skins, Outfits (Name, Skin, Umhang) und die Plätze des Emote-Rads, mit der Zeit der letzten Änderung | Dieselbe Garderobe auf jedem PC |

**Synchronisation:** „Mit TRS-Konto synchronisieren“ (*Einstellungen → Datenschutz*, ab Werk an, solange die
TRS-Dienste an sind) hält deine eigenen Skins, deine eigenen Presets und das Aussehen des Launchers (Theme, Akzentfarbe,
Sprache) auf all deinen PCs gleich. Java, Arbeitsspeicher und alle anderen Einstellungen werden **nicht**
synchronisiert und verlassen deinen PC nie. Schalter aus = keine Synchronisation mehr; was schon synchronisiert wurde,
bleibt auf dem Server, bis du es mit „Alle TRS-Daten löschen“ löschst. Deine synchronisierten Daten kannst nur du
lesen – es gibt keine Admin-Ansicht dafür.

**TRS-Client-Synchronisation:** Die TRS-Client-Mod meldet sich selbst an (siehe oben) und legt ihre eigenen
Einstellungen am selben Ort ab – als ein Dokument von höchstens 64 KB je Account. Das tut sie nur, solange die
TRS-Dienste im Launcher an sind und ihr Schalter „Mit TRS-Konto synchronisieren“ (TRS-Menü → *TRS-Online-Funktionen*,
ab Werk an) an ist; der Schalter selbst, Wegpunkte, die Freelook-Serverliste und Minecrafts eigene Optionen
(options.txt) bleiben auf deinem PC. Das Spiel liest außerdem dein synchronisiertes Theme, Akzentfarbe und Sprache und
schreibt sie zurück, wenn du sie in der Einführung änderst, damit der Launcher folgt. „Alle TRS-Daten löschen“ löscht
auch dieses Dokument.

Der Online-Status liegt **nur im Arbeitsspeicher des Servers**, wird nie auf die Festplatte geschrieben, hat keinen
Verlauf und verfällt **3 Minuten** nach der letzten Aktualisierung. Sehen können ihn nur deine Freunde – und gar
niemand, wenn du „niemand“ einstellst. Nur ob du gerade im Spiel bist, kann zusätzlich als TRS-Symbol erscheinen (siehe
oben).

Admin-Aktionen (etwa das Freigeben eines Umhangs oder eine Sperre) werden zusammen mit der betroffenen UUID in einem
Audit-Log festgehalten.

### Zweck und Rechtsgrundlage

Die Daten werden nur verarbeitet, um die TRS-Dienste bereitzustellen, die du angefordert hast: Umhänge, die Freundesliste,
den Online-Status und die Synchronisation deiner Skins, Presets und des Launcher-Aussehens zwischen deinen PCs.
Rechtsgrundlage ist die Erbringung des von dir gewünschten Dienstes (Art. 6 Abs. 1 lit. b DSGVO).
Die Dienste frei von Missbrauch zu halten (Prüfung von Uploads, Meldungen, Sperren und Ratenbegrenzungen), beruht auf
unserem berechtigten Interesse an einem sicheren Dienst (Art. 6 Abs. 1 lit. f DSGVO). Es gibt keine Werbung, kein
Profiling und keinen Verkauf von Daten.

### Speicherdauer und Löschung

- Deine Daten bleiben gespeichert, solange dein TRS-Account besteht.
- Sitzungs-Tokens verfallen nach 30 Tagen; Abmelden oder Entfernen eines Accounts im Launcher widerruft das Token.
- Der Online-Status verschwindet 3 Minuten nach der letzten Aktualisierung oder sofort, wenn du den Launcher schließt
  und die Welt verlässt.
- Synchronisierte Skins, Presets und Einstellungen bleiben, bis du sie im Launcher löschst (ein auf einem PC gelöschter
  Skin wird auch auf dem Server gelöscht). Vermerke über gelöschte Skins bleiben 30 Tage, damit deine anderen PCs sie
  ebenfalls löschen können.
- **„Alle TRS-Daten löschen“** (*Einstellungen → Datenschutz*) löscht sofort alles (Art. 17 DSGVO): deinen Account,
  Sitzungen, Freundschaften, Anfragen und Blockierungen, hochgeladene Umhänge samt Dateien, geteilte Umhänge (deine
  Umhänge bei Freunden und die Umhänge, die Freunde mit dir geteilt haben), eingelöste Codes, Meldungen,
  deinen Online-Status und alle synchronisierten Skins, Presets und Einstellungen. Danach sind die TRS-Dienste im
  Launcher ausgeschaltet. Die Skins und Presets auf deinem PC bleiben erhalten.
- Nach der Löschung bleibt nur ein bestehender Sperr-Eintrag erhalten (deine UUID, der Grund und der Zeitpunkt), damit
  eine Sperre nicht durch erneutes Anmelden umgangen werden kann.
- Server-Logs enthalten nur technische Daten (Methode, Pfad ohne Query, Status, Dauer, Request-ID) – **keine
  IP-Adressen und keine Tokens**. Ratenbegrenzungen zählen Anfragen pro IP-Adresse und pro Account **nur im
  Arbeitsspeicher**; diese Zähler werden nie auf die Festplatte geschrieben.

### Hosting und Auftragsverarbeiter

- Der TRS-Server läuft auf einem Server in **Deutschland** (von Pterodactyl verwalteter Host). Dort werden die Daten
  gespeichert.
- **Cloudflare** (Cloudflare, Inc. / Cloudflare Germany GmbH) ist Auftragsverarbeiter: Der Server ist nur über einen
  Cloudflare Tunnel erreichbar, und Cloudflare beendet die HTTPS-Verbindung. Cloudflare verarbeitet deshalb in unserem
  Auftrag deine IP-Adresse und die übertragenen Anfragen gemäß dem Data Processing Addendum von Cloudflare.
  Übermittlungen in die USA sind durch das EU-US Data Privacy Framework und Standardvertragsklauseln abgedeckt.
- **Mojang/Microsoft** bestätigt die Anmeldung (siehe oben): Dein Computer schickt die join-Anfrage direkt an Mojang,
  und der TRS-Server fragt bei Mojang mit deinem Spielernamen und der einmaligen Challenge nach (`hasJoined`).

### Deine Rechte

Du hast das Recht auf Auskunft, Berichtigung, Löschung, Einschränkung der Verarbeitung, Datenübertragbarkeit und
Widerspruch (Art. 15–21 DSGVO) sowie das Recht, dich bei einer Aufsichtsbehörde zu beschweren. Das meiste davon kannst
du selbst im Launcher erledigen (Dienste ausschalten, Datenschutz-Einstellungen ändern, alle Daten löschen). Für alles
andere melde dich bei uns.

### Kontakt

Theredstonee – eröffne ein Issue unter <https://github.com/theredstonee/TRS-Launcher/issues> oder nutze die
Kontaktdaten auf <https://theredstonee.de>. Bitte poste keine personenbezogenen Daten in öffentlichen Issues, sondern
frag nach einem privaten Kontakt.
