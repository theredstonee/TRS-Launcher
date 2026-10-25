# Changelog

Every version of the TRS Launcher is listed here – first in English, then in German. The release build copies
the section of its version into the GitHub release and the auto-update, and the launcher shows it once after
updating ("What's new").

<!--
How to write an entry:
- Collect changes under "## Unreleased" while working. For a release, rename it to "## <version> – <YYYY-MM-DD>".
- Every version needs both "### English" and "### Deutsch" with the same points. Write for players, not
  developers: what changed for them, in plain words, no file or function names.
- The release build fails when the section for its version is missing or one language is empty.
- Every release gets a theme name in the heading ("## 0.5.0 – 2026-09-30 – The Clip Update | Das Clip-Update")
  and an update banner right below it: an HTML comment with "banner: accent=#rrggbb motif=/news/0.5.0/banner.png".
  The banner keeps a fixed look (deepslate, redstone wires, pixel font); only the accent colour and the motif change.
  Make the motif in TRS Studio with the "Update-Banner" template (sketch → export as HD pixel art, motif target).
- Right below the banner comes a second HTML comment starting with "shots:" – one screenshot of the new features per
  line: "/news/<version>/<file>.png | English caption | Deutsche Bildunterschrift" (captions optional; without the
  German one the English caption is used for both). PNG or WebP in public/news/<version>/, at most 8, each at most
  2 MB and 640×360 to 3840×2400 px. From 0.6.5 on every release needs at least one screenshot; the release check
  (scripts/changelog.mjs check) fails otherwise. How to take them: docs/release-screenshots.md.
- Pull requests: the CI check "changelog" wants at least one new point under "## Unreleased" in English AND German.
  Changes players don't notice (CI, refactoring, docs) get the label "no-changelog" instead.
- Contributors: the release build adds an HTML comment starting with "contributors:" to the section of its version
  (GitHub names of everyone who contributed since the previous release, see scripts/contributors.mjs). The launcher
  thanks them in "What's new", the GitHub release in "Thanks to / Danke an". Don't write it by hand; to keep the
  thanks in later launcher versions, commit it after the release with
  "node --experimental-strip-types scripts/changelog.mjs set-contributors <version> <name1,name2>".
-->

## Unreleased

### English
- **New design "Fox Den".** Settings → Appearance now has a third design next to Redstone and Winter Night: a Minecraft forest at dusk. Spruces and a few cherry trees stand in front of blocky hills in a pink haze while the square sun sets, fireflies glow over the meadow and leaves and a few cherry petals drift by. By the campfire at the fox den a single fox sleeps curled up and breathes slowly. The play button becomes a warm lantern, loading bars a glowing ember branch, instances without their own banner get a small forest edge at dusk, and on the home page a spruce fence with lanterns lights up while the game starts. Dark is dusk, OLED is late dusk and Light is the golden hour; the accent color starts as fox orange and stays your choice. The start animation stays the normal one.
- **TRS Client: "Fox Den" in the game too.** With the design "Fox Den" the TRS Client gets the same look: the title screen shows the same Minecraft forest at dusk as the launcher – spruces and a few cherry trees, the square sun setting over blocky hills, flat clouds, fireflies, drifting leaves and a few cherry petals, and the single small fox sleeping curled up by the campfire. Buttons become lanterns with warm ember light, redstone wires become glowing branches with little leaves, loading screens show a glowing ember branch with a small flame instead of the lamp row, and the TRS menu, server list, pause menu and toasts use the warm dusk colours with fox orange as the starting accent. You can pick the design in the TRS menu and in the introduction; when the TRS Launcher starts the game, the client follows its design.
- **TRS Client: penguins keep up with boats, horses and fast rides.** When you rode a boat, the penguins didn't swim along – on deep water they were put back onto the seabed again and again, so they only seemed to teleport every now and then. They now swim next to and behind your boat at its speed, and on land they belly-slide after you when you ride or run fast (horse, minecart, elytra landing). They only jump to you as a last resort: when they are more than 20 blocks away or really stuck (for example after you dropped down a cliff). When you fly high above them, they stay on the ground and follow below you.
- **TRS Client: penguin flippers flap outwards.** The flippers of the penguin flapped into its body instead of away from it (when looking around, walking, swimming and jumping). They now flap outwards, like in the cosmetic preview.
- **TRS Client: no more "X-ray water" around wings and other cosmetics.** On Minecraft 1.21.5 and newer, the water behind glowing cosmetics (snow wings, ice wings, glowing halos and more) was cut away in rectangles, so you looked straight down to the seabed – very visible in a boat on the ocean. Water behind and around wings, halos and see-through parts like the ice wings now looks normal. On Minecraft 1.8.9–1.12.2, swimming penguins were drawn on top of the water instead of in it; now the part below the surface is tinted by the water like any other mob.

### Deutsch
- **Neues Design „Fuchsbau“.** In Einstellungen → Darstellung gibt es neben Redstone und Winternacht jetzt ein drittes Design: einen Minecraft-Wald in der Abenddämmerung. Fichten und ein paar Kirschbäume stehen vor blockigen Hügeln im rosa Dunst, die eckige Sonne geht unter, über der Wiese leuchten Glühwürmchen und Blätter und ein paar Kirschblüten treiben vorbei. Am Lagerfeuer vor dem Fuchsbau schläft ein einzelner Fuchs eingerollt und atmet ruhig. Der Spielen-Knopf wird zur warmen Laterne, Ladebalken zum glimmenden Ast, Instanzen ohne eigenes Banner bekommen einen kleinen Waldrand in der Dämmerung, und auf der Startseite leuchtet beim Spielstart ein Fichtenzaun mit Laternen auf. Dunkel ist Dämmerung, OLED späte Dämmerung und Hell die goldene Stunde; die Akzentfarbe beginnt mit Fuchsorange und bleibt frei wählbar. Die Start-Animation bleibt die normale.
- **TRS Client: „Fuchsbau“ auch im Spiel.** Mit dem Design „Fuchsbau“ sieht auch der TRS Client so aus: Der Startbildschirm zeigt denselben Minecraft-Wald in der Abenddämmerung wie der Launcher – Fichten und ein paar Kirschbäume, die eckige Sonne geht über blockigen Hügeln unter, flache Wolken, Glühwürmchen, treibende Blätter und ein paar Kirschblüten, und der eine kleine Fuchs schläft eingerollt am Lagerfeuer. Knöpfe werden zu Laternen mit warmem Glutlicht, Redstone-Leitungen zu glimmenden Ästen mit kleinen Blättern, Ladebildschirme zeigen statt der Lampenreihe einen glimmenden Ast mit kleiner Flamme, und TRS-Menü, Serverliste, Pausenmenü und Meldungen tragen die warmen Dämmerungsfarben mit Fuchsorange als Start-Akzent. Das Design lässt sich im TRS-Menü und in der Einführung wählen; startet der TRS Launcher das Spiel, folgt der Client seinem Design.
- **TRS Client: Pinguine kommen mit Boot, Pferd und schnellen Fahrten mit.** Im Boot schwammen die Pinguine nicht mit – auf tiefem Wasser wurden sie immer wieder auf den Meeresgrund gesetzt und schienen nur ab und zu hinterherzuteleportieren. Jetzt schwimmen sie neben und hinter deinem Boot in seinem Tempo her, und an Land rutschen sie dir auf dem Bauch hinterher, wenn du schnell reitest oder läufst (Pferd, Lore, Elytra-Landung). Zu dir springen sie nur noch als letzter Ausweg: wenn sie mehr als 20 Blöcke entfernt sind oder wirklich festhängen (zum Beispiel, nachdem du eine Klippe hinuntergesprungen bist). Fliegst du hoch über ihnen, bleiben sie am Boden und laufen unter dir mit.
- **TRS Client: Pinguin-Flossen schlagen nach außen.** Die Flossen des Pinguins schlugen in den Körper hinein statt nach außen (beim Umschauen, Laufen, Schwimmen und Springen). Jetzt schlagen sie nach außen, wie in der Kosmetik-Vorschau.
- **TRS Client: Kein „Röntgen-Wasser“ mehr um Flügel und andere Kosmetik.** Ab Minecraft 1.21.5 wurde das Wasser hinter leuchtender Kosmetik (Schneeflügel, Eisflügel, leuchtende Höfe und mehr) rechteckig weggeschnitten – man sah direkt auf den Meeresgrund, besonders deutlich im Boot auf dem Ozean. Wasser hinter und um Flügel, Höfe und durchsichtige Teile wie die Eisflügel sieht jetzt normal aus. Unter Minecraft 1.8.9–1.12.2 lagen schwimmende Pinguine über dem Wasser statt darin; jetzt wird der Teil unter der Oberfläche wie bei jedem anderen Wesen vom Wasser eingefärbt.

## 0.29.0 – 2026-10-24 – First Frost | Erster Frost
<!-- banner: accent=#6fd0ea motif=/news/0.29.0/banner.png -->
<!-- shots:
/news/0.29.0/penguins.png | Penguins with red TRS scarves follow you across the ice | Pinguine mit rotem TRS-Schal folgen dir übers Eis
/news/0.29.0/wardrobe.png | The Cosmetics tab with the Santa hat, the beard switch and two penguins | Der Reiter Kosmetik mit Weihnachtsmütze, Bart-Schalter und zwei Pinguinen
/news/0.29.0/snow-wings.png | Snow wings with drifting snowflakes | Schneeflügel mit rieselnden Schneeflocken
-->

### English
- **Start animation: much quieter, with a volume slider.** The music and effects of the start animation were far too loud. They now start at 25 % of the old level, and Settings → Appearance has a new slider "Start animation volume" (0 % = muted).
- **Winter cosmetics in the launcher: ground companions and switchable parts.** The Cosmetics tab has a new section "Ground companions" for companions that walk next to you instead of floating at your head – like the penguin with its red TRS scarf. The 3D preview places it on the ground next to your character, and buttons show how it stands, walks, slides on its belly, swims and jumps. Creators see how many penguins they have ("2 penguins – creator tier"). Cosmetics with switchable parts get a switch below the preview – for example the beard of the Santa hat: switch it off, and everyone with TRS Client sees the hat without the beard. Redeeming a code for a ground companion, wings or a head companion now offers to put it into the right slot.
- **Team: preview builds for team members.** A new team permission "Preview builds" lets team members download launcher and TRS Client preview builds in the team area, just like Partner creators – no creator status needed.
- **TRS Client – creator preview: map v3.** A new look for the minimap and the world map, first only in the preview build for creators (Partner tier and up) and the TRS team: real 3D relief from all neighbouring heights (also across map borders), water with depth – shallow water shows the seabed, deep water gets darker over a long range –, livelier biome colours, a world map that stays sharp at every zoom level (one block per pixel down to far zoom levels instead of blurry averages), and much faster filling: the map now covers your whole render distance, new chunks appear almost immediately (also when flying fast) and areas that are still loading show a soft placeholder instead of a black hole. The preview keeps its own map files, so your current maps stay untouched and are taken over automatically.
- **TRS Client: no more getting stuck at "Connecting…" or at game start with RRLS (Remove Reloading Screen).** With RRLS installed, joining a server with a server resource pack (or starting the game) could hang forever while the log filled with "Failed to draw overlay!". The TRS loading screen now works together with RRLS (it keeps its look and replaces RRLS's small loading bar), and if drawing it ever fails, a plain TRS screen is shown for that moment while loading always finishes.
- **TRS Client: fixed a crash when opening the HUD editor from the main menu on Minecraft 26.x.** The previews of the armor display and the item counter needed item data that 26.x only loads inside a world; they now stay empty there instead of crashing the game.
- **TRS Client: the hunger display no longer shines through menus.** On Minecraft 1.21.9 and newer (including 26.x), opening the inventory, a chest or a furnace drew the saturation outline, the food and health preview and all TRS HUD elements (FPS, coordinates and more) bright on top of the darkened menu background. They now sit under the menu background and are dimmed just like the vanilla hearts and hunger bar.
- **TRS Client: winter cosmetics.** The TRS Client shows the new winter items: snow wings with drifting snowflakes, glowing see-through ice wings, the Santa hat, the snow cloud that snows on you from above, and penguins. Penguins are a new kind of companion that walks on the ground: they follow you in a small group, waddle and look around, sometimes slide on their belly (more often on snow, ice and downhill), swim on the water surface and hop up blocks after you. If they fall too far behind, they catch up next to you. You see your own penguins too, even in first person. Head items now also animate when you jump, if the item has a jump animation (like the Santa hat).
- **TRS Client: switch parts of cosmetics on and off.** In the wardrobe (Cosmetics) a worn item can now have switches – for example the beard of the Santa hat. Your choice is saved to your TRS account, and everyone with the TRS Client sees it. The wardrobe also has a new section "Companion (ground)" with a preview of the penguin next to your character; creators see how many penguins follow them.

### Deutsch
- **Start-Animation: viel leiser, mit Lautstärke-Regler.** Musik und Effekte der Start-Animation waren viel zu laut. Sie starten jetzt bei 25 % des bisherigen Pegels, und unter Einstellungen → Darstellung gibt es den neuen Regler „Lautstärke der Start-Animation“ (0 % = stumm).
- **Winter-Kosmetik im Launcher: Begleiter am Boden und abschaltbare Teile.** Im Reiter „Kosmetik“ gibt es den neuen Abschnitt „Begleiter am Boden“ für Begleiter, die neben dir herlaufen statt an deinem Kopf zu schweben – wie den Pinguin mit seinem roten TRS-Schal. Die 3D-Vorschau stellt ihn neben deine Figur auf den Boden, und Knöpfe zeigen, wie er steht, läuft, auf dem Bauch rutscht, schwimmt und springt. Creator sehen, wie viele Pinguine sie haben („2 Pinguine – Creator-Stufe“). Kosmetik mit abschaltbaren Teilen bekommt unter der Vorschau einen Schalter – zum Beispiel den Bart der Weihnachtsmütze: schalte ihn aus, und alle mit TRS Client sehen die Mütze ohne Bart. Wer per Code einen Begleiter am Boden, Flügel oder einen Begleiter am Kopf einlöst, bekommt jetzt angeboten, ihn ins richtige Fach zu legen.
- **Team: Vorschau-Builds für Team-Mitglieder.** Ein neues Team-Recht „Vorschau-Builds“ erlaubt Team-Mitgliedern, Vorschau-Builds von Launcher und TRS Client im Team-Bereich zu laden – wie Partner-Creator, ganz ohne Creator-Status.
- **TRS Client – Creator-Vorschau: Karte v3.** Ein neuer Look für Minimap und Weltkarte, zuerst nur im Vorschau-Build für Creator (ab Partner-Stufe) und das TRS-Team: echtes 3D-Relief aus allen Nachbarhöhen (auch über Kartengrenzen hinweg), Wasser mit Tiefe – im Flachwasser sieht man den Grund, tiefes Wasser wird über eine lange Strecke dunkler –, lebendigere Biomfarben, eine Weltkarte, die in jeder Zoomstufe scharf bleibt (ein Block je Pixel bis weit herausgezoomt statt verwaschener Mittelwerte) und deutlich schnelleres Füllen: Die Karte deckt jetzt die ganze Sichtweite ab, neue Chunks erscheinen fast sofort (auch beim schnellen Fliegen), und Bereiche, die noch laden, zeigen einen dezenten Platzhalter statt eines schwarzen Lochs. Die Vorschau speichert ihre Karten getrennt – deine bisherigen Karten bleiben unverändert und werden automatisch übernommen.
- **TRS Client: Kein Hängenbleiben mehr bei „Verbinden…“ oder beim Spielstart mit RRLS (Remove Reloading Screen).** Mit RRLS konnte der Beitritt zu einem Server mit Server-Ressourcenpaket (oder der Spielstart) für immer hängen, während sich das Log mit „Failed to draw overlay!“ füllte. Der TRS-Ladebildschirm arbeitet jetzt mit RRLS zusammen (er behält sein Aussehen und ersetzt die kleine Ladeleiste von RRLS), und schlägt sein Zeichnen je fehl, erscheint kurz eine schlichte TRS-Fläche – das Laden wird immer fertig.
- **TRS Client: Absturz beim Öffnen des HUD-Editors im Hauptmenü unter Minecraft 26.x behoben.** Die Vorschauen von Rüstungsanzeige und Item-Zähler brauchten Item-Daten, die 26.x erst in einer Welt lädt; dort bleiben sie jetzt leer, statt das Spiel abstürzen zu lassen.
- **TRS Client: Die Hunger-Anzeige scheint nicht mehr durch Menüs.** Ab Minecraft 1.21.9 (auch 26.x) lagen der Sättigungsrand, die Essens- und Lebens-Vorschau und alle TRS-Anzeigen (FPS, Koordinaten und mehr) bei offenem Inventar, Truhe oder Ofen hell über dem abgedunkelten Menü-Hintergrund. Jetzt liegen sie darunter und werden genau wie die Vanilla-Herzen und die Hungerleiste abgedunkelt.
- **TRS Client: Winter-Kosmetik.** Der TRS Client zeigt die neuen Winter-Teile: Schneeflügel mit rieselnden Flocken, leuchtende durchsichtige Eisflügel, die Weihnachtsmütze, die Schneewolke, die von oben auf dich schneit, und Pinguine. Pinguine sind eine neue Art Begleiter, die am Boden läuft: Sie folgen dir in einer kleinen Gruppe, watscheln und schauen sich um, rutschen manchmal auf dem Bauch (öfter auf Schnee, Eis und bergab), schwimmen an der Wasseroberfläche und hüpfen dir Blöcke hinauf hinterher. Fallen sie zu weit zurück, tauchen sie wieder neben dir auf. Deine eigenen Pinguine siehst du auch – sogar in der Ich-Perspektive. Kopf-Kosmetik bewegt sich jetzt auch beim Springen, wenn das Teil eine Sprung-Animation hat (wie die Weihnachtsmütze).
- **TRS Client: Teile von Kosmetik an- und ausschalten.** In der Garderobe (Kosmetik) kann ein getragenes Teil jetzt Schalter haben – zum Beispiel den Bart der Weihnachtsmütze. Deine Wahl wird in deinem TRS-Konto gespeichert, und alle mit TRS Client sehen sie. Die Garderobe hat außerdem den neuen Abschnitt „Begleiter (Boden)“ mit einer Vorschau des Pinguins neben deiner Figur; Creator sehen, wie viele Pinguine ihnen folgen.

## 0.28.1 – 2026-10-24 – House Rules | Hausordnung
<!-- banner: accent=#f2b72e motif=/news/0.28.1/banner.png -->
<!-- shots:
/news/0.28.1/server-rules-locked.png | A server with TRS Server Rules locks the minimap – the TRS Client shows a lock | Ein Server mit TRS Server Rules sperrt die Minimap – der TRS Client zeigt ein Schloss
/news/0.28.1/server-rules-limit.png | Settings can be limited by the server, here the zoom factor | Einstellungen kann der Server begrenzen, hier den Zoom-Faktor
-->

### English
- **TRS Client: pixel-art capes are sharp again.** Since TRS Client 0.19.5 many capes – all TRS capes like Tester, Dragon, Ender, Nether and the Halloween capes, plus capes made in HD pixel style – were drawn smoothed and looked blurry. Only real HD images (photos, painted pictures) are smoothed now; everything with a pixel palette stays crisp. The cape preview in the launcher uses the same rule.
- **TRS Client: server rules.** Servers with the new "TRS Server Rules" plugin can turn single TRS Client features off or on for everyone (for example the minimap, gamma or freecam) and fix or limit settings (for example the highest zoom). Locked features are marked "Set by server" in the TRS menu, and the server can show a short note once. Your own settings stay saved and come back as soon as you leave the server. Openly documented: members of the TRS team with the matching permission are asked in game whether they want to use a locked feature anyway – only for themselves and only until they disconnect.
- **New: TRS Server Rules when creating a server.** "Export as server" has a new option "TRS Server Rules (lock TRS Client modules on the server)". It puts the matching server plugin for your server (Fabric, Quilt, Forge or NeoForge and its Minecraft version) into the server – downloaded from the official channel and checked before it is used. With it you decide which TRS Client features players may use on your server. For Fabric it also selects Fabric API, which the plugin needs. If there is no plugin for the server's version yet, the option is greyed out with a short note; "Learn more" opens the page about it on the TRS website.

### Deutsch
- **TRS Client: Pixel-Art-Umhänge sind wieder scharf.** Seit TRS Client 0.19.5 wurden viele Umhänge – alle TRS-Umhänge wie Tester, Drache, Ender, Nether und die Halloween-Umhänge sowie Umhänge im HD-Pixel-Stil – weichgezeichnet und sahen verwaschen aus. Weich gefiltert werden jetzt nur noch echte HD-Bilder (Fotos, gemalte Bilder); alles mit Pixel-Palette bleibt scharf. Die Umhang-Vorschau im Launcher nutzt dieselbe Regel.
- **TRS Client: Server-Regeln.** Server mit dem neuen Plugin „TRS Server Rules“ können einzelne Funktionen des TRS Clients für alle aus- oder einschalten (zum Beispiel Minimap, Gamma oder Freecam) und Einstellungen festlegen oder begrenzen (zum Beispiel den höchsten Zoom). Gesperrtes ist im TRS-Menü mit „Vom Server“ markiert, und der Server kann einmal einen kurzen Hinweis zeigen. Deine eigenen Einstellungen bleiben gespeichert und gelten wieder, sobald du den Server verlässt. Offen dokumentiert: Mitglieder des TRS-Teams mit dem passenden Recht werden im Spiel gefragt, ob sie eine gesperrte Funktion trotzdem benutzen wollen – nur für sich und nur bis zum Trennen.
- **Neu: TRS Server Rules beim Server-Anlegen.** „Als Server exportieren“ hat die neue Option „TRS Server Rules (TRS-Client-Module auf dem Server sperren)“. Sie legt das passende Server-Plugin für deinen Server (Fabric, Quilt, Forge oder NeoForge und die Minecraft-Version) mit auf den Server – geladen aus dem offiziellen Kanal und vor der Nutzung geprüft. Damit bestimmst du, welche TRS-Client-Funktionen Spieler bei dir nutzen dürfen. Bei Fabric wird Fabric API gleich mit ausgewählt, die das Plugin braucht. Gibt es für die Version des Servers noch kein Plugin, ist die Option ausgegraut und ein kurzer Hinweis sagt warum; „Mehr erfahren“ öffnet die Seite dazu auf der TRS-Website.

## 0.28.0 – 2026-10-23 – Your Look | Dein Look
<!-- banner: accent=#f07a22 motif=/news/0.28.0/banner.png -->
<!-- shots:
/news/0.28.0/website-look-on.png | Halloween with the event design | Halloween mit Event-Design
/news/0.28.0/website-look-off.png | Event design turned off – the pumpkin hunt card stays | Event-Design ausgeschaltet – die Karte zur Kürbis-Suche bleibt
-->

### English
- **New: turn off the event design.** Settings → Appearance → "Show event design (e.g. Halloween)". Switched off, the launcher keeps its normal look during an event – no night colours, scenes, moon progress bars, atmosphere or event icon. The pumpkin hunt, trick or treat, stickers and rewards stay available, and every pumpkin can still be found. The TRS Client follows the setting in game.
- **Team area:** each event has a new "Look off for …" list. Players on it get the event without its design and effects; the event content stays for them.
- **Clearer message when sharing a modpack fails.** If the TRS server rejects a modpack, the launcher now shows the server's reason instead of a general "not a valid Modrinth pack". (Large packs saved in the ZIP64 format are accepted again on the server side.)
- **TRS Client: turn the event design off.** The Halloween page in the TRS menu has a new switch "Event design" (on by default). Turned off, the TRS Client looks like it does outside the event – no Halloween theme, title screen or loading bar, no grain, spooky effects, jumpscares or spooky sounds. The pumpkin hunt (all pumpkins stay findable), trick or treat, stickers and rewards keep working. The TRS Client also follows the matching launcher setting and the TRS team's choice for your account.

### Deutsch
- **Neu: Event-Design abschalten.** Einstellungen → Darstellung → „Event-Design anzeigen (z. B. Halloween)“. Ausgeschaltet behält der Launcher während eines Events sein normales Aussehen – keine Nachtfarben, Szenen, Mond-Ladebalken, Atmosphäre und kein Event-Symbol. Kürbis-Suche, Süßes oder Saures, Sticker und Belohnungen bleiben, und alle Kürbisse sind weiter auffindbar. Der TRS Client übernimmt die Einstellung im Spiel.
- **Team-Bereich:** Jedes Event hat eine neue Liste „Look aus für …“. Spieler darauf bekommen das Event ohne Design und Effekte; die Event-Inhalte bleiben für sie.
- **Klarere Meldung, wenn das Teilen eines Modpacks scheitert.** Lehnt der TRS-Server ein Modpack ab, zeigt der Launcher jetzt den Grund des Servers statt eines allgemeinen „kein gültiges Modrinth-Pack“. (Große Packs im ZIP64-Format nimmt der Server wieder an.)
- **TRS Client: Event-Design abschalten.** Die Halloween-Seite im TRS-Menü hat einen neuen Schalter „Event-Design“ (ab Werk an). Ist er aus, sieht der TRS Client aus wie außerhalb des Events – kein Halloween-Thema, kein Halloween-Titelbildschirm oder -Ladebalken, kein Filmkorn, keine Spuk-Effekte, Jumpscares oder Grusel-Töne. Kürbis-Suche (alle Kürbisse bleiben auffindbar), Süßes oder Saures, Sticker und Belohnungen funktionieren weiter. Der TRS Client folgt außerdem der passenden Einstellung im Launcher und der Entscheidung des TRS-Teams für dein Konto.

## 0.27.5 – 2026-10-23 – Clean Cut Fix | Feinschliff
<!-- banner: accent=#3ee6c8 motif=/news/0.27.5/banner.png -->
<!-- shots:
/news/0.27.5/overlay-on-screen.png | The clip overlay now only shows while the game is in front | Das Clip-Overlay erscheint jetzt nur, solange das Spiel vorne ist
-->

### English
- **Fixed: the buffer indicator showed up over other apps.** The small clip overlay ("○ Buffer 30 s", REC, clip messages) stayed on top when you switched to another window, e.g. the browser or Discord. It now only appears while the game is the active window and disappears as soon as you switch away.

### Deutsch
- **Behoben: Die Puffer-Anzeige war auch über anderen Apps zu sehen.** Das kleine Clip-Overlay („○ Puffer 30 s“, REC, Clip-Meldungen) blieb obenauf, wenn du in ein anderes Fenster gewechselt bist, z. B. Browser oder Discord. Es erscheint jetzt nur, solange das Spiel das aktive Fenster ist, und verschwindet, sobald du wegwechselst.

## 0.27.4 – 2026-10-23 – Clean Cut Fix | Feinschliff
<!-- banner: accent=#3ee6c8 motif=/news/0.27.4/banner.png -->
<!-- shots:
/news/0.27.4/overlay-on-screen.png | The clip overlay above the game – it no longer keeps the launcher running after you close it | Das Clip-Overlay über dem Spiel – es hält den Launcher nach dem Schließen nicht mehr am Laufen
-->

### English
- **Fixed: the launcher sometimes didn't open any more.** After playing with clips turned on, closing the launcher could leave it running invisibly in the background – starting it again then did nothing, and updates couldn't install. Closing the launcher window now always quits it completely.

### Deutsch
- **Behoben: Der Launcher ging manchmal nicht mehr auf.** Nach dem Spielen mit eingeschalteten Clips konnte der Launcher nach dem Schließen unsichtbar im Hintergrund weiterlaufen – ein neuer Start tat dann nichts, und Updates ließen sich nicht installieren. Schließt du das Launcher-Fenster, beendet er sich jetzt immer vollständig.

## 0.27.3 – 2026-10-23 – Clean Cut Fix | Feinschliff
<!-- banner: accent=#3ee6c8 motif=/news/0.27.3/banner.png -->
<!-- shots:
/news/0.27.3/overlay-on-screen.png | Buffer indicator and clip messages stay out of your clips | Puffer-Anzeige und Clip-Meldungen bleiben aus deinen Clips heraus
-->

### English
- **Fixed: TRS Client settings and HUD profiles could get lost with "Launch again".** A second copy of an instance – especially with another account – shares the TRS Client settings with the first one. It could write the other account's old settings into them and even into your sync, so profiles disappeared, modules were off and HUD positions were wrong. The second copy now never saves TRS Client settings or syncs them; only the first game does. Changes you make in the second window apply until you close it.
- **Fixed: false "mods don't work together" warning.** Some mods carry an older copy of another mod inside (Distant Horizons, for example, brings its own older Fabric API). The launcher counted that hidden copy even when you have a newer one installed – which is the one the game actually loads – and stopped the launch, e.g. "Sodium doesn't work with Fabric API (inside Distant Horizons)". Now only the copy the game really uses is checked, and the launcher no longer swaps such mods back and forth between versions.
- **Fixed: "Launch again" stopped with a mod error.** A second copy of a running instance now starts with exactly the same mods as the first one – the launcher no longer checks or swaps mods of an instance that is already running.

### Deutsch
- **Behoben: TRS-Client-Einstellungen und HUD-Profile konnten mit „Erneut starten“ verloren gehen.** Eine zweite Kopie einer Instanz – vor allem mit einem anderen Konto – teilt sich die TRS-Client-Einstellungen mit der ersten. Sie konnte die alten Einstellungen des anderen Kontos hineinschreiben und sogar in deinen Sync, sodass Profile verschwanden, Module aus waren und HUD-Positionen nicht mehr stimmten. Die zweite Kopie speichert und synchronisiert die TRS-Client-Einstellungen jetzt nie; das macht nur das erste Spiel. Änderungen im zweiten Fenster gelten, bis du es schließt.
- **Behoben: falsche Warnung „Mods passen nicht zusammen“.** Manche Mods bringen eine ältere Kopie einer anderen Mod mit (Distant Horizons z. B. eine eigene ältere Fabric API). Der Launcher hat diese versteckte Kopie mitgezählt, obwohl eine neuere installiert ist – und die lädt das Spiel tatsächlich – und den Start gestoppt, z. B. „Sodium passt nicht zu Fabric API (in Distant Horizons)“. Jetzt zählt nur die Kopie, die das Spiel wirklich benutzt, und der Launcher tauscht solche Mods nicht mehr zwischen Versionen hin und her.
- **Behoben: „Erneut starten“ brach mit einem Mod-Fehler ab.** Eine zweite Kopie einer laufenden Instanz startet jetzt mit genau denselben Mods wie die erste – der Launcher prüft und tauscht keine Mods einer Instanz mehr, die schon läuft.

## 0.27.2 – 2026-10-23 – Clean Cut | Sauberer Schnitt
<!-- banner: accent=#3ee6c8 motif=/news/0.27.2/banner.png -->
<!-- shots:
/news/0.27.2/overlay-on-screen.png | On screen you still see the buffer indicator and “Clip saved” – exactly where you placed the Clips element | Am Bildschirm siehst du Puffer-Anzeige und „Clip gespeichert“ weiter – genau dort, wo du das Clips-Element hingelegt hast
/news/0.27.2/clip-clean.png | The same moment in the saved clip: no indicator, no message | Derselbe Moment im gespeicherten Clip: keine Anzeige, keine Meldung
-->

### English
- **Clips: no more buffer indicator or clip messages in your recordings.** Like the REC timer, the "○ Buffer 30 s" indicator and short messages such as "Clip saved (30 s)", "Recording started" or errors now appear in the small launcher overlay above the game instead of inside it – you still see them, but they never show up in a saved clip. The overlay sits exactly where you placed the "Clips" element in the HUD editor (by default on the right, above the middle), so it never covers your other HUD elements, and a new message no longer hides "Clip saved" when you start a recording right after it. Needs the new TRS Client; older TRS Client versions keep showing them in the game.
- **Fixed:** when the clip overlay appeared for the first time (e.g. when a recording started), the launcher window could freeze completely and the REC timer never showed up.
- **TRS Client: buffer indicator and clip messages in the launcher overlay.** With a launcher that has the overlay, the TRS Client no longer draws the buffer indicator and clip messages itself. "Show buffer indicator" in the TRS menu still decides whether the buffer indicator appears; hints such as "Clips are off – press again to turn them on" stay in the game.

### Deutsch
- **Clips: keine Puffer-Anzeige und keine Clip-Meldungen mehr in deinen Aufnahmen.** Wie schon der REC-Zähler erscheinen jetzt auch die Anzeige „○ Puffer 30 s“ und kurze Meldungen wie „Clip gespeichert (30 s)“, „Aufnahme läuft“ oder Fehler im kleinen Launcher-Overlay über dem Spiel statt im Spiel selbst – du siehst sie weiter, aber in einem gespeicherten Clip tauchen sie nie auf. Das Overlay sitzt genau dort, wo du das Element „Clips“ im HUD-Editor hingelegt hast (ab Werk rechts, etwas über der Mitte), verdeckt also keine anderen HUD-Elemente, und startest du direkt nach einem Clip eine Aufnahme, bleibt „Clip gespeichert“ trotzdem stehen. Braucht den neuen TRS Client; ältere TRS-Client-Versionen zeigen sie weiter im Spiel.
- **Behoben:** Erschien das Clip-Overlay zum ersten Mal (z. B. beim Start einer Aufnahme), konnte das Launcher-Fenster komplett einfrieren, und der REC-Zähler tauchte nie auf.
- **TRS Client: Puffer-Anzeige und Clip-Meldungen im Launcher-Overlay.** Mit einem Launcher, der das Overlay hat, zeichnet der TRS Client Puffer-Anzeige und Clip-Meldungen nicht mehr selbst. „Puffer-Anzeige zeigen“ im TRS-Menü bestimmt weiter, ob die Puffer-Anzeige erscheint; Hinweise wie „Clips sind aus – noch einmal drücken zum Einschalten“ bleiben im Spiel.

## 0.27.1 – 2026-10-21 – Grand Entrance Fix | Feinschliff
<!-- banner: accent=#ff9a4d motif=/news/0.27.1/banner.png -->
<!-- shots:
/news/0.27.1/intro-hint.png | After the first start animation a small note asks whether the short animation should play every time | Nach der ersten Start-Animation fragt ein kleiner Hinweis, ob die kurze Animation jedes Mal laufen soll
-->

### English
- **No more question in front of the start animation.** The animation now always starts right away. After it has played for the first time, a small note in the launcher asks once whether the short 4-second animation should play on every start – with a box for a calm version without flicker and flashes. On the first start of the day and after updates the animation always plays; you can still skip it with a click or any key.
- **Clearer setting.** Under Settings → Appearance, “Short start animation on every launch” now only turns off the 4-second version; “Flicker and flashes in the start animation” switches to the calm version.
- **Fixed:** the long start animation after an update could disappear after about two seconds.

### Deutsch
- **Keine Abfrage mehr vor der Start-Animation.** Die Animation startet jetzt immer sofort. Nachdem sie das erste Mal gelaufen ist, fragt ein kleiner Hinweis im Launcher einmal, ob die kurze 4-Sekunden-Animation bei jedem Start laufen soll – mit einem Häkchen für die ruhige Fassung ohne Flackern und Lichtblitze. Beim ersten Start des Tages und nach Updates läuft die Animation immer; ein Klick oder eine beliebige Taste überspringt sie weiterhin.
- **Klarere Einstellung.** Unter Einstellungen → Darstellung schaltet „Kurze Start-Animation bei jedem Start“ jetzt nur die 4-Sekunden-Fassung ab; „Flackern und Lichtblitze in der Start-Animation“ wechselt zur ruhigen Fassung.
- **Behoben:** Die lange Start-Animation nach einem Update konnte nach etwa zwei Sekunden einfach verschwinden.

## 0.27.0 – 2026-10-21 – Grand Entrance | Großer Auftritt
<!-- banner: accent=#ff9a4d motif=/news/0.27.0/banner.png -->
<!-- shots:
/news/0.27.0/intro-redstone.png | The new start animation: a redstone signal races through a dungeon until the lamp ignites | Die neue Start-Animation: Ein Redstone-Signal rast durch den Dungeon, bis die Lampe zündet
/news/0.27.0/intro-halloween.png | During Halloween a moonlit graveyard with pumpkins and a ghost opens the launcher | Zu Halloween öffnet ein Friedhof im Mondlicht mit Kürbissen und Geist den Launcher
/news/0.27.0/intro-winter.png | With the Winter Night design a penguin colony slides across the frozen lake | Mit dem Design Winternacht rutscht eine Pinguin-Kolonie über den zugefrorenen See
/news/0.27.0/intro-flicker-ask.png | On the first start you choose whether you want flicker and flashes | Beim ersten Start wählst du, ob du Flackern und Lichtblitze sehen möchtest
-->

### English
- **New start animation.** When the launcher opens, a short pixel-art scene with its own music plays while the app loads in the background: in the Redstone design a signal races through a dark dungeon, pistons slam open and a redstone lamp ignites into the logo. During Halloween you fly over a moonlit graveyard full of jack-o'-lanterns, and in the Winter Night design a colony of penguins in scarves and beanies slides, tumbles and hops across a frozen lake until the aurora flares. It takes about 4 seconds, a little longer on the first start of the day, and after an update you get the full 10-second version once.
- **Skip it, calm it or turn it off.** Click or press any key to skip. On the first start you are asked once whether you want to see flickering and flashes of light – without them the same scenes play calmly with steady lights and a soft glow. Under Settings → Appearance you can change that, turn off the animation on every launch and mute its sound. The update version still plays once after each update. With reduced animations only the logo fades in, and the sound stays quiet while the launcher window is in the background.

### Deutsch
- **Neue Start-Animation.** Beim Öffnen des Launchers läuft eine kurze Pixel-Szene mit eigener Musik, während die App im Hintergrund lädt: Im Redstone-Design rast ein Signal durch einen dunklen Dungeon, Kolben schlagen auf und eine Redstone-Lampe zündet zum Logo. Zu Halloween fliegst du über einen mondhellen Friedhof voller Kürbislaternen, im Design Winternacht rutscht, purzelt und hüpft eine Pinguin-Kolonie mit Schals und Mützen über einen zugefrorenen See, bis das Polarlicht aufflammt. Normal dauert sie etwa 4 Sekunden, beim ersten Start des Tages etwas länger, und nach einem Update gibt es einmal die ganze 10-Sekunden-Fassung.
- **Überspringen, beruhigen oder abschalten.** Ein Klick oder eine beliebige Taste überspringt sie. Beim ersten Start wirst du einmal gefragt, ob du Flackern und Lichtblitze sehen möchtest – ohne laufen dieselben Szenen ruhig, mit stetigem Licht und sanftem Aufleuchten. Unter Einstellungen → Darstellung lässt sich das ändern, die Animation bei jedem Start abschalten und ihr Ton stummschalten. Die Update-Fassung läuft nach jedem Update trotzdem einmal. Mit reduzierten Animationen blendet nur das Logo ein, und solange das Launcher-Fenster im Hintergrund ist, bleibt der Ton stumm.

## 0.26.0 – 2026-10-21 – Showcase | Schaufenster
<!-- banner: accent=#ffb84d motif=/news/0.26.0/banner.png -->
<!-- shots:
/news/0.26.0/featured-carousel.png | Featured modpacks picked by the TRS team, right on the start page – install with one click | Vom TRS-Team empfohlene Modpacks direkt auf der Startseite – mit einem Klick installiert
/news/0.26.0/featured-team.png | The team picks packs by pasting a Modrinth or CurseForge link | Das Team wählt Packs per Modrinth- oder CurseForge-Link aus
-->

### English
- **Featured modpacks on the start page.** The TRS team now picks modpacks from Modrinth and CurseForge that show up as a large carousel on the start page – with a short note from the team, a badge (recommended, new, event), loaders, Minecraft version and downloads. Click a slide for the project page, or install the modpack right from the slide. The list comes live from the TRS server, so new picks appear without a launcher update. Hover or focus pauses the carousel; with reduced motion it doesn't turn on its own. On phones you can swipe.
- **Team area: manage featured modpacks.** Team members with the new right "Manage featured modpacks" paste a Modrinth or CurseForge link, write a short title and text in English and German, choose a badge, pin packs to the front, plan a time window and change the order by drag and drop (or with the arrow buttons).
- **New link: open a modpack in the launcher.** `trs-launcher://modpack/modrinth/<id>` and `trs-launcher://modpack/curseforge/<id>` open the project page in the launcher – nothing is installed until you click.

### Deutsch
- **Empfohlene Modpacks auf der Startseite.** Das TRS-Team wählt jetzt Modpacks von Modrinth und CurseForge aus, die als großes Karussell auf der Startseite erscheinen – mit einem kurzen Text vom Team, einem Abzeichen (Empfohlen, Neu, Event), Loadern, Minecraft-Version und Downloads. Ein Klick auf die Folie öffnet die Projektseite, installieren geht direkt auf der Folie. Die Liste kommt live vom TRS-Server – neue Empfehlungen erscheinen ohne Launcher-Update. Mit der Maus darüber oder mit Fokus hält das Karussell an; bei reduzierter Bewegung blättert es nicht von selbst. Am Handy kannst du wischen.
- **Team-Bereich: empfohlene Modpacks verwalten.** Team-Mitglieder mit dem neuen Recht „Empfohlene Modpacks verwalten“ fügen einen Modrinth- oder CurseForge-Link ein, schreiben einen kurzen Titel und Text auf Englisch und Deutsch, wählen ein Abzeichen, heften Packs vorn an, planen einen Zeitraum und ändern die Reihenfolge per Ziehen (oder mit den Pfeilknöpfen).
- **Neuer Link: Modpack im Launcher öffnen.** `trs-launcher://modpack/modrinth/<ID>` und `trs-launcher://modpack/curseforge/<ID>` öffnen die Projektseite im Launcher – installiert wird erst nach einem Klick.

## 0.25.1 – 2026-10-19 – Crystal Clear Fix | Feinschliff
<!-- banner: accent=#5ec8ff motif=/news/0.25.1/banner.png -->
<!-- shots:
/news/0.25.1/installer-update.png | The installer now safely replaces a launcher that was still running | Der Installer ersetzt jetzt auch einen noch laufenden Launcher sauber
-->

### English
- **Installer: no more "error overwriting trs-launcher.exe".** When you update or reinstall while the launcher is still running, the installer now closes it and waits until Windows has really released the file, instead of failing after half a second. If the file stays in use, you get a clear message with "Retry" instead of a cryptic error. A running Minecraft game is not closed.

### Deutsch
- **Installer: kein „Fehler beim Überschreiben der Datei trs-launcher.exe“ mehr.** Aktualisierst oder installierst du neu, während der Launcher noch läuft, schließt der Installer ihn jetzt und wartet, bis Windows die Datei wirklich freigibt – statt nach einer halben Sekunde abzubrechen. Bleibt die Datei belegt, kommt ein verständlicher Hinweis mit „Wiederholen“ statt eines kryptischen Fehlers. Ein laufendes Minecraft wird dabei nicht beendet.

## 0.25.0 – 2026-10-18 – Crystal Clear | Gestochen scharf
<!-- banner: accent=#5ec8ff motif=/news/0.25.0/banner.png -->
<!-- shots:
/news/0.25.0/cape-hd-upload.png | Own capes in "HD – like the image": smooth, large preview from behind | Eigene Umhänge in „HD – wie Bild“: glatt und mit großer Rückansicht
/news/0.25.0/cape-hd-fit-logo.png | "Fit" keeps square logos whole instead of cutting them | „Einpassen“ zeigt quadratische Logos ganz, statt sie abzuschneiden
/news/0.25.0/cape-hd-ingame.png | HD capes are drawn smoothly in game | HD-Umhänge werden im Spiel glatt gezeichnet
-->

### English
- **Own capes: new "HD – like the image" style.** Photos, logos and paintings now use the full 1024×512 per frame, are scaled smoothly in full color – no more blocky pixels or washed-out colors – and every preview (crop, editor and the 3D player) shows them smooth, exactly as they will be uploaded. The launcher picks HD or Pixel art from your image automatically; you can switch at any time, and pixel art stays crisp as before. HD capes are open to every player.
- **Own capes: "Fill" or "Fit".** Square logos and other images that don't match the cape shape no longer get cut off: "Fit" keeps the whole image and fills the rest with a color of your choice, a blurred copy of the image or transparency. Logos with a plain or transparent edge are fitted automatically.
- **Own capes: finished cape strips up to 1024×16384.** Ready-made animated capes in the upload format (up to 1024×512 per frame and 32 frames) are no longer rejected as "too large".
- **Own capes: size help for HD.** Smooth HD images take more space than pixel art. If an animated HD cape would be bigger than 20 MB, the upload step says so and suggests fewer frames or a slightly smaller resolution.
- **Bigger, smoother cape previews.** While you make a cape, the 3D preview now shows it large from behind instead of a tiny figure, and big HD capes in the launcher's cape lists and 3D previews are shown smooth instead of grainy.
- **TRS Client: smooth HD capes.** Real HD capes (photos, logos, paintings) are now drawn smoothly with mipmaps, so they no longer flicker or shimmer from a distance. Pixel-art capes stay crisp.

### Deutsch
- **Eigene Umhänge: neuer Stil „HD – wie Bild“.** Fotos, Logos und gemalte Bilder nutzen jetzt die vollen 1024×512 je Frame, werden glatt und in allen Farben skaliert – keine Pixelblöcke und keine blassen Farben mehr – und jede Vorschau (Ausschnitt, Editor und die 3D-Figur) zeigt sie glatt, genau so, wie sie hochgeladen werden. Der Launcher wählt HD oder Pixel-Art automatisch passend zum Bild; du kannst jederzeit umschalten, Pixel-Art bleibt scharf wie bisher. HD-Umhänge kann jeder Spieler hochladen.
- **Eigene Umhänge: „Füllen“ oder „Einpassen“.** Quadratische Logos und andere Bilder, die nicht zur Umhangform passen, werden nicht mehr abgeschnitten: „Einpassen“ zeigt das ganze Bild und füllt den Rest mit einer Farbe deiner Wahl, einer unscharfen Kopie des Bildes oder durchsichtig. Logos mit einfarbigem oder durchsichtigem Rand werden automatisch eingepasst.
- **Eigene Umhänge: fertige Umhang-Streifen bis 1024×16384.** Fertige animierte Umhänge im Upload-Format (bis 1024×512 je Frame und 32 Frames) werden nicht mehr als „zu groß“ abgelehnt.
- **Eigene Umhänge: Hilfe bei der Dateigröße.** Glatte HD-Bilder brauchen mehr Platz als Pixel-Art. Wäre ein animierter HD-Umhang größer als 20 MB, sagt dir der Schritt „Hochladen“ das und schlägt weniger Frames oder eine etwas kleinere Auflösung vor.
- **Größere, glatte Umhang-Vorschauen.** Beim Erstellen zeigt die 3D-Vorschau den Umhang jetzt groß von hinten statt einer winzigen Figur, und große HD-Umhänge sehen in den Umhang-Listen und 3D-Vorschauen des Launchers glatt statt körnig aus.
- **TRS Client: glatte HD-Umhänge.** Echte HD-Umhänge (Fotos, Logos, gemalte Bilder) werden jetzt weich mit Mipmaps gezeichnet und flimmern aus der Entfernung nicht mehr. Pixel-Art-Umhänge bleiben scharf.

## 0.24.0 – 2026-10-18 – Haunted Halls | Spukende Hallen
<!-- banner: accent=#8b5cf6 motif=/news/0.24.0/banner.png -->
<!-- shots:
/news/0.24.0/launcher-halloween-library.png | The moon night now haunts every page – here the library | Die Mondnacht spukt jetzt durch jede Seite – hier die Bibliothek
-->

### English
- **TRS Client: adjustable brightness (Gamma).** The old Fullbright becomes a brightness slider you can push from 100 % all the way up to fullbright. It applies instantly, works with shaders, and has two optional keybinds (toggle on/off, and cycle off → your level → fullbright).
- **TRS Client: auto-refill.** When the stack in your hand runs out, the same item is pulled from the rest of your inventory into the slot automatically – blocks, food, potions, arrows and anything else stackable. Tools are only replaced once they break. It pauses itself on Fair-Play/PvP servers and has its own keybind. Off by default (TRS menu).
- **TRS Client: ghost trail off by default.** The ghost trail when sprinting no longer appears right in front of your face – it is now off by default, and if you switch it on (TRS menu → Halloween) it appears behind you.
- **TRS Client: wardrobe pumpkin visible.** The hidden pumpkin in the wardrobe was completely covered by the player's legs; it now peeks out next to the right foot.
- **Website: home page pumpkin visible.** The pumpkin on the home page was hidden under the tile row; it now sits just above it.
- **Launcher: "Show more" in the news.** The news feed no longer stops after a handful of posts – it shows a batch and a "Show more" button loads the next, so you can reach older news too.
- **Clips: no more "REC" in your recordings.** While you record, the red REC timer now sits in a small launcher overlay above the game instead of inside it – you still see it, but it never shows up in the saved clip.
- **TRS Client: sharper clip preview.** The in-game clip preview no longer looks like a tiny thumbnail blown up – it now plays close to the clip's own resolution, so it stays crisp on the preview screen.
- **TRS Client: spooky screenshots.** During Halloween the screenshot editor has a new "Grain" button next to Pixelate: it lays the Halloween film grain, a dark vignette and a moon-night tint over your screenshot. It is off until you click it, can be undone like any other edit and ends up in the saved copy and in everything you share.
- **Launcher: Halloween on every page.** During the event the moon night with the blood moon is now the background of every page – library, instances, Discover, skins, social and Big Picture – not just the home page, and instances without their own banner get a moon-night picture. With "Animated background" off you get a still image of the moon night instead of the plain background; "Animations off" and "Flicker & grain" work as before.

### Deutsch
- **TRS Client: einstellbare Helligkeit (Gamma).** Aus dem alten Fullbright wird ein Helligkeitsregler von 100 % bis hin zu Fullbright. Wirkt sofort, funktioniert mit Shadern und hat zwei optionale Tastenkürzel (ein/aus und durchschalten: aus → dein Wert → Fullbright).
- **TRS Client: Automatisch nachlegen.** Geht der Stapel in deiner Hand leer, wird derselbe Gegenstand automatisch aus dem restlichen Inventar nachgelegt – Blöcke, Essen, Tränke, Pfeile und alles andere Stapelbare. Werkzeuge erst, wenn sie kaputtgehen. Auf Fair-Play-/PvP-Servern pausiert es von selbst und hat ein eigenes Tastenkürzel. Ab Werk aus (TRS-Menü).
- **TRS Client: Geister-Spur ab Werk aus.** Die Geister-Spur beim Sprinten erscheint nicht mehr direkt vor deinem Gesicht – sie ist jetzt ab Werk aus, und wer sie einschaltet (TRS-Menü → Halloween), sieht sie hinter sich.
- **TRS Client: Kürbis in der Garderobe sichtbar.** Der versteckte Kürbis in der Garderobe lag komplett hinter den Beinen der Figur; jetzt schaut er neben dem rechten Fuß hervor.
- **Website: Kürbis auf der Startseite sichtbar.** Der Kürbis auf der Startseite lag unter der Kachelreihe; jetzt sitzt er knapp darüber.
- **Launcher: „Mehr ansehen" in den Neuigkeiten.** Der Neuigkeiten-Feed hört nicht mehr nach ein paar Beiträgen auf – er zeigt einen Block, und „Mehr ansehen" lädt den nächsten, sodass du auch an ältere Meldungen kommst.
- **Clips: kein „REC" mehr in deinen Aufnahmen.** Während du aufnimmst, steht der rote REC-Zähler jetzt in einem kleinen Launcher-Overlay über dem Spiel statt im Spiel selbst – du siehst ihn weiter, aber im gespeicherten Clip taucht er nie auf.
- **TRS Client: schärfere Clip-Vorschau.** Die Clip-Vorschau im Spiel sieht nicht mehr aus wie ein hochskaliertes Miniaturbild – sie läuft jetzt nahe an der eigentlichen Auflösung des Clips und bleibt dadurch im Vorschaufenster gestochen scharf.
- **TRS Client: gruselige Screenshots.** Zu Halloween hat der Screenshot-Editor neben „Verpixeln“ einen neuen Knopf „Rauschen“: Er legt das Halloween-Filmkorn, eine dunkle Vignette und einen Mondnacht-Farbton über dein Bild. Er ist aus, bis du ihn anklickst, lässt sich wie jede Änderung rückgängig machen und landet in der gespeicherten Kopie und in allem, was du teilst.
- **Launcher: Halloween auf jeder Seite.** Während des Events ist die Mondnacht mit dem Blutmond jetzt der Hintergrund jeder Seite – Bibliothek, Instanzen, Entdecken, Skins, Sozial und Big Picture – nicht nur der Startseite, und Instanzen ohne eigenes Titelbild bekommen ein Mondnacht-Bild. Mit „Animierter Hintergrund“ aus gibt es ein Standbild der Mondnacht statt des schlichten Hintergrunds; „Animationen aus“ und „Flackern & Rauschen“ wirken wie bisher.

## 0.23.0 – 2026-10-17 – Blood Moon | Blutmond
<!-- banner: accent=#c9424a motif=/news/0.23.0/banner.png -->
<!-- shots:
/news/0.23.0/launcher-trick-or-treat.png | Trick or treat and the pumpkin hunt in the launcher's moon night | Süßes oder Saures und die Kürbis-Suche in der Mondnacht des Launchers
/news/0.23.0/website-halloween.png | The website during Halloween – night of the long shadows | Die Website zu Halloween – Nacht der langen Schatten
/news/0.23.0/client-title.png | The TRS Client title screen under the blood moon | Der Titelbildschirm des TRS Client unter dem Blutmond
/news/0.23.0/client-halloween-page.png | Pumpkin hunt, riddle and trick or treat in the TRS menu | Kürbis-Suche, Rätsel und Süßes oder Saures im TRS-Menü
/news/0.23.0/wings-ingame.png | Halloween wings in game | Halloween-Flügel im Spiel
/news/0.23.0/jumpscare-behind-you.png | Don't turn around … | Dreh dich nicht um …
-->

### English
- **Halloween 2026 – Night of Long Shadows.** When the TRS team switches on the Halloween event, the launcher wears a brand-new look: a pixel moon night with a blood moon, lightning, fog, graves, glowing pumpkins and things that watch you from the dark. Progress bars become a little moon that waxes from new moon to full moon.
- **Flicker & grain.** The film grain and the lightning flicker are on by default; the first time you see them a hint shows how to switch them off (Settings → Appearance → Flicker & grain). With animations off everything stands still.
- **Pumpkin hunt.** 13 pumpkins hide in the launcher, on the website and in the TRS Client. A riddle points you to the next one. Find all 13 for the "Pumpkin Hunter 2026" badge, the Pumpkin Hunter cape and a pair of Halloween wings of your choice (bat, demon, raven or ghost – one pair per player).
- **Trick or treat.** Knock once a day: a sweet gives you a Moon Night cape (random colour, only one per player), a Halloween emote or a chat sticker – and sometimes it's just sour.
- **Halloween 2026 badge.** Everyone who logs in during the event gets the badge – it can never be earned again afterwards. Badges show next to names in friends and chat.
- **Chat stickers.** Send the stickers you won in chats.
- **Wings.** Wings are a new cosmetic on your back. The Skins page previews them standing, in the air and sneaking.
- **TRS Client: Halloween in game.** The title screen, TRS menus and loading screens get the moon night look, the TRS menu gets a Halloween page (pumpkin hunt, trick or treat, switches) and 4 pumpkins hide in the client.
- **TRS Client: jumpscares and spooky effects.** Jumpscares are on by default during Halloween (you get a heads-up once; switch them off in TRS menu → Halloween) – never in a fight, never at low health, at most one every 20 minutes. Bats burst out of your hits and a ghost trail follows you when sprinting; other players' effects only if you switch them on.
- **TRS Client: wings, badges, stickers, emotes.** Wings show on your back in game (they flap in the air, fold when sneaking and hide with an elytra), Halloween badges appear on name tags and in the tab list, stickers in the in-game chat, plus 4 new Halloween emotes and the bat companion.

### Deutsch
- **Halloween 2026 – Nacht der langen Schatten.** Schaltet das TRS-Team das Halloween-Event ein, trägt der Launcher einen ganz neuen Look: eine Pixel-Mondnacht mit Blutmond, Blitzen, Nebel, Gräbern, leuchtenden Kürbissen und Dingen, die dich aus dem Dunkeln beobachten. Fortschrittsbalken werden zu einem kleinen Mond, der von Neumond zu Vollmond wächst.
- **Flackern & Rauschen.** Filmkorn und Blitz-Flackern sind ab Werk an; beim ersten Mal zeigt ein Hinweis, wie du sie ausschaltest (Einstellungen → Darstellung → Flackern & Rauschen). Mit ausgeschalteten Animationen steht alles still.
- **Kürbis-Suche.** 13 Kürbisse verstecken sich im Launcher, auf der Website und im TRS Client. Ein Rätsel verrät jeweils das nächste Versteck. Wer alle 13 findet, bekommt das Abzeichen „Kürbisjäger 2026“, den Kürbisjäger-Umhang und ein Paar Halloween-Flügel nach Wahl (Fledermaus, Dämon, Rabe oder Geist – jeder nur ein Paar).
- **Süßes oder Saures.** Einmal am Tag anklopfen: Süßes bringt einen Mondnacht-Umhang (zufällige Farbe, jeder nur einen), ein Halloween-Emote oder einen Chat-Sticker – und manchmal gibt es nur Saures.
- **Abzeichen „Halloween 2026“.** Alle, die sich während des Events anmelden, bekommen es – danach nie wieder. Abzeichen stehen bei Freunden und im Chat neben dem Namen.
- **Chat-Sticker.** Gewonnene Sticker kannst du in Chats verschicken.
- **Flügel.** Flügel sind eine neue Kosmetik für den Rücken. Die Skins-Seite zeigt sie im Stand, in der Luft und beim Schleichen.
- **TRS Client: Halloween im Spiel.** Titelbildschirm, TRS-Menüs und Ladebildschirme tragen den Mondnacht-Look, das TRS-Menü bekommt eine Halloween-Seite (Kürbis-Suche, Süßes oder Saures, Schalter) und 4 Kürbisse verstecken sich im Client.
- **TRS Client: Jumpscares und Spuk-Effekte.** Jumpscares sind zu Halloween ab Werk an (einmaliger Hinweis; ausschalten unter TRS-Menü → Halloween) – nie im Kampf, nie bei wenig Herzen, höchstens einer alle 20 Minuten. Aus deinen Treffern flattern Fledermäuse, beim Sprinten folgt dir eine Geister-Spur; Effekte anderer Spieler nur, wenn du sie einschaltest.
- **TRS Client: Flügel, Abzeichen, Sticker, Emotes.** Flügel sitzen im Spiel auf deinem Rücken (in der Luft schlagen sie, beim Schleichen liegen sie an, mit Elytra sind sie weg), Halloween-Abzeichen erscheinen am Namensschild und in der Tab-Liste, Sticker im Spiel-Chat, dazu 4 neue Halloween-Emotes und der Fledermaus-Begleiter.

## 0.22.0 – 2026-10-16 – Spotlight | Rampenlicht
<!-- banner: accent=#a66bff motif=/news/0.22.0/banner.png -->
<!-- shots:
/news/0.22.0/creator-fan.png | Redeem a creator code and see your fan symbol as it looks in game | Creator-Code einlösen und dein Fan-Symbol so sehen wie im Spiel
/news/0.22.0/creator-dashboard.png | The creator dashboard with statistics, codes, fan symbol and helpers | Das Creator-Dashboard mit Statistik, Codes, Fan-Symbol und Helfern
/news/0.22.0/fan-symbol-ingame.png | Creator star and fan symbol in front of the name in game | Creator-Stern und Fan-Symbol vor dem Namen im Spiel
/news/0.22.0/freecam.png | Freecam with Fair Play: through glass, but not through solid blocks | Freecam mit Fair Play: durch Glas, aber nicht durch massive Blöcke
-->

### English
- **Creator programme.** The new **Creator** page lets you redeem a creator code to support your favourite YouTuber or streamer. You support one creator at a time (switching to another one is possible every 30 days), can stop supporting at any time, and keep every fan cape you unlocked. Creators you don't want to see can be blocked by their code.
- **Fan symbols.** Fans wear the symbol of their creator in front of their name – above the head and in the tab list, animated for Partner and Top creators. The Creator page shows exactly how it looks in game, and you can hide other players' fan symbols.
- **Creator dashboard.** Creators get statistics with a 30-day chart, their own codes (extra codes with a limit and expiry date), a fan symbol upload that turns PNG, GIF or APNG into the right format with a live preview, fan cape submission straight from the cape workshop, preview builds for Partner and Top, and the tiers Starter, Partner and Top with an upgrade request.
- **Helpers.** Creators can invite TRS friends as helpers and choose what they may do (see statistics, manage codes, upload symbol/cape, list packs). Helpers accept the invitation on the Creator page and switch to "Dashboard of …" there.
- **Creator modpacks.** Creators can list their shared modpacks; they show up under Discover → Modpacks with the creator's badge and install with one click.
- **Milestones.** At 100, 500, 1,000 and 5,000 active fans a creator reaches Bronze, Silver, Gold and Diamond – all fans then get a coloured frame around the fan symbol and a notification.
- **TRS Client: fan symbols and creator stars in game.** Fans show their creator's symbol in front of their name above the head and in the tab list, creators get a coloured star. Both can be turned off under TRS Online Features.
- **TRS Client: camera paths.** The new module "Camera Paths" lets you set keyframes at your camera and play smooth cinematic flights (curve or straight, duration per segment or in total). The HUD hides while playing, the TRS Launcher can record the flight automatically, and paths are saved per world and server.
- **TRS Client: freecam.** Camera Paths now has a freecam: fly the camera freely (WASD, space, sneak, scroll wheel for speed) while your character stays where it is, and set keyframes from there. Fair Play (on by default) stops the camera at solid blocks – it only passes see-through blocks and passages like glass, doors, leaves and water. Servers can turn the freecam off or force Fair Play.

### Deutsch
- **Creator-Programm.** Auf der neuen Seite **Creator** löst du den Creator-Code deines Lieblings-YouTubers oder -Streamers ein und unterstützt ihn damit. Du unterstützt immer einen Creator (wechseln geht alle 30 Tage), kannst jederzeit aufhören und behältst alle freigeschalteten Fan-Umhänge. Creator, die du nicht sehen willst, blockierst du per Code.
- **Fan-Symbole.** Fans tragen das Symbol ihres Creators vor ihrem Namen – über dem Kopf und in der Tab-Liste, bei Partner- und Top-Creatorn auch animiert. Die Creator-Seite zeigt genau, wie es im Spiel aussieht, und du kannst die Fan-Symbole anderer Spieler ausblenden.
- **Creator-Dashboard.** Creator bekommen eine Statistik mit 30-Tage-Verlauf, eigene Codes (Zusatz-Codes mit Grenze und Ablaufdatum), das Hochladen des Fan-Symbols aus PNG, GIF oder APNG mit Live-Vorschau, das Einreichen des Fan-Umhangs direkt aus der Umhang-Werkstatt, Vorschau-Builds für Partner und Top sowie die Stufen Starter, Partner und Top mit Aufstiegs-Antrag.
- **Helfer.** Creator laden TRS-Freunde als Helfer ein und legen fest, was sie dürfen (Statistik sehen, Codes verwalten, Symbol/Umhang hochladen, Packs listen). Helfer nehmen die Einladung auf der Creator-Seite an und wechseln dort zu „Dashboard von …“.
- **Creator-Modpacks.** Creator können ihre geteilten Modpacks listen; sie erscheinen unter Entdecken → Modpacks mit dem Creator-Abzeichen und lassen sich mit einem Klick installieren.
- **Meilensteine.** Bei 100, 500, 1.000 und 5.000 aktiven Fans erreicht ein Creator Bronze, Silber, Gold und Diamant – alle Fans bekommen dann einen farbigen Rahmen um das Fan-Symbol und eine Benachrichtigung.
- **TRS Client: Fan-Symbole und Creator-Sterne im Spiel.** Fans zeigen das Symbol ihres Creators vor ihrem Namen über dem Kopf und in der Tab-Liste, Creator bekommen einen farbigen Stern. Beides lässt sich unter TRS-Online-Funktionen abschalten.
- **TRS Client: Kamera-Fahrten.** Mit dem neuen Modul „Kamera-Fahrten“ setzt du Punkte an deiner Kamera und spielst weiche Kamerafahrten ab (Kurve oder gerade, Dauer je Abschnitt oder gesamt). Das HUD verschwindet dabei, der TRS Launcher kann die Fahrt automatisch aufnehmen, und Fahrten werden je Welt und Server gespeichert.
- **TRS Client: Freecam.** Die Kamera-Fahrten haben jetzt eine Freecam: Die Kamera fliegt frei (WASD, Leertaste, Schleichen, Mausrad für das Tempo), während deine Figur stehen bleibt, und von dort setzt du Punkte für Fahrten. Fair Play (ab Werk an) hält die Kamera an massiven Blöcken an – sie kommt nur durch Durchsichtiges und Durchgänge wie Glas, Türen, Laub und Wasser. Server können die Freecam abschalten oder Fair Play erzwingen.

## 0.21.1 – 2026-10-13 – Cape Workshop Fix | Umhang-Werkstatt – Fix
<!-- banner: accent=#ff5a3c motif=/news/0.21.0/banner.png -->
<!-- shots:
/news/0.21.1/vanilla-elytra.png | The elytra looks like the normal Minecraft elytra again | Die Elytra sieht wieder aus wie die normale Minecraft-Elytra
/news/0.21.1/halloween-icon.png | The Halloween app icon during the event | Das Halloween-App-Symbol während des Events
-->

### English
- **Elytras look like normal Minecraft elytras again.** TRS capes are no longer painted onto the elytra – with a TRS cape you now see the plain vanilla elytra (in all Minecraft versions). The cape itself still shows as usual when you don't wear an elytra.
- The cape/elytra switch in the 3D preview (Skins page and cape workshop) was removed, and the cape workshop template no longer shows an elytra area.
- Event icons: during events like Halloween the launcher wears its event icon – in the window and taskbar, on the
  desktop and Start menu shortcuts (and your own app shortcut on Linux), and on the home screen of the Android and
  iPhone apps. When the event ends, the normal icon comes back by itself. You can turn this off under
  Settings → Appearance → Event icons.
- Help links now open the new documentation at trs-launcher.wiki.

### Deutsch
- **Elytren sehen wieder wie normale Minecraft-Elytren aus.** TRS-Umhänge werden nicht mehr auf die Elytra gemalt – mit einem TRS-Umhang siehst du jetzt die ganz normale Vanilla-Elytra (in allen Minecraft-Versionen). Der Umhang selbst wird wie gewohnt angezeigt, wenn du keine Elytra trägst.
- Der Umschalter Umhang/Elytra in der 3D-Vorschau (Skins-Seite und Umhang-Werkstatt) wurde entfernt, und die Vorlage der Umhang-Werkstatt zeigt keinen Elytra-Bereich mehr.
- Event-Symbole: Bei Events wie Halloween trägt der Launcher sein Event-Symbol – im Fenster und in der Taskleiste,
  auf den Verknüpfungen auf dem Desktop und im Startmenü (unter Linux auf deiner eigenen App-Verknüpfung) und auf
  dem Startbildschirm der Android- und iPhone-App. Nach dem Event kommt das normale Symbol von selbst zurück.
  Abschalten kannst du das unter Einstellungen → Aussehen → Event-Symbole.
- Hilfe-Links öffnen jetzt die neue Dokumentation unter trs-launcher.wiki.

## 0.21.0 – 2026-10-13 – Cape Workshop | Umhang-Werkstatt
<!-- banner: accent=#ff5a3c motif=/news/0.21.0/banner.png -->
<!-- shots:
/news/0.21.0/cape-editor.png | The new cape workshop: timeline, pixel editor and template | Die neue Umhang-Werkstatt: Zeitleiste, Pixel-Editor und Vorlage
/news/0.21.0/cape-elytra-preview.png | Switch the 3D preview between cape and elytra | Die 3D-Vorschau zwischen Umhang und Elytra umschalten
/news/0.21.0/cape-upload.png | Upload in HD up to 1024×512 with 32 frames | Hochladen in HD bis 1024×512 mit 32 Frames
/news/0.21.0/ingame-elytra.png | TRS capes now look right on the elytra | TRS-Umhänge sehen jetzt auf der Elytra richtig aus
/news/0.21.0/ingame-winter-title.png | Winter Night in the TRS Client title screen | Winternacht auf dem Titelbildschirm des TRS Clients
/news/0.21.0/ingame-winter-menu.png | Ice and snow menus in the game | Menüs aus Eis und Schnee im Spiel
-->

### English
- **HD capes.** Your own capes can now be up to 1024×512 pixels per frame, with up to 32 frames and up to 20 MB – twice as sharp and twice as long as before.
- **Cape workshop with editor.** Uploading a cape now has three steps: choose the source, edit, upload. The editor has a frame timeline (drag to reorder, duplicate, delete, play, speed, and a duration per frame), a pixel editor with pencil, eraser, color picker, fill, line, moving a part, brush sizes, mirroring within a part and onion skin of the previous frame, undo/redo, and a template that shows where the outside, inside, edges and elytra are. A live 3D preview on the player shows every change right away – rotatable, animated and with a light or dark background.
- **More sources.** Besides images, GIFs, sprite sheets and TRS Studio exports you can now use animated PNGs (APNG), choose the order of several images by dragging, split sprite sheets in rows and columns, and turn a section of a video (MP4, WebM …) into frames – with your own start, end and frame rate, as a task you can cancel. Videos use the same FFmpeg as clips (downloaded once).
- **Automatic upscaling.** You edit at a small resolution, and the cape is scaled up on upload – pixel-exact, or optionally "Smooth", which rounds staircase edges right on your computer. Before uploading you see the final size, the number of frames and the file size; if the cape would be too large, the launcher suggests a smaller resolution or fewer frames.
- **Cape or elytra.** The 3D preview on the Skins page and in the cape workshop has a switch between cape and elytra – animated capes move on the elytra too. The elytra design is always made from the front of the cape (also for older capes), so every cape gets matching wings.
- On phones and tablets you can import and upload capes; the pixel editor is available in the desktop launcher.
- **Your design follows your account.** Redstone or Winter Night is now synced with your TRS account like color theme, accent and language – pick it once, and your other PCs and the TRS Client follow.
- **TRS Client: capes look right on the elytra.** Wearing an elytra with a TRS cape (team, code, Studio or older capes) no longer shows red or broken wings – the wings now always show your cape's design, sharp in HD, darker on the inside, and animated capes animate on the elytra too.
- **Winter Night in the game, too.** The TRS Client now follows the launcher's Winter Night design: the title screen shows the snowy pixel landscape with mountains, northern lights, falling snow and the cabin – the penguin in the Santa hat looks out of the window and now and then comes out to wave. Buttons become ice lanterns, the turntable under your character becomes an ice floe, and the logo wears a snow cap.
- Pause menu, server list, options and loading screens get ice and snow slabs, frosty edges and an ice bar that freezes over while loading. The TRS menu, HUD accents and notifications use the night-blue palette; light theme shows a bright winter day.
- You can switch the design inside the game: in the first-start guide and in the TRS menu (pages "Title Screen" and "Menu Style"), next to color theme and the new "Ice" accent.

### Deutsch
- **HD-Umhänge.** Eigene Umhänge dürfen jetzt bis 1024×512 Pixel je Frame groß sein, mit bis zu 32 Frames und bis zu 20 MB – doppelt so scharf und doppelt so lang wie bisher.
- **Umhang-Werkstatt mit Editor.** Das Hochladen geht jetzt in drei Schritten: Quelle wählen, bearbeiten, hochladen. Der Editor hat eine Zeitleiste für die Frames (ziehen zum Umsortieren, duplizieren, löschen, abspielen, Tempo und eine Dauer je Frame), einen Pixel-Editor mit Stift, Radierer, Pipette, Füllen, Linie, Teil verschieben, Pinselgrößen, Spiegeln innerhalb eines Teils und Zwiebelhaut des vorigen Frames, Rückgängig/Wiederholen und eine Vorlage, die zeigt, wo Außenseite, Innenseite, Kanten und Elytra liegen. Eine Live-3D-Vorschau am Spieler zeigt jede Änderung sofort – drehbar, animiert und mit hellem oder dunklem Hintergrund.
- **Mehr Quellen.** Neben Bildern, GIFs, Sprite-Sheets und TRS-Studio-Exporten gehen jetzt auch animierte PNGs (APNG), die Reihenfolge mehrerer Bilder lässt sich per Ziehen festlegen, Sprite-Sheets lassen sich in Zeilen und Spalten teilen, und ein Ausschnitt aus einem Video (MP4, WebM …) wird zu Frames – mit eigenem Anfang, Ende und eigener Bildrate, als Aufgabe, die du abbrechen kannst. Videos nutzen dasselbe FFmpeg wie Clips (einmal geladen).
- **Automatisches Hochskalieren.** Du bearbeitest in kleiner Auflösung, beim Hochladen wird der Umhang hochskaliert – pixelgenau oder wahlweise „Glätten“, das Treppenkanten direkt auf deinem Rechner abrundet. Vor dem Hochladen siehst du die fertige Größe, die Zahl der Frames und die Dateigröße; wäre der Umhang zu groß, schlägt der Launcher eine kleinere Auflösung oder weniger Frames vor.
- **Umhang oder Elytra.** Die 3D-Vorschau auf der Skins-Seite und in der Umhang-Werkstatt hat einen Umschalter zwischen Umhang und Elytra – animierte Umhänge bewegen sich auch auf den Elytren. Das Elytra-Muster entsteht immer aus der Vorderseite des Umhangs (auch bei älteren Umhängen), so bekommt jeder Umhang passende Flügel.
- Am Handy und Tablet lassen sich Umhänge importieren und hochladen; den Pixel-Editor gibt es im Desktop-Launcher.
- **Dein Design folgt deinem Konto.** Redstone oder Winternacht wird jetzt wie Farbschema, Akzent und Sprache mit deinem TRS-Konto abgeglichen – einmal wählen, deine anderen PCs und der TRS Client ziehen mit.
- **TRS Client: Umhänge sehen auf der Elytra richtig aus.** Mit einem TRS-Umhang (Team-, Code-, Studio- oder ältere Umhänge) zeigt die Elytra keine roten oder kaputten Flügel mehr – die Flügel zeigen jetzt immer das Motiv deines Umhangs, scharf in HD, innen dunkler, und animierte Umhänge bewegen sich auch auf der Elytra.
- **Winternacht jetzt auch im Spiel.** Der TRS Client übernimmt das Design Winternacht des Launchers: Der Startbildschirm zeigt die verschneite Pixel-Landschaft mit Bergen, Polarlicht, Schneefall und der Hütte – der Pinguin mit Weihnachtsmütze schaut aus dem Fenster und kommt ab und zu heraus, um zu winken. Knöpfe werden zu Eislaternen, die Drehscheibe unter deiner Figur zur Eisscholle, und auf dem Logo liegt Schnee.
- Pausenmenü, Serverliste, Optionen und Ladebildschirme bekommen Eis- und Schneeplatten, Frostkanten und einen Eisbalken, der beim Laden zufriert. TRS-Menü, HUD-Akzente und Hinweise nutzen die nachtblauen Farben; das helle Farbschema zeigt einen hellen Wintertag.
- Das Design lässt sich im Spiel umstellen: in der Einführung beim ersten Start und im TRS-Menü (Seiten „Startbildschirm“ und „Menü-Stil“), neben Farbschema und dem neuen Akzent „Eis“.

## 0.20.0 – 2026-10-13 – Penguin Lodge | Pinguin-Hütte
<!-- banner: accent=#7fd3ff motif=/news/0.20.0/banner.png -->
<!-- shots:
/news/0.20.0/winter-home.png | The new Winter Night design with northern lights and the penguin's cabin | Das neue Design Winternacht mit Polarlicht und der Pinguin-Hütte
/news/0.20.0/winter-library.png | Winter scenes for every instance | Winterbilder für jede Instanz
/news/0.20.0/design-setting.png | Pick your design in Settings → Appearance | Das Design wählst du unter Einstellungen → Aussehen
-->

### English
- **New design: Winter Night.** Settings → Appearance now has a "Design" choice with two looks: the familiar Redstone and the new Winter Night. You can switch at any time, all year round.
- **A living winter landscape.** In Winter Night the circuit behind the pages and on the start page becomes a snowy pixel landscape: a wide snowy plain, snowy mountains far away on the horizon, pine trees, a cosy log cabin with warm windows, a lantern and smoke from the chimney, the moon and stars, gentle snowfall and – every now and then – green northern lights that ripple across the sky like real ones. With the light color theme it turns into a bright winter day with sun and clouds.
- **Someone lives in the cabin.** A little penguin with a Santa hat peeks out of the window – and every minute or two he opens the door, waddles out, waves and goes back inside. On a winter day he likes to stay outside a bit longer.
- **Ice lanterns on the start page.** A row of ice lanterns leads to the play button. When you start a game they light up one after another, and while you play the northern lights shine.
- **Frost everywhere.** In Winter Night, progress bars become ice bars that freeze over, the play button becomes an ice lantern, instance pictures show little winter scenes, and loading placeholders, menus and page changes get a frosty shimmer. Everything that shows a running game glows ice blue instead of amber.
- **New accent color "Ice".** Ice blue can be picked with any design. If you choose Winter Night while the redstone color is set, the launcher switches to Ice for you – you can change it back.
- The color theme still works with both designs: dark, OLED (almost black night) and light.

### Deutsch
- **Neues Design: Winternacht.** Unter Einstellungen → Aussehen gibt es jetzt „Design“ mit zwei Looks: das gewohnte Redstone und die neue Winternacht. Umschalten geht jederzeit, das ganze Jahr über.
- **Eine lebendige Winterlandschaft.** In der Winternacht wird aus der Schaltung hinter den Seiten und auf der Startseite eine verschneite Pixel-Landschaft: eine weite Schneeebene, verschneite Berge weit hinten am Horizont, Tannen, eine gemütliche Blockhütte mit warmen Fenstern, einer Laterne und Rauch aus dem Schornstein, Mond und Sterne, leiser Schneefall und – ab und zu – grünes Polarlicht, das wie echtes über den Himmel zieht. Mit dem hellen Farbschema wird daraus ein heller Wintertag mit Sonne und Wolken.
- **In der Hütte wohnt jemand.** Ein kleiner Pinguin mit Weihnachtsmütze schaut aus dem Fenster – und alle ein, zwei Minuten öffnet er die Tür, watschelt hinaus, winkt und geht wieder hinein. An einem Wintertag bleibt er gern etwas länger draußen.
- **Eislaternen auf der Startseite.** Eine Reihe Eislaternen führt zum Spielen-Knopf. Beim Spielstart gehen sie nacheinander an, und während du spielst, leuchtet das Polarlicht.
- **Überall Frost.** In der Winternacht werden Fortschrittsbalken zu Eisbalken, die zufrieren, der Spielen-Knopf wird zur Eislaterne, Instanzbilder zeigen kleine Winterszenen, und Ladeplatzhalter, Menüs und Seitenwechsel bekommen einen frostigen Schimmer. Alles, was ein laufendes Spiel zeigt, leuchtet eisblau statt bernsteinfarben.
- **Neue Akzentfarbe „Eis“.** Eisblau lässt sich mit jedem Design wählen. Wählst du die Winternacht, während die Redstone-Farbe eingestellt ist, stellt der Launcher auf Eis um – du kannst es wieder ändern.
- Das Farbschema gilt weiter in beiden Designs: Dunkel, OLED (fast schwarze Nacht) und Hell.

## 0.19.1 – 2026-10-12 – Smooth Moves Fix | Flüssige Bewegung – Fix
<!-- banner: accent=#ff4d3a motif=/news/0.19.0/banner.png -->
<!-- shots:
/news/0.19.1/background.png | The redstone background stays clean | Der Redstone-Hintergrund bleibt sauber
-->

### English
- **Fix: red spots across the animated background.** On some PCs the redstone background behind the pages slowly filled with red spots (for example after sleep, minimizing or a graphics driver reset). The scene now clears itself every frame and redraws completely when the graphics card resets or the window comes back.

### Deutsch
- **Fix: rote Flecken im animierten Hintergrund.** Auf manchen PCs füllte sich der Redstone-Hintergrund hinter den Seiten nach und nach mit roten Flecken (z. B. nach Ruhezustand, Minimieren oder einem Neustart des Grafiktreibers). Die Szene leert sich jetzt bei jedem Bild und zeichnet sich vollständig neu, wenn die Grafikkarte zurückgesetzt wird oder das Fenster wieder erscheint.

## 0.19.0 – 2026-10-12 – Smooth Moves | Flüssige Bewegung
<!-- banner: accent=#ff4d3a motif=/news/0.19.0/banner.png -->
<!-- shots:
/news/0.19.0/library.png | Cards and lists now glide in smoothly | Karten und Listen gleiten jetzt weich herein
/news/0.19.0/animations-setting.png | The new option Reduce animations | Die neue Option Animationen reduzieren
-->

### English
- **Smoother, more polished interface.** Dialogs and settings gently zoom in and fade out (on phones they slide up from the bottom), cards and lists glide in one after another, instances and mods slide to their new place when you filter, sort or move them into groups, toasts slide in and the rest move up smoothly, and the marker in the sidebar and the tab bar glides to the new page. Cards lift slightly under the mouse and buttons give way when pressed. Everything is short and quick, and nothing keeps moving while you play.
- **New option "Reduce animations".** In Settings → Appearance → Animations you can now pick "Reduce animations" to turn all of these effects off (pages, dialogs and lists then appear instantly).

### Deutsch
- **Flüssigere, feinere Oberfläche.** Dialoge und Einstellungen zoomen sanft auf und blenden weich aus (am Handy fahren sie von unten hoch), Karten und Listen laufen nacheinander ein, Instanzen und Mods gleiten an ihren neuen Platz, wenn du filterst, sortierst oder sie in Gruppen verschiebst, Benachrichtigungen gleiten herein und die übrigen rücken weich nach, und die Markierung in der Seitenleiste und der Tab-Leiste gleitet zur neuen Seite. Karten heben sich unter der Maus leicht an, Knöpfe geben beim Drücken nach. Alles ist kurz und schnell, und während du spielst, bewegt sich nichts dauerhaft.
- **Neue Option „Animationen reduzieren“.** Unter Einstellungen → Darstellung → Animationen kannst du jetzt „Animationen reduzieren“ wählen und damit alle diese Effekte abschalten (Seiten, Dialoge und Listen erscheinen dann sofort).

## 0.18.2 – 2026-10-12 – iPhone Fix | iPhone-Fix
<!-- banner: accent=#ffb02e motif=/news/0.18.0/banner.png -->
<!-- shots:
/news/0.18.2/mobile-app.png | The TRS Launcher app now opens on iPhone and iPad | Die TRS-Launcher-App öffnet sich jetzt auf iPhone und iPad
-->

### English
- **Fix: the iPhone/iPad app closed right after opening.** The app only showed a blurry window for a moment and then disappeared. It now starts normally – update it in AltStore/SideStore.

### Deutsch
- **Fix: Die iPhone-/iPad-App schloss sich direkt nach dem Öffnen.** Die App zeigte nur kurz ein trübes Fenster und verschwand dann. Sie startet jetzt normal – aktualisiere sie in AltStore/SideStore.

## 0.18.1 – 2026-10-04 – Power Tools Fix | Profi-Werkzeuge – Fix
<!-- banner: accent=#ffb02e motif=/news/0.18.0/banner.png -->
<!-- shots:
/news/0.18.1/mobile-app.png | The TRS Launcher app for Android is now part of the release | Die TRS-Launcher-App für Android ist jetzt im Release
-->

### English
- **Fix: "The mod loader installer could not be downloaded" on NeoForge/Forge.** Some instances had a loader version saved that doesn't belong to NeoForge or Forge (for example a Fabric version). The launcher now notices this and installs the newest matching NeoForge/Forge version instead of failing.
- **Android app and iPhone version in the release.** The mobile builds of 0.18.0 didn't make it into the release; the APK for Android and the AltStore/SideStore source for iPhone and iPad are now published.
- **"Redeem code" right on the Skins page.** A big button at the top next to "Save skin" opens the code dialog for capes and cosmetics.
- **Java is picked automatically again.** After changing an instance to a Minecraft version that needs a different Java (for example 26.1 with Java 25), a Java downloaded by the launcher is switched to the right one by itself – no more "Java 21 – Java 25 is expected here".
- **Mod conflict helper.** When mods don't fit together and the launch is stopped, a helper now explains each problem in plain words ("Better Advancements 0.4.8.54 doesn't support Minecraft 26.1 (needs 1.21.x)") and offers a fix per mod: find a matching version, disable, remove or open the mod page – plus "Disable all affected and launch" and "Launch anyway" for false alarms. It also opens from the instance page and from the crash helper.
- **Mods made for older Minecraft versions on 26.1.** Since 26.1 Minecraft runs without obfuscation, so mods built for 1.21.x crash right at the start ("NoClassDefFoundError: net/minecraft/class_…") even if they claim to support newer versions. The launcher now spots them before the launch, swaps in a version made for your Minecraft version when there is one, and otherwise shows them in the conflict helper. The crash helper explains this crash too.
- **Fix: typing in the TRS menu on Minecraft 26.3.** The search box, chat and friends, names, codes and every other text field of the TRS Client take your keyboard input again on 26.3 (Fabric, NeoForge and Forge) – including typing straight into the search without clicking it first.
- **Play on your own server with friends.** Under "My servers" a running server now shows how to connect: the address on this PC, in your home network and over the internet (with a copy button, a hint about port forwarding and a reachability check).
- **Invite friends to your server – no port forwarding needed.** "Invite friends" sends your TRS friends an invite with "Join": their launcher starts the matching version (or creates it) and connects through the TRS relay, even straight into a game that's already running. Only invited friends get in.
- **Public link for your server (e4mc).** Optionally get an address like name.e4mc.link that anyone can join, even without TRS – after a warning, and only while it's switched on.
- **No more duplicate resource packs with synced settings.** When resource packs are synced between instances, a pack that's already there (even under another file name) isn't copied again, and an updated pack replaces its older version instead of piling up next to it – the old one goes to the sync backup. Packs for different Minecraft versions (for example 1.8.9 and 1.21) stay side by side. Enabled packs stay enabled and point to the new file. Sharing the resource pack folder also keeps identical packs only once.
- **A full-page editor for presets.** Creating or editing a preset now opens a big page like Discover: mods, resource packs and shaders from Modrinth and CurseForge with search, sorting, filters and endless scrolling. The contents of the preset stay on the right, grouped by type, with name, "Always automatic" and Save always in view.
- **Icon and colour for presets.** Every custom preset can get its own icon and accent colour – shown in the preset list and wherever you pick presets.
- **Dependencies and conflicts at a glance.** For every project the editor shows which required mods come along automatically (for example Fabric API or Cloth Config) and warns about known conflicts, like Sodium together with Embeddium or OptiFine, or two minimaps.
- **Preset templates.** A new preset can start from "Performance", "Shaders", "PvP" or "Redstone" and be changed afterwards.
- **"Take over from modpack".** Copy mods, resource packs and shaders into a preset from one of your instances, from any Modrinth or CurseForge modpack (only its list is read, nothing is installed) or from a TRS pack code – as a checklist with search, filters like "Performance only" or "Client-side only", and "All"/"None". Projects that are already in the preset are skipped.
- **CurseForge projects in presets.** Presets can now hold CurseForge projects too; they're installed with the matching file and their required dependencies.
- **Icon editor for instances and modpacks.** Design your own icon when creating an instance, in the instance settings or when sharing a pack: draw it in a pixel editor (16×16 or 32×32 with pencil, eraser, fill, colour picker, lines, rectangles, mirror mode, undo/redo and a TRS + Minecraft palette), pick any item or block from your own Minecraft game, choose one of 40 TRS icons or crop your own picture (optionally pixelated). Add a background with colour, gradient, redstone wire, deepslate or dots, a border and a glow, and see it live at every size. The icon stays editable later, and a shared pack takes its icon along.

### Deutsch
- **Behoben: „Der Modloader-Installer konnte nicht heruntergeladen werden“ bei NeoForge/Forge.** Bei manchen Instanzen war eine Loader-Version gespeichert, die gar nicht zu NeoForge oder Forge gehört (zum Beispiel eine Fabric-Version). Der Launcher erkennt das jetzt und installiert die neueste passende NeoForge-/Forge-Version, statt abzubrechen.
- **Android-App und iPhone-Version im Release.** Die Handy-Builds von 0.18.0 haben es nicht ins Release geschafft; die APK für Android und die AltStore/SideStore-Quelle für iPhone und iPad sind jetzt veröffentlicht.
- **„Code einlösen“ direkt auf der Skins-Seite.** Ein großer Knopf oben neben „Skin speichern“ öffnet den Code-Dialog für Umhänge und Kosmetik.
- **Java wird wieder automatisch gewählt.** Nach dem Wechsel einer Instanz auf eine Minecraft-Version, die eine andere Java braucht (zum Beispiel 26.1 mit Java 25), wird eine vom Launcher geladene Java von selbst auf die passende umgestellt – kein „Java 21 – Java 25 erwartet“ mehr.
- **Mod-Konflikt-Helfer.** Passen Mods nicht zusammen und der Start wird angehalten, erklärt jetzt ein Helfer jedes Problem in einfachen Worten („Better Advancements 0.4.8.54 unterstützt Minecraft 26.1 nicht (braucht 1.21.x)“) und bietet je Mod eine Lösung an: passende Version suchen, deaktivieren, entfernen oder die Mod-Seite öffnen – dazu „Alle betroffenen deaktivieren und starten“ und „Trotzdem starten“ für Fehlalarme. Er lässt sich auch auf der Instanzseite und im Absturz-Helfer öffnen.
- **Mods für ältere Minecraft-Versionen unter 26.1.** Seit 26.1 läuft Minecraft ohne Verschleierung – Mods, die für 1.21.x gebaut wurden, stürzen gleich beim Start ab („NoClassDefFoundError: net/minecraft/class_…“), auch wenn sie neuere Versionen erlauben. Der Launcher erkennt sie jetzt vor dem Start, tauscht sie gegen eine Version für deine Minecraft-Version, wenn es eine gibt, und zeigt sie sonst im Konflikt-Helfer. Auch der Absturz-Helfer erklärt diesen Absturz.
- **Behoben: Tippen im TRS-Menü unter Minecraft 26.3.** Suchfeld, Chat und Freunde, Namen, Codes und alle anderen Textfelder des TRS Clients nehmen unter 26.3 (Fabric, NeoForge und Forge) wieder Tastatureingaben an – auch das direkte Lostippen in die Suche ohne vorheriges Anklicken.
- **Mit Freunden auf deinem eigenen Server spielen.** Unter „Meine Server“ zeigt ein laufender Server jetzt, wie man sich verbindet: die Adresse auf diesem PC, im Heimnetz und über das Internet (mit Kopieren-Knopf, Hinweis zur Portfreigabe und Erreichbarkeits-Test).
- **Freunde auf deinen Server einladen – ohne Portfreigabe.** „Freunde einladen“ schickt deinen TRS-Freunden eine Einladung mit „Beitreten“: Ihr Launcher startet die passende Version (oder legt sie an) und verbindet über das TRS Relay, auch direkt in ein schon laufendes Spiel. Nur eingeladene Freunde kommen rein.
- **Öffentlicher Link für deinen Server (e4mc).** Auf Wunsch gibt es eine Adresse wie name.e4mc.link, mit der jeder beitreten kann, auch ohne TRS – nach einer Warnung und nur, solange er eingeschaltet ist.
- **Keine doppelten Resource Packs mehr beim Synchronisieren.** Werden Resource Packs zwischen Instanzen synchronisiert, wird ein Pack, das schon da ist (auch unter anderem Dateinamen), nicht noch einmal kopiert, und ein aktualisiertes Pack ersetzt seine ältere Version, statt sich daneben zu stapeln – die alte landet in der Sync-Sicherung. Packs für verschiedene Minecraft-Versionen (zum Beispiel 1.8.9 und 1.21) bleiben nebeneinander. Eingeschaltete Packs bleiben eingeschaltet und zeigen auf die neue Datei. Auch beim Teilen des Resource-Pack-Ordners werden gleiche Packs nur einmal behalten.
- **Preset-Editor als ganze Seite.** Ein Preset anlegen oder bearbeiten öffnet jetzt eine große Seite wie „Entdecken“: Mods, Resource Packs und Shader von Modrinth und CurseForge mit Suche, Sortierung, Filtern und endlosem Nachladen. Der Inhalt des Presets steht rechts, nach Art sortiert – Name, „Immer automatisch“ und Speichern bleiben immer sichtbar.
- **Symbol und Farbe für Presets.** Jedes eigene Preset kann ein eigenes Symbol und eine Akzentfarbe bekommen – zu sehen in der Preset-Liste und überall, wo man Presets auswählt.
- **Abhängigkeiten und Konflikte auf einen Blick.** Zu jedem Projekt zeigt der Editor, welche Pflicht-Mods automatisch mitkommen (zum Beispiel Fabric API oder Cloth Config), und warnt vor bekannten Konflikten wie Sodium zusammen mit Embeddium oder OptiFine oder zwei Minimaps.
- **Preset-Vorlagen.** Ein neues Preset kann mit „Performance“, „Shader“, „PvP“ oder „Redstone“ starten und danach frei geändert werden.
- **„Aus Modpack übernehmen“.** Mods, Resource Packs und Shader aus einer eigenen Instanz, aus jedem Modrinth- oder CurseForge-Modpack (nur die Liste wird gelesen, nichts installiert) oder aus einem TRS-Pack-Code ins Preset übernehmen – als Checkliste mit Suche, Filtern wie „Nur Performance“ oder „Nur Client-seitig“ und „Alle“/„Keine“. Was schon im Preset ist, wird übersprungen.
- **CurseForge-Projekte in Presets.** Presets können jetzt auch CurseForge-Projekte enthalten; installiert wird jeweils die passende Datei samt Pflicht-Abhängigkeiten.
- **Symbol-Editor für Instanzen und Modpacks.** Beim Anlegen einer Instanz, in den Instanz-Einstellungen und beim Teilen eines Packs gestaltest du jetzt dein eigenes Symbol: im Pixel-Editor zeichnen (16×16 oder 32×32 mit Stift, Radierer, Füllen, Pipette, Linien, Rechtecken, Spiegeln, Rückgängig/Wiederholen und TRS- und Minecraft-Farben), ein beliebiges Item oder einen Block aus deinem eigenen Minecraft nehmen, eins von 40 TRS-Symbolen wählen oder ein eigenes Bild zuschneiden (auf Wunsch verpixelt). Dazu ein Hintergrund mit Farbe, Verlauf, Redstone-Leitung, Deepslate oder Punkten, Rahmen und Leuchten – live in allen Größen. Das Symbol bleibt später bearbeitbar, und ein geteiltes Pack nimmt sein Symbol mit.

## 0.18.0 – 2026-10-04 – Power Tools | Profi-Werkzeuge
<!-- banner: accent=#ff3b30 motif=/news/0.18.0/banner.png -->
<!-- shots:
/news/0.18.0/mobile-app.png | TRS Launcher on Android – with Minecraft Java and touch controls | TRS Launcher auf Android – mit Minecraft Java und Touch-Steuerung
/news/0.18.0/config-editor.png | Edit configs with switches and lists instead of text | Configs mit Schaltern und Listen statt Text bearbeiten
/news/0.18.0/mod-groups.png | Mod groups and "Find the culprit mod" | Mod-Gruppen und „Schuldige Mod finden“
/news/0.18.0/server-export.png | Export an instance as a ready-to-run server | Eine Instanz als fertigen Server exportieren
/news/0.18.0/shared-folders.png | Share shaders, resource packs, worlds and more between instances | Shader, Resource Packs, Welten und mehr zwischen Instanzen teilen
/news/0.18.0/big-picture.png | Big Picture mode for controllers, TVs and the Steam Deck | Big-Picture-Modus für Controller, Fernseher und Steam Deck
-->

### English
- **TRS Launcher for Android (preview).** Play Minecraft Java on your phone: sign in, create or share instances, install modpacks and mods – then start the game with touch controls. Works best with Minecraft 1.21 and newer (including 26.x), older versions are still experimental; the TRS Client and Mod Menu come along automatically. Download the APK from the release; it updates itself.
- **iPhone and iPad (preview, sideload).** The app is available through an AltStore/SideStore source. Playing needs JIT (SideStore + StikDebug, AltServer or TrollStore); the app explains how.
- **Touch controls with an editor.** Layouts for PvP, building and redstone, buttons you can move and resize, a visible mouse pointer in menus (swipe to move, tap to click, hold for right-click and dragging items) and controller/mouse support. The TRS Client gets a touch mode with bigger menus and the on-screen keyboard.
- **Push notifications and PC remote control.** The app tells you about messages, friend requests and invites (through ntfy or a 15-minute check), and you can pair it with your PC to start or stop games there and install modpacks.
- **Edit configs right in the launcher.** In an instance's Files tab, .toml, .json, .properties, .cfg and .yml open in an editor: switches, numbers and lists in "Simple", the whole file with syntax colours in "Advanced". Comments and formatting stay as they are.
- **Mod groups and "Find the culprit mod".** Sort content into coloured groups and switch whole groups on or off; see what came with a modpack and what you added. If something is broken, the launcher finds the responsible mod by switching half of them off each round – and restores everything afterwards.
- **Shared folders.** Shaders, resource packs, screenshots, worlds and schematics can be shared between instances – merged without losing files.
- **Move your data – or run portable.** Move the launcher's data folder or a single instance to another drive, or use the new portable ZIP that keeps everything next to the program.
- **Export as a server.** Turn an instance into a server: only server mods, configs, an optional world, start scripts and RAM settings – as a ZIP or set up and started right here with a live console under "My servers".
- **Fast Connect in the TRS Client.** No more waiting at "Connecting to the server": IPv4 and IPv6 are tried side by side, addresses are looked up in advance (also by the launcher), server switches in networks are much faster and server resource packs are preloaded.
- **Big Picture mode.** A full-screen view with large tiles for controllers, TVs and the Steam Deck (F11).
- **Start an instance twice** – for example with a second account.
- **Cape search and details.** Search, filter and sort TRS capes and see where a cape comes from.
- **Minecraft Bedrock.** If Bedrock is installed from the Microsoft Store, it gets its own card in the library.
- **Mods that need each other in other versions are fixed before the start.** If a mod needs a different version of another one (like Iris 1.11 needing Sodium 0.9 while 0.8 is installed), the launcher swaps in a fitting version before starting instead of showing Fabric's "Incompatible mods" error – also after changing an instance's Minecraft version, where the optimisation mods now follow the new version too. If nothing fits, you get a clear note which mods clash, and the crash helper offers the right swap.

### Deutsch
- **TRS Launcher für Android (Vorschau).** Minecraft Java auf dem Handy: anmelden, Instanzen anlegen oder teilen, Modpacks und Mods installieren – und mit Touch-Steuerung spielen. Am besten läuft Minecraft 1.21 und neuer (auch 26.x), ältere Versionen sind noch experimentell; TRS Client und Mod Menu kommen automatisch mit. Die APK gibt es im Release, danach aktualisiert sie sich selbst.
- **iPhone und iPad (Vorschau, Sideload).** Die App gibt es über eine AltStore/SideStore-Quelle. Zum Spielen braucht es JIT (SideStore + StikDebug, AltServer oder TrollStore); die App erklärt, wie.
- **Touch-Steuerung mit Editor.** Layouts für PvP, Bauen und Redstone, Knöpfe frei verschieben und skalieren, ein sichtbarer Mauszeiger in Menüs (wischen zum Bewegen, tippen zum Klicken, halten für Rechtsklick und Items ziehen) sowie Controller und Maus. Der TRS Client bekommt einen Touch-Modus mit größeren Menüs und Bildschirmtastatur.
- **Push-Benachrichtigungen und PC-Fernbedienung.** Die App meldet Nachrichten, Freundschaftsanfragen und Einladungen (über ntfy oder alle 15 Minuten), und gekoppelt mit deinem PC startest oder beendest du dort Spiele und installierst Modpacks.
- **Configs direkt im Launcher bearbeiten.** Im Dateien-Tab einer Instanz öffnen sich .toml, .json, .properties, .cfg und .yml in einem Editor: Schalter, Zahlen und Listen unter „Einfach“, die ganze Datei mit Farben unter „Erweitert“. Kommentare und Formatierung bleiben erhalten.
- **Mod-Gruppen und „Schuldige Mod finden“.** Inhalte in farbige Gruppen sortieren und ganze Gruppen ein- oder ausschalten; sehen, was vom Modpack kam und was du selbst hinzugefügt hast. Ist etwas kaputt, findet der Launcher die schuldige Mod, indem er jede Runde die Hälfte abschaltet – und stellt danach alles wieder her.
- **Gemeinsame Ordner.** Shader, Resource Packs, Screenshots, Welten und Schematics lassen sich zwischen Instanzen teilen – zusammengeführt, ohne Dateien zu verlieren.
- **Daten umziehen – oder portabel.** Den Datenordner des Launchers oder einzelne Instanzen auf ein anderes Laufwerk verschieben, oder die neue portable ZIP nutzen, die alles neben dem Programm speichert.
- **Als Server exportieren.** Aus einer Instanz wird ein Server: nur Server-Mods, Configs, auf Wunsch eine Welt, Start-Skripte und RAM-Einstellung – als ZIP oder direkt hier eingerichtet und gestartet, mit Live-Konsole unter „Meine Server“.
- **Schnell verbinden im TRS Client.** Kein Warten mehr bei „Verbinde mit Server“: IPv4 und IPv6 werden parallel probiert, Adressen vorab aufgelöst (auch vom Launcher), Serverwechsel in Netzwerken sind viel schneller und Server-Ressourcenpakete werden vorgeladen.
- **Big-Picture-Modus.** Eine Vollbild-Ansicht mit großen Kacheln für Controller, Fernseher und das Steam Deck (F11).
- **Eine Instanz zweimal starten** – zum Beispiel mit einem zweiten Konto.
- **Umhang-Suche und Details.** TRS-Umhänge suchen, filtern, sortieren und sehen, woher ein Umhang kommt.
- **Minecraft Bedrock.** Ist Bedrock aus dem Microsoft Store installiert, bekommt es eine eigene Karte in der Bibliothek.
- **Mods, die einander in anderen Versionen brauchen, werden vor dem Start repariert.** Braucht eine Mod eine andere Version einer anderen (etwa Iris 1.11 Sodium 0.9, installiert ist 0.8), tauscht der Launcher vor dem Start eine passende Version ein, statt Fabrics Fehler „Incompatible mods“ zu zeigen – auch nach einem Versionswechsel der Instanz, bei dem jetzt auch die Optimierungs-Mods mitziehen. Passt nichts, sagt ein klarer Hinweis, welche Mods sich beißen, und der Absturz-Helfer bietet den richtigen Tausch an.

## 0.17.1 – 2026-10-03 – Light Luggage Fix | Leichtes Gepäck – Fix
<!-- banner: accent=#ffb02e motif=/news/0.17.0/banner.png -->
<!-- shots:
/news/0.17.1/memory-warning.png | A hint when an instance gets a lot of RAM | Ein Hinweis, wenn eine Instanz sehr viel RAM bekommt
-->

### English
- **Fix: "Could not create the Java Virtual Machine" with a lot of RAM.** With a large memory setting (e.g. 24 GB)
  the launcher reserved all of it at the start – if other programs were using memory, Java refused to start. Now at
  most 8 GB are reserved up front, and only what is actually free; the rest grows as the game needs it. Before every
  start the launcher checks that Java gets the memory, and if Java still fails it restarts the game right away with
  safe values instead of showing the Java error window.
- **Hint for very large RAM settings.** From 16 GB the RAM slider explains that Minecraft rarely needs more than
  8–12 GB.

### Deutsch
- **Behoben: „Could not create the Java Virtual Machine“ bei viel RAM.** Mit einer großen Speichereinstellung (z. B.
  24 GB) hat der Launcher beim Start alles auf einmal reserviert – belegten andere Programme Speicher, startete Java
  nicht. Jetzt werden höchstens 8 GB vorab reserviert, und nur so viel, wie gerade frei ist; der Rest wächst nach
  Bedarf. Vor jedem Start prüft der Launcher, ob Java den Speicher bekommt, und scheitert Java trotzdem, startet er das
  Spiel sofort mit sicheren Werten neu statt das Java-Fehlerfenster zu zeigen.
- **Hinweis bei sehr viel RAM.** Ab 16 GB erklärt der RAM-Regler, dass Minecraft selten mehr als 8–12 GB braucht.

## 0.17.0 – 2026-10-03 – Light Luggage | Leichtes Gepäck
<!-- banner: accent=#ff3b30 motif=/news/0.17.0/banner.png -->
<!-- shots:
/news/0.17.0/pack-share-upload.png | Sharing a modpack: mods go in as links, only your own files are uploaded | Modpack teilen: Mods als Link, hochgeladen werden nur deine eigenen Dateien
/news/0.17.0/chat-heads.png | Chat Heads: the sender's head in front of every message | Chat-Köpfe: der Kopf des Absenders vor jeder Nachricht
-->

### English
- **Borderless fullscreen in the TRS Client.** F11 now fills your monitor with a borderless window instead of
  exclusive fullscreen: alt-tab is instant, Discord and clips work reliably. On by default, switch it off under
  Performance → Borderless Fullscreen.
- **Hunger Overlay follows your resource pack.** If a pack ships AppleSkin textures, the TRS Client uses them;
  otherwise the saturation outline takes the shape of the pack's own hunger icons.
- **Chat Heads in the TRS Client.** The sender's skin head now shows in front of chat messages – also for server
  formats like [Rank] Name » text, private messages and in the chat history. On by default. Based on Chat Heads by
  dzwdz (MPL-2.0).
- **Fix: crash on start with some mod packs.** Since the borderless fullscreen update the game could crash while
  loading when another mod brought its own MixinExtras. Fixed.
- **Sharing modpacks 2.0.** Shared packs are now much smaller: mods from Modrinth and CurseForge are stored as
  download links (found by their file fingerprint, even when renamed) and only your own files are uploaded. Packs
  may be up to 1 GB, the upload runs in parts with progress, speed, pause and resume, and the share code installs
  everything again 1:1 – every download is checked against its fingerprint.

### Deutsch
- **Randloses Vollbild im TRS Client.** F11 füllt jetzt den Bildschirm mit einem randlosen Fenster statt
  exklusivem Vollbild: Alt-Tab geht sofort, Discord und Clips laufen zuverlässig. Ab Werk an, abschaltbar unter
  Leistung → Randloses Vollbild.
- **Hunger-Anzeige passt sich dem Resource Pack an.** Bringt ein Pack AppleSkin-Texturen mit, nutzt der TRS Client
  diese; sonst übernimmt der Sättigungsrand die Form der Hunger-Symbole des Packs.
- **Chat-Köpfe im TRS Client.** Vor Chat-Nachrichten erscheint jetzt der Skin-Kopf des Absenders – auch bei
  Server-Formaten wie [Rang] Name » Text, bei privaten Nachrichten und im Chat-Verlauf. Ab Werk an. Basiert auf
  Chat Heads von dzwdz (MPL-2.0).
- **Behoben: Absturz beim Start mit manchen Modpacks.** Seit dem randlosen Vollbild konnte das Spiel beim Laden
  abstürzen, wenn eine andere Mod ihr eigenes MixinExtras mitbrachte. Behoben.
- **Modpacks teilen 2.0.** Geteilte Packs sind jetzt viel kleiner: Mods von Modrinth und CurseForge werden als
  Download-Link gespeichert (am Datei-Fingerabdruck erkannt, auch wenn sie umbenannt sind), hochgeladen werden nur
  deine eigenen Dateien. Packs dürfen bis zu 1 GB groß sein, der Upload läuft in Teilen mit Fortschritt, Tempo,
  Pause und Fortsetzen, und der Teilen-Code installiert alles wieder 1:1 – jeder Download wird anhand seines
  Fingerabdrucks geprüft.

## 0.16.0 – 2026-10-02 – Spooky Season | Gruselzeit
<!-- banner: accent=#ff7a1a motif=/news/0.16.0/banner.png -->
<!-- shots:
/news/0.16.0/halloween-cosmetics.webp | Witch hat, pumpkin head and the new bat companion | Hexenhut, Kürbiskopf und der neue Fledermaus-Begleiter
/news/0.16.0/website-halloween.webp | Halloween look with the night scene and the flying witch | Halloween-Look mit Nachtszene und fliegender Hexe
/news/0.16.0/hunger-overlay.png | Hunger Overlay: saturation and a preview of the food in your hand | Hunger-Anzeige: Sättigung und Vorschau für das Essen in der Hand
-->

### English
- **Hunger Overlay in the TRS Client.** A new module on the hunger bar: your saturation shows as a golden outline,
  the food in your hand flashes how much hunger it fills up and how much health it will heal, and in singleplayer a
  light bar shows how close the next hunger point is. Each part can be switched off. Idea from AppleSkin, works in
  every version from Minecraft 1.7.10 to 26.3.
- **Halloween in the launcher.** When the TRS team switches on the Halloween event (for everyone or just for you), the
  launcher turns orange and purple: a night start page with a full moon, pumpkins and bats, a witch on a broom who
  now and then flies across every page, and every progress bar becomes a redstone bar with the witch flying along.
  Under Skins you can grab the witch hat, the pumpkin head, the bat companion and the Halloween cape for free while
  the event runs – they stay yours afterwards. The bat is a new kind of cosmetic: a companion you wear next to your
  hat. The team switches the event under Team → Events.
- **Duplicate mods.** If an instance has the same mod twice (for example two Sodium versions), the launcher now warns
  before starting and in the mods list, and fixes it with one click: the newest version stays, older copies are
  disabled (not deleted).
- **Smoother fullscreen in the TRS Client.** Dynamic FPS treated a visible fullscreen window like a minimised one and
  dropped to 1 FPS, which caused lag and a delay of a few seconds after clicking or alt-tabbing. Fullscreen now counts
  as focused.
- **Skins page.** The reset-camera button now sits centred under the preview.

### Deutsch
- **Hunger-Anzeige im TRS Client.** Ein neues Modul an der Hungerleiste: deine Sättigung erscheint als goldener Rand,
  das Essen in deiner Hand zeigt blinkend, wie viel Hunger es auffüllt und wie viel Leben es heilt, und im
  Einzelspieler zeigt ein heller Balken, wie nah der nächste Hungerpunkt ist. Jeder Teil lässt sich abschalten. Idee
  von AppleSkin, läuft in jeder Version von Minecraft 1.7.10 bis 26.3.
- **Halloween im Launcher.** Schaltet das TRS-Team das Halloween-Event ein (für alle oder nur für dich), wird der
  Launcher orange und lila: eine nächtliche Startseite mit Vollmond, Kürbissen und Fledermäusen, eine Hexe auf dem
  Besen, die ab und zu über jede Seite fliegt, und jeder Fortschrittsbalken wird ein Redstone-Balken mit
  mitfliegender Hexe. Unter Skins holst du dir während des Events gratis den Hexenhut, den Kürbiskopf, den
  Fledermaus-Begleiter und den Halloween-Umhang – sie bleiben danach deine. Die Fledermaus ist eine neue Art
  Kosmetik: ein Begleiter, den du zusätzlich zum Hut trägst. Das Team schaltet das Event unter Team → Events.
- **Doppelte Mods.** Liegt eine Mod doppelt in einer Instanz (zum Beispiel zwei Sodium-Versionen), warnt der Launcher
  jetzt vor dem Start und in der Mod-Liste und behebt es mit einem Klick: Die neueste Version bleibt, ältere Kopien
  werden deaktiviert (nicht gelöscht).
- **Flüssigeres Vollbild im TRS Client.** Die dynamischen FPS haben ein sichtbares Vollbild wie ein minimiertes
  Fenster behandelt und auf 1 FPS gedrosselt – das gab Ruckler und ein paar Sekunden Verzögerung nach Klick oder
  Alt-Tab. Vollbild zählt jetzt als aktiv.
- **Skins-Seite.** Der Knopf zum Zurücksetzen der Kamera sitzt jetzt mittig unter der Vorschau.

## 0.15.2 – 2026-09-30 – The Polish Update | Das Feinschliff-Update
<!-- banner: accent=#ff7a3d motif=/news/0.15.2/banner.png -->
<!-- shots:
/news/0.15.2/discover-source.png | Modrinth and CurseForge with their own marks | Modrinth und CurseForge mit ihren eigenen Zeichen
-->

### English
- **Source marks.** On Discover, Modrinth and CurseForge show their own marks next to the name. The switch itself
  stays the same.

### Deutsch
- **Quellen-Zeichen.** Beim Entdecken stehen neben Modrinth und CurseForge ihre eigenen Zeichen. Der Schalter selbst
  bleibt gleich.

## 0.15.1 – 2026-09-30 – The Wardrobe Update | Das Kleiderschrank-Update
<!-- banner: accent=#e10600 motif=/news/0.15.1/banner.png -->
<!-- shots:
/news/0.15.1/skins-page.png | Skins, capes and cosmetics as tabs, with small 3D skin cards | Skins, Umhänge und Kosmetik als Reiter, mit kleinen 3D-Skin-Karten
-->

### English
- **Default skin preview.** “Account default” on the Skins page shows the real skin Minecraft gives this account
  (Steve, Alex, Ari and the others, with the matching arm width), read from an installed version – the option
  tells you which one and from which Minecraft version. Until a version is installed, a simple figure stands in.
- **New Skins page.** A large 3D figure with your player name on the left that stays in place while you scroll,
  and tabs for **Skins**, **Capes** and **Cosmetics** on the right. Skins are cards with a small 3D figure;
  capes and cosmetics keep their previous cards (the cape picture or the cosmetic, the name and the badge).
  Whatever you click shows up on the big figure right away, and “Apply” still sends everything at once.
  Skin sections can be collapsed – the page remembers it.
- **All 18 default skins.** Alex, Ari, Efe, Kai, Makena, Noor, Steve, Sunny and Zuri – each slim and wide – can be
  picked and applied directly (the textures come from an installed Minecraft version).
- **Official skin packs.** The free Java skins Mojang has released on minecraft.net (Chaos Cubed, Tiny Takeover,
  Mounts of Mayhem, The Copper Age, Chase the Skies, The Garden Awakens, Striding Hero, Builders & Biomes) can be
  tried on and applied. They are downloaded only from minecraft.net and cached; without internet the section is hidden.
- **TRS Client: compact "Add Server" window.** In the TRS menu style, "Add Server", "Edit Server" and "Direct
  Connection" stretched their dark panel to the edge of the screen when another mod put a button in a corner (for
  example "Set version" from ViaFabricPlus). The panel now always sits compact and centred around the form; buttons of
  other mods next to the form still get included.
- **TRS Client: calmer head cosmetics from a distance.** The glowing crowns and hats use very detailed textures. From
  further away they could flicker and sparkle from frame to frame, which made the Redstone Crown look like it was
  blinking much too fast. The client now switches to smoother, less detailed versions of the textures the further away
  (or smaller) the cosmetic is – like Minecraft does for blocks. Up close nothing changes; the animation keeps exactly
  the pace of the TRS Studio preview (the running light still steps every 140 ms, the lamps pulse every 1.68 s).

### Deutsch
- **Vorschau des Standard-Skins.** „Standard des Kontos“ auf der Skins-Seite zeigt den echten Skin, den Minecraft diesem
  Konto gibt (Steve, Alex, Ari und die anderen, mit der passenden Armbreite), aus einer installierten Version – die
  Auswahl sagt dir, welcher es ist und aus welcher Minecraft-Version. Solange keine Version installiert ist,
  steht eine einfache Figur da.
- **Neue Skins-Seite.** Links eine große 3D-Figur mit deinem Spielernamen, die beim Scrollen stehen bleibt, rechts
  die Reiter **Skins**, **Umhänge** und **Kosmetik**. Skins sind Karten mit kleiner 3D-Figur; Umhänge und
  Kosmetik behalten ihre bisherigen Karten (Umhang-Bild bzw. Kosmetik, Name und Badge). Was du anklickst,
  erscheint sofort auf der großen Figur, und „Anwenden“ schickt weiterhin alles auf einmal. Die Skin-Abschnitte
  lassen sich zuklappen – die Seite merkt sich das.
- **Alle 18 Standard-Skins.** Alex, Ari, Efe, Kai, Makena, Noor, Steve, Sunny und Zuri – jeweils schmal und breit –
  lassen sich direkt auswählen und anwenden (die Texturen stammen aus einer installierten Minecraft-Version).
- **Offizielle Skin-Pakete.** Die kostenlosen Java-Skins, die Mojang auf minecraft.net veröffentlicht hat (Chaos
  Cubed, Tiny Takeover, Mounts of Mayhem, The Copper Age, Chase the Skies, The Garden Awakens, Striding Hero,
  Builders & Biomes), kannst du anprobieren und anwenden. Geladen wird nur von minecraft.net, danach aus dem Cache;
  ohne Internet ist der Abschnitt ausgeblendet.
- **TRS Client: kompaktes Fenster „Server hinzufügen“.** Im TRS-Menü-Stil zogen „Server hinzufügen“, „Server
  bearbeiten“ und „Direkt verbinden“ ihre dunkle Fläche bis zum Bildschirmrand, wenn eine andere Mod einen Knopf in eine
  Ecke setzt (zum Beispiel „Set version“ von ViaFabricPlus). Die Fläche sitzt jetzt immer kompakt und mittig um das
  Formular; Knöpfe anderer Mods direkt am Formular gehören weiter dazu.
- **TRS Client: ruhigere Kopf-Kosmetik aus der Entfernung.** Die leuchtenden Kronen und Hüte haben sehr feine
  Texturen. Aus größerer Entfernung konnten sie von Bild zu Bild flimmern und glitzern – die Redstone-Krone wirkte dann,
  als würde sie viel zu schnell blinken. Der Client nimmt jetzt je nach Entfernung (bzw. Größe auf dem Bildschirm)
  weichere, weniger detaillierte Fassungen der Texturen – wie Minecraft bei Blöcken. Aus der Nähe ändert sich nichts; die
  Animation läuft weiter genau im Takt der Vorschau im TRS Studio (Lauflicht alle 140 ms, Lampen-Puls alle 1,68 s).

## 0.15.0 – 2026-09-29 – Crowned | Gekrönt
<!-- banner: accent=#ffa62b motif=/news/0.15.0/banner.png -->
<!-- shots:
/news/0.15.0/launcher-head-cosmetics.png | Real 3D head cosmetics in the launcher | Echte 3D-Kopf-Kosmetik im Launcher
/news/0.15.0/launcher-try-on.png | Try a crown on in the big 3D preview – together with your cape | Krone in der großen 3D-Vorschau anprobieren – zusammen mit deinem Umhang
/news/0.15.0/launcher-night.png | Night mode: see how your cosmetics glow | Nacht-Modus: So leuchtet deine Kosmetik
/news/0.15.0/game-redstone-crown.png | The Redstone Crown in the game | Die Redstone-Krone im Spiel
/news/0.15.0/game-team-crown-night.png | The Team Crown glowing at night | Die Team-Krone leuchtet in der Nacht
-->

### English
- **3D head cosmetics.** Crowns, the TRS cap, the lamp helmet, the top hat and the halo are now real 3D models with
  animations and glowing parts. On the Skins page, a new **Head cosmetics** section below the TRS capes shows every
  item as a picture card – locked ones too, with how to get them (code, achievement or TRS team only). Click a card to
  try it on in the big 3D preview together with your cape, turn it around, switch between **day and night** (at night
  the lamps and crystals glow) and zoom to the head. **Put on** / **Take off** right there.
- **Help & docs.** The new documentation on the website (in English, German and Spanish) replaces the old GitHub
  wiki, and the launcher now takes you straight to the right page in your language: the question mark in the title
  bar, “Help & docs” at the bottom of the settings or in the search (Ctrl+K), “More help” in the crash helper, the
  question mark in the log viewer, a Java guide in the Java settings and a Linux guide next to the sign-in storage
  hint on Linux. Other launcher languages open the English docs.
- **TRS Client:** the TRS menu has a new **Help** entry that opens the TRS Client guide in your browser, in the
  language of the client.
- **Thank you, contributors.** "What's new" now names everyone who helped with an update on GitHub – with code, fixes
  or translations.
- **TRS Client: real 3D head cosmetics.** Crowns, hats, the TRS cap, the lamp helmet and the halo are now proper 3D
  models from the TRS Studio – with bones, animations that run in sync for everyone, glowing parts and soft light halos,
  looking exactly like the preview on the website and in the launcher. They work in every Minecraft version the client
  supports (Fabric, Forge, NeoForge, 1.8.9–26.3) and sit neatly on top of a helmet. The rubber duck keeps waddling.
- **TRS Client: new Cosmetics tab in the wardrobe.** Browse your head cosmetics as cards, try one on the big rotating
  player (with animation and glow, day or night), and put it on or take it off. Locked ones are greyed out and tell you
  how to get them (redeem a code in the launcher, an achievement or the TRS team).

### Deutsch
- **Kopf-Kosmetik in 3D.** Kronen, TRS-Cap, Lampen-Helm, Zylinder und Heiligenschein sind jetzt echte 3D-Modelle mit
  Animationen und leuchtenden Teilen. Auf der Skins-Seite zeigt der neue Abschnitt **Kopf-Kosmetik** unter den
  TRS-Umhängen alle Teile als Bild-Karten – auch gesperrte, mit dem Weg dorthin (Code, Erfolg oder nur Team). Ein Klick
  probiert das Teil in der großen 3D-Vorschau an, zusammen mit deinem Umhang: drehen, zwischen **Tag und Nacht**
  wechseln (nachts leuchten Lampen und Kristalle) und auf den Kopf zoomen. **Aufsetzen** / **Absetzen** direkt dort.
- **Hilfe & Docs.** Die neue Dokumentation auf der Website (auf Englisch, Deutsch und Spanisch) ersetzt das alte
  GitHub-Wiki, und der Launcher bringt dich direkt zur passenden Seite in deiner Sprache: das Fragezeichen in der
  Titelleiste, „Hilfe & Docs“ unten in den Einstellungen oder in der Suche (Strg+K), „Mehr Hilfe“ im Absturz-Helfer,
  das Fragezeichen in der Log-Ansicht, eine Java-Anleitung in den Java-Einstellungen und unter Linux eine
  Linux-Anleitung beim Hinweis zu den gespeicherten Anmeldedaten. Andere Launcher-Sprachen öffnen die englischen Docs.
- **TRS Client:** Im TRS-Menü gibt es den neuen Eintrag **Hilfe** – er öffnet die Anleitung zum TRS Client im
  Browser, in der Sprache des Clients.
- **Danke an alle, die mitmachen.** „Was ist neu“ nennt jetzt alle, die auf GitHub an einem Update mitgearbeitet
  haben – mit Code, Fehlerbehebungen oder Übersetzungen.
- **TRS Client: echte 3D-Kopf-Kosmetik.** Kronen, Hüte, die TRS-Cap, der Lampen-Helm und der Heiligenschein sind jetzt
  richtige 3D-Modelle aus dem TRS Studio – mit Knochen, Animationen, die bei allen gleichzeitig laufen, leuchtenden
  Teilen und weichen Lichthöfen, genau wie in der Vorschau auf der Website und im Launcher. Sie funktionieren in allen
  Minecraft-Versionen des Clients (Fabric, Forge, NeoForge, 1.8.9–26.3) und sitzen mit Helm sauber auf dem Helm. Die
  Quietscheente watschelt weiter.
- **TRS Client: neuer Reiter „Kosmetik“ in der Garderobe.** Deine Kopf-Kosmetik als Karten, Anprobieren an der großen
  drehbaren Figur (mit Animation und Leuchten, bei Tag oder Nacht) und Aufsetzen oder Absetzen. Gesperrte Teile sind
  ausgegraut und zeigen, wie du sie bekommst (Code im Launcher einlösen, Erfolg oder TRS-Team).

## 0.14.0 – 2026-09-28 – Achievement Unlocked | Erfolg freigeschaltet
<!-- banner: accent=#ffc53d motif=/news/0.14.0/banner.png -->
<!-- shots:
/news/0.14.0/achievements.png | Achievements with points, rarities and rewards | Erfolge mit Punkten, Seltenheit und Belohnungen
/news/0.14.0/friends-achievements.png | See what your friends achieved | Sieh, was deine Freunde geschafft haben
/news/0.14.0/screenshot-toast.png | After F2: edit, favourite, copy or send your screenshot | Nach F2: Screenshot bearbeiten, merken, kopieren oder senden
/news/0.14.0/screenshot-editor.png | The screenshot editor: crop, arrows, text, pixelate, pen | Der Screenshot-Editor: zuschneiden, Pfeile, Text, verpixeln, Stift
/news/0.14.0/notes.png | Notes for every world – with checklists and coordinates | Notizen für jede Welt – mit Checklisten und Koordinaten
/news/0.14.0/notes-links.png | Click a coordinate: world map or waypoint | Koordinate anklicken: Weltkarte oder Wegpunkt
/news/0.14.0/menus.png | Server screens in the TRS style | Server-Fenster im TRS-Stil
-->

### English
- Installing a modpack (CurseForge or Modrinth) no longer looks finished while it is still downloading: the new
  instance shows “Downloading mods · 120/261 … 45 %” in the library and on its page, and Play stays locked until
  everything is there. Big packs also download faster – large files first and more downloads at the same time.
- TRS Client menu style: "Direct Connection" and "Add Server" / "Edit Server" now use the redstone look too – text
  fields, buttons and the server resource pack switch on a calm panel instead of the plain vanilla screen.
- Text fields in all restyled menus (e.g. world search) look like the TRS text fields and glow while you type.
- Confirmations such as "Delete server?", the server resource pack question and link warnings follow the style of
  the menu they come from; the multiplayer warning, "Open to LAN", world editing, game rules, flat/buffet world
  settings, resource packs and credits are restyled as well.
- **Screenshot preview (TRS Client).** After F2 a small preview of your screenshot slides in at the top right for a
  few seconds. Open the chat or your inventory to use it: hover it and four buttons appear – **Edit**, **Favourite**
  (the heart), **Copy picture** (straight to the clipboard, paste it anywhere with Ctrl+V) and **Send to friends**
  (pick one or more friends or chats, the picture goes out as a normal chat image). While your mouse is on it, it
  stays. Length and on/off are in the new module “Screenshot Tools”.
- The chat message “Screenshot saved …” now has **[Edit] [Copy link] [Copy picture]**, and hovering it shows the
  picture. “Copy link” uploads the screenshot as a share link (valid for 30 days) and copies it.
- **Screenshot editor.** Crop with handles on the corners and edges, rotate by 90°, draw arrows, frames, text and
  freehand lines, and **pixelate** areas to hide names or coordinates (great for streamers). Pick a colour and a size,
  undo and redo (also with Z and Y). Saving always creates “Copy of …” next to the original – your original
  screenshot is never changed. Share straight from the editor: send to friends, copy the picture or copy a link.
- **Favourites** show up in their own tab in “Clips & Images”; the big image view there got a heart and an edit
  button too.
- **Works with Essential.** If Essential is installed, its screenshot popup is replaced by the TRS preview (setting
  “Replace Essential's screenshot preview”, on by default – turning it off gives Essential its preview back).
- Available in every TRS Client version. Minecraft 1.7.10 and 1.13.2 have the preview, editor, favourites and copy
  picture (no links or sending there); copying pictures on Linux needs wl-clipboard or xclip.
- **Achievements.** The launcher now has achievements: for playtime, for trying out launcher features and for the
  community – plus a few secret ones you only see once you have unlocked them. Every achievement earns points and has
  a rarity; some come with a reward (100 hours played gives the "Veteran" cape, an implemented idea the "Ideengeber"
  cape, 10 friends the “Party” emote). Open them from the account menu at the top right, the
  trophy in Social or with Ctrl+K: grouped by category, with progress bars (playtime in hours), unlock dates and a
  filter for all, unlocked and locked.
- When you unlock an achievement, a notification pops up. If it gives you a cape or a head cosmetic, "Wear now" puts
  it on right away; for the emote it tells you where to find it in the TRS Client. You can switch these notifications
  off under Settings → Notifications → "Unlocked achievements".
- See what your friends achieved: click a friend in Social to see their points and latest achievements, or open all of
  them. Secret achievements stay "???" until you have them too. If you'd rather keep yours to yourself, turn off
  "Achievements visible to friends" on the achievements page or under Settings → Privacy.
- **World notes in the TRS Client.** Every world and server now has its own notebook: open it with the new *Notes*
  entry in the TRS menu or a key of your choice (unbound by default, set it in the controls or on the *World Notes*
  page). Write as many notes as you like (up to 200 per world) with a title and text, search the notes of the world
  you are in, and look at the notes of your other worlds and servers.
- **Checklists:** start a line with `[ ]` (or press *Checklist*) and tick the boxes right in the note – Enter continues
  the list. The list shows how many items are done.
- **Clickable coordinates:** coordinates like `x: 100, y: 64, z: -20` become links – *My position* inserts where you
  stand. Click one to show the spot on the world map, set a temporary waypoint (it disappears when you get there) or
  save it as a normal waypoint.
- **Pin a note to the HUD:** the pin button shows the note (with its checklist) on screen while you are in that world;
  move and resize it in the HUD editor like every other HUD element.
- Notes are saved in `config/trsclient/notes` and synced with your TRS account note by note: the newest change of a
  note wins, deleted notes are deleted on all your PCs, and changes from another PC arrive within seconds. Sync can be
  switched off on the *World Notes* page – then your notes stay on this PC.
- New emote “Party” in the TRS Client – the reward for having 10 friends (achievements).

### Deutsch
- Ein Modpack (CurseForge oder Modrinth) sieht beim Installieren nicht mehr fertig aus, während es noch lädt: Die neue
  Instanz zeigt in der Bibliothek und auf ihrer Seite „Mods werden geladen · 120/261 … 45 %“, und Spielen bleibt
  gesperrt, bis alles da ist. Große Packs laden außerdem schneller – große Dateien zuerst und mehr Downloads
  gleichzeitig.
- TRS-Client-Menü-Stil: „Direkt verbinden“ und „Server hinzufügen“/„Server bearbeiten“ haben jetzt auch den
  Redstone-Look – Textfelder, Knöpfe und der Schalter für Server-Ressourcenpakete auf ruhiger Fläche statt im
  schlichten Vanilla-Bildschirm.
- Textfelder in allen gestylten Menüs (z. B. die Weltensuche) sehen aus wie die TRS-Eingabefelder und leuchten beim
  Tippen.
- Rückfragen wie „Server löschen?“, die Frage nach dem Server-Ressourcenpaket und Link-Warnungen folgen dem Stil des
  Menüs, aus dem sie kommen; Mehrspieler-Hinweis, „Im LAN öffnen“, Welt bearbeiten, Spielregeln,
  Flachland-/Buffet-Einstellungen, Ressourcenpakete und Mitwirkende sind ebenfalls gestylt.
- **Screenshot-Vorschau (TRS Client).** Nach F2 fährt oben rechts für ein paar Sekunden eine kleine Vorschau deines
  Screenshots herein. Öffne den Chat oder dein Inventar, um sie zu benutzen: Fährst du darüber, erscheinen vier Knöpfe –
  **Bearbeiten**, **Favorit** (das Herz), **Bild kopieren** (direkt in die Zwischenablage, überall mit Strg+V
  einfügen) und **An Freunde senden** (einen oder mehrere Freunde bzw. Chats auswählen, das Bild geht als normales
  Chat-Bild raus). Solange die Maus darauf liegt, bleibt sie stehen. Dauer und An/Aus im neuen Modul
  „Screenshot-Werkzeuge“.
- Die Chatnachricht „Screenshot gespeichert …“ hat jetzt **[Bearbeiten] [Link kopieren] [Bild kopieren]**, und
  beim Überfahren siehst du das Bild. „Link kopieren“ lädt den Screenshot als Teilen-Link hoch (30 Tage gültig) und
  kopiert ihn.
- **Screenshot-Editor.** Zuschneiden mit Griffen an Ecken und Kanten, um 90° drehen, Pfeile, Rahmen, Text und
  Freihand-Linien zeichnen und Bereiche **verpixeln**, um Namen oder Koordinaten zu verbergen (ideal für Streamer).
  Farbe und Größe wählen, Rückgängig und Wiederholen (auch mit Z und Y). Speichern legt immer „Kopie von …“ neben dem
  Original an – dein Original-Screenshot wird nie verändert. Direkt aus dem Editor teilen: an Freunde senden, Bild
  kopieren oder Link kopieren.
- **Favoriten** haben in „Clips & Bilder“ einen eigenen Reiter; die große Bildansicht dort hat außerdem ein Herz und
  einen Bearbeiten-Knopf.
- **Klappt mit Essential.** Ist Essential installiert, ersetzt die TRS-Vorschau das Screenshot-Popup von Essential
  (Einstellung „Essential-Screenshot-Vorschau ersetzen“, ab Werk an – ausgeschaltet bekommt Essential seine Vorschau
  zurück).
- In jeder TRS-Client-Version verfügbar. Minecraft 1.7.10 und 1.13.2 haben Vorschau, Editor, Favoriten und Bild
  kopieren (dort ohne Links und Senden); Bilder kopieren unter Linux braucht wl-clipboard oder xclip.
- **Erfolge.** Der Launcher hat jetzt Erfolge: für Spielzeit, fürs Ausprobieren von Launcher-Funktionen und für die
  Community – dazu ein paar geheime, die du erst siehst, wenn du sie freigeschaltet hast. Jeder Erfolg bringt Punkte und
  hat eine Seltenheit; manche haben eine Belohnung (100 Stunden gespielt gibt den Umhang „Veteran“, eine umgesetzte Idee
  den Umhang „Ideengeber“, 10 Freunde das Emote „Party“). Du findest sie im
  Kontomenü oben rechts, über den Pokal in „Sozial“ oder mit Strg+K: nach Kategorien sortiert, mit Fortschrittsbalken
  (Spielzeit in Stunden), Datum der Freischaltung und Filter für alle, freigeschaltete und gesperrte.
- Schaltest du einen Erfolg frei, erscheint eine Benachrichtigung. Gibt es dafür einen Umhang oder eine Kopf-Kosmetik,
  legt „Jetzt tragen“ sie sofort an; beim Emote steht dort, wo du es im TRS Client findest. Abschalten kannst du diese
  Hinweise unter Einstellungen → Benachrichtigungen → „Freigeschaltete Erfolge“.
- Sieh dir an, was deine Freunde geschafft haben: Klick in „Sozial“ auf einen Freund, um seine Punkte und neuesten
  Erfolge zu sehen, oder öffne alle. Geheime Erfolge bleiben „???“, bis du sie selbst hast. Willst du deine lieber für
  dich behalten, schalte „Erfolge für Freunde sichtbar“ auf der Erfolge-Seite oder unter Einstellungen → Datenschutz aus.
- **Welt-Notizen im TRS Client.** Jede Welt und jeder Server hat jetzt ein eigenes Notizbuch: öffne es über den neuen
  Eintrag *Notizen* im TRS-Menü oder eine Taste deiner Wahl (ab Werk unbelegt, einstellbar in der Steuerung oder auf der
  Seite *Welt-Notizen*). Schreib beliebig viele Notizen (bis zu 200 je Welt) mit Titel und Text, durchsuche die Notizen
  der Welt, in der du bist, und schau dir die Notizen deiner anderen Welten und Server an.
- **Checklisten:** Beginne eine Zeile mit `[ ]` (oder drück *Checkliste*) und hake die Kästchen direkt in der Notiz ab –
  Enter setzt die Liste fort. Die Liste zeigt, wie viele Punkte erledigt sind.
- **Anklickbare Koordinaten:** Koordinaten wie `x: 100, y: 64, z: -20` werden zu Links – *Meine Position* fügt ein, wo du
  gerade stehst. Ein Klick zeigt die Stelle auf der Weltkarte, setzt einen vorübergehenden Wegpunkt (verschwindet, wenn
  du dort bist) oder speichert sie als normalen Wegpunkt.
- **Notiz ans HUD heften:** Der Pin-Knopf zeigt die Notiz (mit Checkliste) auf dem Bildschirm, solange du in dieser Welt
  bist; verschieben und vergrößern wie jedes andere HUD-Element im HUD-Editor.
- Notizen liegen in `config/trsclient/notes` und werden Notiz für Notiz mit deinem TRS-Konto synchronisiert: Die neueste
  Änderung einer Notiz gewinnt, gelöschte Notizen verschwinden auf all deinen PCs, und Änderungen von einem anderen PC
  kommen nach wenigen Sekunden an. Der Sync lässt sich auf der Seite *Welt-Notizen* ausschalten – dann bleiben deine
  Notizen auf diesem PC.
- Neues Emote „Party“ im TRS Client – die Belohnung für 10 Freunde (Erfolge).

## 0.13.0 – 2026-09-28 – Your Say | Mitreden
<!-- banner: accent=#a78bfa motif=/news/0.13.0/banner.png -->
<!-- shots:
/news/0.13.0/issues.png | Share ideas and report bugs – vote on what comes next | Ideen einreichen und Bugs melden – abstimmen, was als Nächstes kommt
/news/0.13.0/issue-page.png | Every issue with its own page, activity and community score | Jedes Issue mit eigener Seite, Aktivität und Community-Score
/news/0.13.0/roadmap.png | The roadmap: six columns from open to done | Die Roadmap: sechs Spalten von offen bis erledigt
/news/0.13.0/bug-report.png | Report bugs straight from the game – you see exactly what gets sent | Bugs direkt aus dem Spiel melden – du siehst genau, was mitgeht
/news/0.13.0/minimap.png | Minimap: waypoints on the edge, real mob heads, texture colours | Minimap: Wegpunkte am Rand, echte Mob-Köpfe, Texturfarben
/news/0.13.0/world-map.png | World map: smooth zoom, waypoint list with search, other dimensions | Weltkarte: stufenlos zoomen, Wegpunkt-Liste mit Suche, andere Dimensionen
/news/0.13.0/web-login.png | Sign in on the website with your TRS Launcher | Auf der Website mit dem TRS Launcher anmelden
-->

### English
- TRS Client minimap: waypoints outside the map now sit as small coloured boxes with their first letter on the ring
  (or edge) pointing the way – several close ones move apart a little instead of covering each other.
- The minimap takes its colours from the block textures of your resource pack (grass, leaves and water keep their
  biome colour, relief shading as before). You can switch back to the vanilla map colours in the minimap settings.
- Animals and monsters show up as their real heads (cut from the mob texture, so they match your resource pack) –
  or, if you prefer, as simple symbols for friendly, neutral and hostile creatures.
- Auto zoom: the minimap zooms out when you are fast (sprinting, riding, boat, elytra) and back in when you slow down,
  and it goes a step closer indoors and in caves. Both can be turned off on their own.
- **World map: smooth zoom and gliding (TRS Client).** The mouse wheel now zooms smoothly towards the mouse pointer –
  the spot under the pointer stays where it is. Let go while dragging and the map glides on for a moment. Big explored
  areas appear much faster: what you look at loads first, and far out the map uses overview tiles instead of single
  areas. Both can be switched off in the world map settings (then zoom works in fixed steps as before).
- **Waypoint list with search.** The pin button on the world map opens a list of all waypoints of the world: search by
  name or coordinate, filter by dimension, see the distance (across Nether and Overworld, converted ×8), turn each one
  on or off. Click a waypoint to center the map on it, the pencil opens the waypoint window (edit or delete), “+” adds
  a new one in the middle of the map.
- **View other dimensions.** Click the dimension name at the top of the world map to look at the Nether, the End or
  other dimensions of this world you have mapped – without going there. In the Nether view you can switch between the
  mapped cave levels. In the Nether and the Overworld the bottom bar also shows the matching coordinates of the other
  side (×8 or ÷8), handy for portals.
- **Export the map as an image.** The image button saves the whole explored map or just the visible part as a PNG in
  your `screenshots` folder (named after world, dimension and time; unexplored parts stay transparent). Very large
  maps are scaled down to the size set in the settings (default 8192 pixels). The export runs in the background – the
  game keeps running – and a chat message tells you the file name.
- TRS Client: report bugs straight from the game – TRS menu → "Report bug". Write a title and what happened, and choose
  what to attach: TRS Client version, Minecraft version and loader, your mod list, a log excerpt (latest.log or the
  newest crash report) and a screenshot (the newest one, another one, or take a new one). Before sending you see exactly
  what goes out; the log is cleaned first (tokens, UUIDs, your player and account names, IP addresses, user names in
  folder paths, email addresses and chat messages are removed). After sending you get the issue number and can open it
  in the browser. Works in every Minecraft version the TRS Client supports.
- New on the website: an issue tracker (trs-launcher.theredstonee.de/issues) and a roadmap. Share ideas and report
  bugs for the launcher, the TRS Client or the website, vote them up or down, comment with screenshots and follow
  issues. Every issue has its own page with the whole activity – comments and every change with its own symbol – and
  a sidebar with the community score, area, version & loader, status and tags. The roadmap board shows every issue in
  six columns – open, planned, in progress, in review, done and rejected – or as a compact list; filter with a search
  field (e.g. status:planned area:client) or the filter menus and open any issue in a window right on the board.
- Follow an issue and the launcher tells you when its status changes, the team answers, a fix ships ("fixed in") or it
  is merged into another issue – “View” opens it in your browser. You can turn this off under Settings →
  Notifications → “Issues you follow”.
- Sign in on the website with your launcher: choose “TRS Launcher” on trs-launcher.theredstonee.de, the launcher opens
  and shows the same short code, the website and the browser – click Confirm and you are signed in (Microsoft still
  works too). You can pick another of your accounts, and on another PC type the code under Settings → Privacy →
  “Sign in on the website” (or Ctrl+K). Nothing is confirmed without your click.
- News from the TRS team now appear under News on the home page (with TRS services switched on) and open with pictures
  and a button to read them on the website.

### Deutsch
- Minimap im TRS Client: Wegpunkte außerhalb der Karte sitzen jetzt als kleine farbige Kästchen mit ihrem
  Anfangsbuchstaben auf dem Ring (bzw. am Rand) und zeigen die Richtung – liegen mehrere dicht beieinander, rücken sie
  ein Stück auseinander, statt sich zu verdecken.
- Die Minimap nimmt ihre Farben aus den Block-Texturen deines Resource Packs (Gras, Laub und Wasser behalten ihre
  Biomfarbe, Relief-Schattierung wie bisher). In den Minimap-Einstellungen kannst du zu den Vanilla-Kartenfarben
  zurückschalten.
- Tiere und Monster erscheinen als ihre echten Köpfe (aus der Mob-Textur, passend zu deinem Resource Pack) – oder auf
  Wunsch als einfache Symbole für freundliche, neutrale und feindliche Kreaturen.
- Auto-Zoom: Die Minimap zoomt heraus, wenn du schnell unterwegs bist (Sprinten, Reiten, Boot, Elytren), und wieder
  hinein, wenn du langsamer wirst – drinnen und in Höhlen geht sie eine Stufe näher heran. Beides lässt sich einzeln
  abschalten.
- **Weltkarte: stufenlos zoomen und gleiten (TRS Client).** Das Mausrad zoomt jetzt stufenlos zum Mauszeiger hin – die
  Stelle unter dem Zeiger bleibt, wo sie ist. Lässt du beim Ziehen los, gleitet die Karte noch kurz weiter. Große
  erkundete Gebiete erscheinen deutlich schneller: Was du ansiehst, lädt zuerst, und weit herausgezoomt nutzt die Karte
  Übersichtskacheln statt einzelner Bereiche. Beides lässt sich in den Weltkarten-Einstellungen abschalten (dann zoomt
  sie wie bisher in festen Stufen).
- **Wegpunkt-Liste mit Suche.** Der Stecknadel-Knopf auf der Weltkarte öffnet eine Liste aller Wegpunkte der Welt: nach
  Name oder Koordinate suchen, nach Dimension filtern, Entfernung sehen (auch über Nether und Oberwelt hinweg, ×8
  umgerechnet), jeden einzeln ein- oder ausblenden. Klick auf einen Wegpunkt zentriert die Karte darauf, der Stift
  öffnet das Wegpunkt-Fenster (bearbeiten oder löschen), „+“ legt einen neuen in der Kartenmitte an.
- **Andere Dimensionen ansehen.** Ein Klick auf den Dimensionsnamen oben auf der Weltkarte zeigt den Nether, das End oder
  andere Dimensionen dieser Welt, die du schon kartiert hast – ohne dorthin zu reisen. In der Nether-Ansicht lässt sich
  zwischen den kartierten Höhlenebenen wechseln. Im Nether und in der Oberwelt zeigt die untere Leiste zusätzlich die
  passenden Koordinaten der anderen Seite (×8 bzw. ÷8) – praktisch für Portale.
- **Karte als Bild exportieren.** Der Bild-Knopf speichert die ganze erkundete Karte oder nur den sichtbaren Ausschnitt
  als PNG im Ordner `screenshots` (benannt nach Welt, Dimension und Uhrzeit; Unerkundetes bleibt durchsichtig). Sehr
  große Karten werden auf die eingestellte Größe verkleinert (Standard 8192 Pixel). Der Export läuft im Hintergrund –
  das Spiel läuft weiter – und eine Chat-Nachricht nennt den Dateinamen.
- TRS Client: Bugs direkt aus dem Spiel melden – TRS-Menü → „Bug melden“. Titel und Beschreibung eingeben und
  auswählen, was mitgeht: TRS-Client-Version, Minecraft-Version und Loader, deine Mod-Liste, ein Log-Ausschnitt
  (latest.log oder der neueste Absturzbericht) und ein Screenshot (der neueste, ein anderer oder gleich ein neuer). Vor
  dem Senden siehst du genau, was mitgeht; das Log wird vorher gesäubert (Tokens, UUIDs, dein Spieler- und deine
  Kontonamen, IP-Adressen, Benutzernamen in Ordnerpfaden, E-Mail-Adressen und Chat-Nachrichten fliegen raus). Danach
  bekommst du die Issue-Nummer und kannst sie im Browser öffnen. Geht in jeder Minecraft-Version, die der TRS Client
  unterstützt.
- Neu auf der Website: ein Issue-Tracker (trs-launcher.theredstonee.de/issues) und eine Roadmap. Reiche Ideen ein und
  melde Bugs für den Launcher, den TRS Client oder die Website, stimme hoch oder runter, kommentiere mit Screenshots
  und folge Issues. Jedes Issue hat eine eigene Seite mit der ganzen Aktivität – Kommentare und jede Änderung mit
  eigenem Symbol – und einer Seitenleiste mit Community-Score, Bereich, Version & Loader, Status und Tags. Das
  Roadmap-Board zeigt alle Issues in sechs Spalten – offen, geplant, in Arbeit, in Prüfung, erledigt und abgelehnt –
  oder als kompakte Liste; filtern geht über ein Suchfeld (z. B. status:geplant bereich:client) oder die Filter-Menüs,
  und jedes Issue öffnet sich als Fenster direkt über dem Board.
- Folgst du einem Issue, sagt dir der Launcher Bescheid, wenn sich der Status ändert, das Team antwortet, eine Lösung
  erscheint („Erledigt in“) oder es mit einem anderen Issue zusammengeführt wird – „Ansehen“ öffnet es im Browser.
  Abschalten kannst du das unter Einstellungen → Benachrichtigungen → „Issues, denen du folgst“.
- Auf der Website mit dem Launcher anmelden: Wähle auf trs-launcher.theredstonee.de „TRS Launcher“, der Launcher öffnet
  sich und zeigt denselben kurzen Code, die Website und den Browser – auf Bestätigen klicken und du bist angemeldet
  (Microsoft geht weiterhin). Du kannst ein anderes deiner Konten wählen und an einem anderen PC den Code unter
  Einstellungen → Datenschutz → „Auf der Website anmelden“ (oder Strg+K) eingeben. Ohne deinen Klick wird nichts bestätigt.
- News vom TRS-Team erscheinen jetzt unter Neuigkeiten auf der Startseite (mit eingeschalteten TRS-Diensten) und öffnen
  sich mit Bildern und einem Knopf zum Weiterlesen auf der Website.

## 0.12.0 – 2026-09-27 – Pass It On | Weitergeben
<!-- banner: accent=#27d3e6 motif=/news/0.12.0/banner.png -->
<!-- shots:
/news/0.12.0/share-result.png | Share a modpack: code, link or straight to friends | Modpack teilen: Code, Link oder direkt an Freunde
/news/0.12.0/share-dialog.png | Mod list and settings – you pick the folders and how long the code works | Mod-Liste und Einstellungen – du wählst die Ordner und wie lange der Code gilt
/news/0.12.0/sent-to-you.png | Packs your friends sent you, right in the library | Packs von Freunden direkt in der Bibliothek
/news/0.12.0/install-by-code.png | Install by code – with a warning for mod files that aren’t on Modrinth | Per Code installieren – mit Hinweis auf Mod-Dateien, die nicht von Modrinth stammen
/news/0.12.0/pack-update.png | New version? Update with one click, your own changes stay | Neue Version? Ein Klick, eigene Änderungen bleiben
/news/0.12.0/my-modpacks.png | My modpacks: code, duration, send, delete | Meine Modpacks: Code, Laufzeit, schicken, löschen
-->

### English
- Installing a modpack no longer waits for the pack check – click Install right away. If you haven't picked with or
  without TRS Client yet, the launcher chooses as usual (including the check for mods that clash).
- Share modpacks: Instance → Share → Share modpack uploads your mod list and the settings you pick and gives you a
  code (TRS-XXXX-XXXX) and a link – or send it straight to friends. You choose how long the code works (1, 7 or 30 days
  or no expiry). Others install it with Library → “Modpack by code”, from the list “Sent to you” or from the link page.
- Upload a new version and keep the code: everyone who installed the pack sees “Update” – files they changed themselves
  stay as they are. Mod files that aren't on Modrinth need a confirmation when sharing and show a warning when installing.
- “Open in TRS Launcher” on a pack's web page starts the launcher (or brings it to the front) and shows the pack right
  away – nothing is installed until you click Install.
- Exported modpacks no longer contain the TRS Client itself, its settings or private files such as the clip key.

### Deutsch
- Modpacks lassen sich sofort installieren, ohne auf die Prüfung des Packs zu warten. Hast du „mit“ oder „ohne TRS
  Client“ noch nicht gewählt, entscheidet der Launcher wie gewohnt (samt Prüfung auf Mods, die sich beißen).
- Modpacks teilen: Instanz → Teilen → Modpack teilen lädt deine Mod-Liste und die Einstellungen, die du auswählst,
  hoch und gibt dir einen Code (TRS-XXXX-XXXX) und einen Link – oder schick es direkt an Freunde. Wie lange der Code
  gilt, wählst du selbst (1, 7 oder 30 Tage oder unbegrenzt). Andere installieren es über Bibliothek → „Modpack per
  Code“, über die Liste „An dich geschickt“ oder über die Link-Seite.
- Neue Version hochladen und den Code behalten: Wer das Pack installiert hat, sieht „Update“ – selbst geänderte Dateien
  bleiben, wie sie sind. Mod-Dateien, die es nicht auf Modrinth gibt, brauchen beim Teilen eine Bestätigung und zeigen
  beim Installieren einen Hinweis.
- „Im TRS Launcher öffnen“ auf der Webseite eines Packs startet den Launcher (oder holt ihn nach vorne) und zeigt das
  Pack sofort – installiert wird erst, wenn du auf Installieren klickst.
- Exportierte Modpacks enthalten nicht mehr den TRS Client selbst, seine Einstellungen oder private Dateien wie den
  Clip-Schlüssel.

## 0.11.0 – 2026-09-27 – Back Online | Wieder verbunden
<!-- banner: accent=#3ddc84 motif=/news/0.11.0/banner.png -->
<!-- shots:
/news/0.11.0/sign-in-again.png | Invalid session? Sign in again right on the error screen – no restart | Ungültige Sitzung? Direkt im Fehlerbildschirm neu anmelden – ohne Neustart
/news/0.11.0/key-search.png | Search Minecraft's key binds, e.g. key:2 for everything on 2 | Suche in der Tastenbelegung, z. B. key:2 für alles auf der 2
/news/0.11.0/key-conflicts.png | conflict shows every key that is used twice | konflikt zeigt alle doppelt belegten Tasten
/news/0.11.0/log-filters.png | Combine log filters: levels, TRS and chat | Log-Filter kombinieren: Stufen, TRS und Chat
/news/0.11.0/log-errors.png | Only errors, with the stack trace one click away | Nur Fehler, der Stacktrace einen Klick entfernt
-->

### English

- **Log filters you can combine.** In an instance's Logs tab, Errors, Warnings, Info and Debug are now switches: turn
  on as many as you like (for example only errors and warnings). New buttons "TRS" and "Chat" show only lines of the
  TRS Client or chat messages, and work together with the levels and the search. "All" shows everything again. Works
  for the live log and for older log files.
- **Search in Minecraft's key binds (TRS Client).** Controls → Key Binds now has a search box: type a name, or
  `key:R` / `key:2` for everything on a key, `mouse` or `key:mouse4` for mouse buttons, `mod:sodium` for one mod,
  `conflict` for keys used twice or `unbound` for free actions – combine them with spaces. The "Key…" button next to
  it shows everything on the next key or mouse button you press. Works from 1.8.9 to 26.3 and also finds the keys of
  other mods.
- **Sign in again without restarting (TRS Client).** When a server says "Invalid session" or you get disconnected,
  the error screen now has "Sign in again": the TRS Launcher gets a fresh session, the game uses it right away and
  connects to the server again – no need to restart the instance. The screen also offers "Connect again", "Switch
  account", "Copy error" and "Server status", and error screens now use the TRS menu style (can be turned off under
  Menu Style → Error screens). Signing in again needs the TRS Launcher to be open.
- **Emote wheel now on R by default.** From Minecraft 1.21.11 on, G opens the new "Quick Actions", so new players get
  the emote wheel on R. If you already use G, nothing changes – you can pick any key in the controls.

### Deutsch

- **Log-Filter, die sich kombinieren lassen.** Im Reiter „Logs“ einer Instanz sind Fehler, Warnungen, Info und Debug
  jetzt Schalter: Schalte beliebig viele an (zum Beispiel nur Fehler und Warnungen). Neue Knöpfe „TRS“ und „Chat“
  zeigen nur Zeilen des TRS Client bzw. Chat-Nachrichten und wirken zusammen mit den Stufen und der Suche. „Alle“
  zeigt wieder alles. Gilt für den Live-Log und für ältere Log-Dateien.
- **Suche in der Minecraft-Tastenbelegung (TRS Client).** Steuerung → Tastenbelegung hat jetzt ein Suchfeld: Tippe
  einen Namen, oder `key:R` / `key:2` für alles auf einer Taste, `maus` oder `key:maus4` für Maustasten, `mod:sodium`
  für einen Mod, `konflikt` für doppelt belegte Tasten oder `unbelegt` für freie Aktionen – mit Leerzeichen
  kombinierbar. Der Knopf „Taste…“ daneben zeigt alles, was auf der nächsten gedrückten Taste oder Maustaste liegt.
  Funktioniert von 1.8.9 bis 26.3 und findet auch die Tasten anderer Mods.
- **Neu anmelden ohne Neustart (TRS Client).** Meldet ein Server „Ungültige Sitzung“ oder wirst du getrennt, hat der
  Fehlerbildschirm jetzt „Neu anmelden“: Der TRS Launcher holt eine frische Sitzung, das Spiel übernimmt sie sofort und
  verbindet wieder mit dem Server – die Instanz muss nicht neu gestartet werden. Dazu gibt es „Erneut verbinden“,
  „Konto wechseln“, „Fehler kopieren“ und „Server-Status“, und Fehlerbildschirme erscheinen im TRS-Menü-Stil
  (abschaltbar unter Menü-Stil → Fehlerbildschirme). Zum Neu-Anmelden muss der TRS Launcher geöffnet sein.
- **Emote-Rad jetzt ab Werk auf R.** Ab Minecraft 1.21.11 öffnet G die neuen „Schnellaktionen“, deshalb bekommen neue
  Spieler das Emote-Rad auf R. Wer schon G nutzt, behält G – die Taste lässt sich in der Steuerung frei wählen.

## 0.10.0 – 2026-09-27 – Toolbox | Werkzeugkasten
<!-- banner: accent=#ff9f1c motif=/news/0.10.0/banner.png -->
<!-- shots:
/news/0.10.0/circuits.png | Circuit library in the TRS Client: ready-made redstone circuits with explanation and materials | Schaltungs-Bibliothek im TRS Client: fertige Redstone-Schaltungen mit Erklärung und Material
/news/0.10.0/circuit-ghost.png | Show a circuit as a template in your world and build it block by block | Schaltung als Vorlage in der Welt einblenden und Block für Block nachbauen
/news/0.10.0/circuits-web.png | All circuits also on the website – download for the structure block | Alle Schaltungen auch auf der Website – Download für den Konstruktionsblock
/news/0.10.0/crash-helper.png | Crash helper: the cause in plain words and a button to fix it | Absturz-Helfer: die Ursache in klaren Worten und ein Knopf zum Beheben
/news/0.10.0/modpack-choice.png | Modpacks: install with or without TRS Client | Modpacks: mit oder ohne TRS Client installieren
/news/0.10.0/tooltips.png | Better tooltips: shulker contents, maps, durability and food | Bessere Tooltips: Shulker-Inhalt, Karten, Haltbarkeit und Essen
/news/0.10.0/waypoint.png | Send waypoints to friends in the chat | Wegpunkte im Chat an Freunde schicken
/news/0.10.0/screenshot-link.png | Share a screenshot as a link, valid for 30 days | Screenshot als Link teilen, 30 Tage gültig
-->

### English

- **With or without TRS Client – your choice for modpacks.** When you install a modpack (Modrinth or CurseForge,
  from Discover or from a file) or import instances with mods from another launcher, the launcher now asks once:
  "With TRS Client" or "Without TRS Client". "With" is recommended – unless the pack already brings mods that
  overlap with it, such as another client (Essential), its own minimap (Xaero's, JourneyMap, VoxelMap, FTB Chunks)
  or its own HUD (MiniHUD). Then you see which ones, and "Without" is preselected. Packs with their own zoom only
  get a hint. If there is no TRS Client for the pack's Minecraft version, the option is greyed out with the reason.
  When importing several instances you choose once for all and can tick exceptions per instance. Your choice is
  saved in the instance and can be changed anytime in its settings. New instances without a pack work as before.
- **New setting "TRS Client for modpacks"** (Settings → Default settings): always ask (default), always with or
  always without – with "always …" the question no longer appears.
- **Crash helper.** When the game crashes – at startup or later – the launcher now reads the crash report and the log
  itself and explains in plain words what happened: which mods are involved (with their icons), and what helps. It
  recognizes mods that clash, missing or wrong dependencies, the same mod installed twice, mods for another Minecraft
  or loader version, too little memory, the wrong Java version, graphics driver crashes (AMD, Intel, NVIDIA, missing
  OpenGL), damaged files and known combinations such as Sodium with OptiFine, Iris without Sodium or two minimaps.
- **Fix it with one click.** Depending on the cause you get buttons like "Disable mod" (reversible), "Install
  dependency", "Remove duplicates", "Increase RAM", "Use Java 21", "Update TRS Client", "Share log" and "Launch again".
  Every change is confirmed first and shows up in the instance history.
- **Earlier crashes stay available.** Open them again from the History tab, or analyze any older log or crash report
  in the Logs tab.
- **Private by design.** The analysis runs only on your PC; nothing is sent unless you share the log. Paths with your
  user name and your player name are hidden in the lines shown.
- Includes a clear answer for the crash of TRS Client 0.9.0 together with Essential: update the TRS Client.
- **Share waypoints with friends.** In the TRS Client you can send a waypoint – or where you are standing – to a
  friend or a group in the social chat. It arrives as a card with name, coordinates, dimension and server. Your
  friends can **take it over** as their own waypoint (when they are on the same server or in the same world) or
  **show** it on the map. In the launcher chat the card has a button to copy the coordinates.
- **Clickable coordinates in the Minecraft chat.** When someone writes coordinates like "x: 100 y: 64 z: -20" or
  "100 64 -20", the TRS Client underlines them – one click saves them as a waypoint. This happens only on your PC,
  nothing is sent anywhere.
- **Share screenshots as a link.** In the launcher's screenshot gallery, in "Clips & pictures" and right after taking
  a screenshot in the game, "Share as link" uploads the picture and copies a link you can post anywhere – Discord
  shows a preview. Links stay online for 30 days and can be deleted earlier under "My shared pictures". Pictures are
  re-encoded without any hidden data, and the page never shows your name. Anyone can report a picture.
- **Better tooltips in the TRS Client.** Hovering over a shulker box shows its contents as a grid in the box's colour,
  filled maps show a small preview, tools and armour show their durability as a number and percent, food shows hunger
  and saturation as drumsticks, and many enchantments are packed into a few lines – hold Shift for the full list.
  Everything can be switched on and off one by one (TRS menu → "Better Tooltips"). Display only. Shulker contents from
  Minecraft 1.11; the better tooltips are not available on 1.7.10 and 1.13.2.
- **Server profiles.** Save a setup for a server – modules on or off, their settings and optionally a HUD profile – and
  the TRS Client switches to it by itself when you join (by address or pattern like "*.hypixel.net", or for
  singleplayer) and back to "Standard" when you leave. "Remember current setup for this server" in TRS menu →
  Profiles → Server profiles saves what you just changed on the server. Profiles stay on this PC.
- **Panorama screenshots.** One key (unbound by default, set it in Controls) or "Take panorama now" in the TRS menu
  records a 360° panorama: six cube images like the title screen panorama (ready for a resource pack) and one 360°
  picture you can view in any panorama viewer. Saved in screenshots/panorama; a message offers to open the folder, and "Share as link" on the
  module page uploads the 360° picture and copies the link.
  The HUD is hidden while recording. From Minecraft 1.8.9 (not on 1.7.10 and 1.13.2).
- **Website sign-in with Microsoft.** The website now only signs in with the Microsoft account that owns Minecraft.
  Confirming a website sign-in with a code from the launcher is gone; the launcher opens the website instead
  (Settings → Privacy, Ctrl+K, the team area).
- **Team roles with permissions.** The team area in the launcher now follows the fine-grained permissions of your
  team role: every team role sees it, but only the areas and buttons it may use – for example supporters see
  reports without their content ("Content only visible with permission") and can only give warnings. Sanctions given
  by someone with a higher rank can't be changed. Roles, applications and positions open on the website.
- **My applications.** Settings → Privacy and Ctrl+K show your team applications with status and the team's answer;
  open ones can be withdrawn. When the status changes you get a notification – in the game from the TRS Client, too.
- **Circuit library in the TRS Client.** A new page in the redstone category with ready-made redstone circuits:
  logic gates, repeater chain, torch tower, three clocks, memory (RS latch, T flip-flops, D latch), pulse circuits,
  a 2×2 piston door and a hidden staircase, farm basics (sugar cane, item filter, automatic furnace, item elevator)
  and displays. Each comes with an explanation, difficulty, size, material list (with what you already have in your
  inventory), the Minecraft version it needs and whether it runs reliably on servers – plus a rotatable 3D preview
  you can view layer by layer. Search, categories and "my version" help you find the right one.
- **Show a circuit as a template in the world.** The template follows your view until you confirm (R rotates,
  Enter confirms); ghost blocks then show what goes where – green when a block is right, red when it is wrong (with
  the reason) and grey while it is missing – with a "32/40 blocks" progress bar, layer by layer with ↑/↓ and H to
  hide it. The template stays in the world or on the server when you reconnect. Display only, like a Litematica
  preview: nothing is built for you and nothing is sent to the server. From Minecraft 1.8.9 (not on 1.7.10 and 1.13.2).
- **The circuits come from the TRS server** – new circuits appear without a mod update. The library checks for
  changes once per start and keeps a local copy for offline play; before the first download it tells you it will
  load with internet.
- **Submit your own circuits.** Mark a circuit in your world (two corners, up to 16×16×16), give it a name,
  category and short description and submit it while signed in with TRS. "My submissions" shows whether it is
  waiting, approved or rejected (with the reason).

### Deutsch

- **Mit oder ohne TRS Client – deine Wahl bei Modpacks.** Wenn du ein Modpack installierst (Modrinth oder
  CurseForge, aus „Entdecken“ oder als Datei) oder Instanzen mit Mods aus einem anderen Launcher importierst, fragt
  der Launcher jetzt einmal: „Mit TRS Client“ oder „Ohne TRS Client“. Empfohlen ist „Mit“ – außer das Pack bringt
  schon Mods mit, die sich damit überschneiden, etwa einen anderen Client (Essential), eine eigene Minimap
  (Xaero's, JourneyMap, VoxelMap, FTB Chunks) oder ein eigenes HUD (MiniHUD). Dann siehst du, welche, und „Ohne“
  ist vorausgewählt. Packs mit eigenem Zoom bekommen nur einen Hinweis. Gibt es für die Minecraft-Version des
  Packs keinen TRS Client, ist die Option mit Begründung ausgegraut. Beim Import mehrerer Instanzen wählst du einmal
  für alle und kannst je Instanz Ausnahmen anhaken. Die Wahl wird in der Instanz gespeichert und lässt sich
  jederzeit in ihren Einstellungen ändern. Neue Instanzen ohne Pack bleiben wie bisher.
- **Neue Einstellung „TRS Client bei Modpacks“** (Einstellungen → Standard-Einstellungen): immer fragen (Standard),
  immer mit oder immer ohne – bei „immer …“ kommt keine Frage mehr.
- **Absturz-Helfer.** Stürzt das Spiel ab – beim Start oder später –, liest der Launcher jetzt selbst Crash-Report und
  Log und erklärt verständlich, was passiert ist: welche Mods beteiligt sind (mit Symbol) und was hilft. Er erkennt
  Mods, die sich in die Quere kommen, fehlende oder falsche Abhängigkeiten, doppelt installierte Mods, Mods für eine
  andere Minecraft- oder Loader-Version, zu wenig Arbeitsspeicher, die falsche Java-Version, Abstürze im Grafiktreiber
  (AMD, Intel, NVIDIA, fehlendes OpenGL), beschädigte Dateien und bekannte Kombinationen wie Sodium mit OptiFine, Iris
  ohne Sodium oder zwei Minimaps.
- **Mit einem Klick beheben.** Je nach Ursache gibt es Knöpfe wie „Mod deaktivieren“ (rückgängig machbar),
  „Abhängigkeit installieren“, „Doppelte entfernen“, „RAM erhöhen“, „Java 21 verwenden“, „TRS Client aktualisieren“,
  „Log teilen“ und „Erneut starten“. Jede Änderung wird vorher bestätigt und landet im Verlauf der Instanz.
- **Frühere Abstürze bleiben abrufbar.** Im Reiter „Verlauf“ lassen sie sich wieder öffnen, im Reiter „Logs“ lässt
  sich jeder ältere Log oder Crash-Report analysieren.
- **Datenschutz inklusive.** Die Analyse läuft nur auf deinem PC; gesendet wird nichts, außer du teilst den Log. In den
  angezeigten Zeilen sind Pfade mit deinem Benutzernamen und dein Spielername ausgeblendet.
- Mit klarer Antwort auf den Absturz von TRS Client 0.9.0 zusammen mit Essential: TRS Client aktualisieren.
- **Wegpunkte mit Freunden teilen.** Im TRS Client schickst du einen Wegpunkt – oder deine aktuelle Position – an
  einen Freund oder eine Gruppe im Sozial-Chat. Er kommt als Karte mit Name, Koordinaten, Dimension und Server an.
  Deine Freunde können ihn als eigenen Wegpunkt **übernehmen** (wenn sie auf demselben Server bzw. in derselben
  Welt sind) oder auf der Karte **anzeigen**. Im Launcher-Chat kopiert ein Knopf die Koordinaten.
- **Anklickbare Koordinaten im Minecraft-Chat.** Schreibt jemand Koordinaten wie „x: 100 y: 64 z: -20“ oder
  „100 64 -20“, unterstreicht der TRS Client sie – ein Klick speichert sie als Wegpunkt. Das passiert nur auf deinem
  PC, es wird nichts gesendet.
- **Screenshots als Link teilen.** In der Screenshot-Galerie des Launchers, in „Clips & Bilder“ und direkt nach einem
  Screenshot im Spiel lädt „Als Link teilen“ das Bild hoch und kopiert einen Link, den du überall posten kannst –
  Discord zeigt eine Vorschau. Links bleiben 30 Tage online und lassen sich unter „Meine geteilten Bilder“ früher
  löschen. Bilder werden ohne versteckte Daten neu gespeichert, und die Seite zeigt nie deinen Namen. Jeder kann ein
  Bild melden.
- **Bessere Tooltips im TRS Client.** Beim Überfahren einer Shulker-Kiste siehst du ihren Inhalt als Raster in der
  Farbe der Kiste, gefüllte Karten zeigen eine kleine Vorschau, Werkzeuge und Rüstung ihre Haltbarkeit als Zahl und
  Prozent, Essen Hunger und Sättigung als Keulen, und viele Verzauberungen passen in wenige Zeilen – Umschalt halten
  zeigt die volle Liste. Alles lässt sich einzeln an- und ausschalten (TRS-Menü → „Bessere Tooltips“). Nur Anzeige.
  Shulker-Inhalt ab Minecraft 1.11; auf 1.7.10 und 1.13.2 gibt es die besseren Tooltips nicht.
- **Server-Profile.** Speichere ein Setup für einen Server – Module an oder aus, ihre Einstellungen und auf Wunsch ein
  HUD-Profil –, und der TRS Client schaltet beim Betreten selbst darauf um (per Adresse oder Muster wie
  „*.hypixel.net“ oder für Einzelspieler) und beim Verlassen zurück auf „Standard“. „Aktuelles Setup für diesen Server
  merken“ unter TRS-Menü → Profile → Server-Profile speichert, was du gerade auf dem Server geändert hast. Die Profile
  bleiben auf diesem PC.
- **Panorama-Screenshots.** Eine Taste (ab Werk unbelegt, in der Steuerung festlegen) oder „Jetzt aufnehmen“ im
  TRS-Menü nimmt ein 360°-Panorama auf: sechs Würfelbilder wie das Panorama des Titelbildschirms (fertig für ein
  Ressourcenpaket) und ein 360°-Bild für jeden Panorama-Betrachter. Gespeichert in screenshots/panorama; eine Meldung
  bietet an, den Ordner zu öffnen, und „Als Link teilen“ auf der Modulseite lädt das 360°-Bild hoch und kopiert den
  Link. Das HUD ist währenddessen ausgeblendet. Ab Minecraft 1.8.9 (nicht auf 1.7.10 und
  1.13.2).
- **Anmeldung auf der Website mit Microsoft.** Die Website meldet nur noch mit dem Microsoft-Konto an, dem Minecraft
  gehört. Die Bestätigung per Code aus dem Launcher entfällt; der Launcher öffnet stattdessen die Website
  (Einstellungen → Datenschutz, Strg+K, Team-Bereich).
- **Team-Rollen mit Rechten.** Der Team-Bereich im Launcher richtet sich jetzt nach den feinen Rechten deiner
  Team-Rolle: Jede Team-Rolle sieht ihn, aber nur die Bereiche und Knöpfe, die sie nutzen darf – Supporter sehen
  zum Beispiel Meldungen ohne Inhalt („Inhalt nur mit Recht sichtbar“) und dürfen nur verwarnen. Strafen von
  jemandem mit höherem Rang lassen sich nicht ändern. Rollen, Bewerbungen und Stellen öffnen sich auf der Website.
- **Meine Bewerbungen.** Einstellungen → Datenschutz und Strg+K zeigen deine Bewerbungen fürs Team mit Status und
  Antwort des Teams; offene kannst du zurückziehen. Ändert sich der Status, bekommst du einen Hinweis – im Spiel auch
  vom TRS Client.
- **Schaltungs-Bibliothek im TRS Client.** Eine neue Seite in der Redstone-Kategorie mit fertigen
  Redstone-Schaltungen: Logik-Gatter, Verstärker-Kette, Fackelturm, drei Takte, Speicher (RS-Latch, T-Flipflops,
  D-Latch), Impuls-Schaltungen, eine 2×2-Kolbentür und eine versteckte Treppe, Farm-Grundlagen (Zuckerrohr,
  Item-Filter, automatischer Ofen, Item-Aufzug) und Anzeigen. Zu jeder gibt es eine Erklärung, Schwierigkeit, Größe,
  Materialliste (mit dem, was du schon im Inventar hast), die nötige Minecraft-Version und ob sie auf Servern
  verlässlich läuft – dazu eine drehbare 3D-Vorschau, auch Schicht für Schicht. Suche, Kategorien und „Meine
  Version“ helfen beim Finden.
- **Schaltung als Vorlage in der Welt einblenden.** Die Vorlage folgt deinem Blick, bis du bestätigst (R dreht,
  Enter bestätigt); danach zeigen Geisterblöcke, was wohin gehört – grün, wenn ein Block stimmt, rot, wenn er falsch
  ist (mit Grund), und grau, solange er fehlt – mit Fortschritt „32/40 Blöcke“, Schicht für Schicht mit ↑/↓ und H zum
  Ausblenden. Die Vorlage bleibt in der Welt bzw. auf dem Server, auch nach dem Neuverbinden. Nur Anzeige wie eine
  Litematica-Vorschau: Nichts wird für dich gebaut und nichts an den Server geschickt. Ab Minecraft 1.8.9 (nicht auf
  1.7.10 und 1.13.2).
- **Die Schaltungen kommen vom TRS-Server** – neue Schaltungen gibt es ohne Mod-Update. Die Bibliothek prüft bei jedem
  Start kurz auf Änderungen und behält eine lokale Kopie für unterwegs ohne Internet; vor dem ersten Download sagt sie,
  dass sie beim nächsten Start mit Internet geladen wird.
- **Eigene Schaltungen einreichen.** Markiere eine Schaltung in deiner Welt (zwei Ecken, höchstens 16×16×16), gib
  Name, Kategorie und eine kurze Beschreibung an und reiche sie mit TRS-Anmeldung ein. „Meine Einreichungen“ zeigt,
  ob sie noch geprüft wird, angenommen oder abgelehnt wurde (mit Grund).

## 0.9.0 – 2026-09-27 – Pack Up | Einpacken & los
<!-- banner: accent=#ff4d5e motif=/news/0.9.0/banner.png -->
<!-- shots:
/news/0.9.0/share-mods.png | Hosting: choose which mods your friends take along | Hosting: auswählen, welche Mods deine Freunde mitnehmen
/news/0.9.0/join-with-mods.png | Joining a world with mods: new instance or a copy, with a clear warning for files from the host | Beitreten mit Mods: neue Instanz oder Kopie, mit klarer Warnung bei Dateien vom Host
/news/0.9.0/resource-pack.png | The host's resource pack comes straight over the hosting connection | Das Resource Pack des Hosts kommt direkt über die Hosting-Verbindung
/news/0.9.0/ping.png | Honest ping display with jitter, history and server TPS | Ehrliche Ping-Anzeige mit Jitter, Verlauf und Server-TPS
/news/0.9.0/ping-test.png | Ping test sorts your server list | Der Ping-Test sortiert deine Serverliste
/news/0.9.0/chat.png | Chat with timestamps, stacking and mentions | Chat mit Zeitstempeln, Stapeln und Erwähnungen
/news/0.9.0/pvp-hud.png | Counter HUD, hitmarker and warnings | Zähler-HUD, Hitmarker und Warnungen
/news/0.9.0/streamer.png | Streamer mode and Auto-GG | Streamer-Modus und Auto-GG
-->

### English

- **TRS Client 0.9.1: crash fix.** With some mod combinations (for example together with Essential) Minecraft crashed
  right at startup with TRS Client 0.9.0. The TRS Client now hooks into world hosting in a way that no longer clashes with other
  mods; hosting works exactly as before. The client updates itself on the next game start.
- **TRS Client: quieter public-link hint.** The red "Public link active" badge no longer sits on top of the game. While
  the public link is on, a small, subtle hint in the pause menu (and in the hosting window) reminds you of it.
- **TRS Client: an honest ping display.** The ping HUD now shows the real round-trip time to the server, the jitter
  (how much it varies) and a small history graph; optionally the server's TPS (an estimate from the server's time
  updates), timeouts and a warning on ping spikes (off by default). From Minecraft 1.20.2 the TRS Client measures the ping
  itself with one ping request every 2 seconds (adjustable, never more than one per second – the same request the F3
  network graph sends, just much less often). Older versions don't allow that without faking packets, so there the
  display shows the value the server reports in the player list and marks it "server". Nothing here makes your
  connection faster – it shows what it really is.
- **TRS Client: ping test in the server list.** A "Ping test" button in the multiplayer menu measures all your servers
  (like Minecraft's own server list ping, at most four at a time, once every 10 seconds) and sorts the list by ping;
  pinned servers stay on top. In 1.8.9–1.12.2 and 1.14.4–26.3 wherever the TRS menu style is on.
- **TRS Client: Network Optimization (Performance, on by default).** Incoming data is processed faster on your PC: the
  decryption of online-mode servers runs about 2 to 4.5 times faster (measured per packet, same result bit for bit), and
  compression no longer creates new arrays for every packet (up to about 87 KB less garbage per chunk packet – the time
  itself stays about the same). On 1.7.10 it also switches on TCP_NODELAY, which Minecraft 1.7.10 leaves off (from 1.8
  Minecraft does it itself). Honestly: this cannot lower your ping – the time packets travel through the internet stays
  the same, and nothing the server receives changes. No packets are held back, bundled or faked.
- **TRS Client: Low Input Latency (Performance, off by default).** Reads your mouse right before the frame and can
  limit how many frames wait in the graphics card queue. The biggest effect we measured is on 1.8.9–1.12.2 with an FPS
  limit: the mouse movement is used about 15 ms fresher at 60 FPS. On modern versions Minecraft already reads the mouse
  late, and in our tests the graphics card queue was empty – there it brings well under a millisecond, and "Maximum"
  costs a few percent FPS. The module page shows the measured values live, so you can check on your PC.
- **Launcher: ping test for servers.** The server page can sort by ping and measures at most four servers at a time;
  the Worlds tab of an instance has a "Ping test" for every server in its server list.
- **World hosting: ping per player.** The host's player list shows each player's ping next to "Direct" or "Via relay",
  and a guest's ping HUD shows whether the connection runs directly or through the relay.
- **World hosting with mods and a resource pack.** When you host a world in the TRS Client you can now share your mods
  and a resource pack with your guests – both are off by default and remembered per world. Pick which mods go along and
  whether each is required or optional; mods that add blocks or items are preselected as required, pure client mods are
  not selected. The TRS Launcher recognizes your mods on Modrinth and CurseForge (without the launcher, Modrinth only).
  Mods that are in no store can be sent directly from you if you turn that on – otherwise your guests only see "get it
  yourself". The resource pack goes straight to your guests (never through TRS) and they get Minecraft's usual question
  whether to use it (Minecraft 1.20.3 and newer).
- **Launcher: joining a world with mods.** Every time you join such a world, the launcher shows its mods – required or
  optional, from Modrinth, CurseForge or directly from the host, size, present or missing – and lets you create a new
  instance, add the mods to a copy of an existing one (your original stays untouched) or join without mods if nothing
  required is missing. Store mods are downloaded from their official source and checked. Mods directly from the host
  come with a clear warning on every join and are only installed after you tick "I trust this host". In the game,
  "Open in launcher" takes you straight to this dialog. Worlds with mods are marked in Social → Worlds and on world
  cards.
- **TRS Client: better chat.** Timestamps now also in 12-hour format, identical messages stack ("(x3)"), the chat
  keeps up to 1000 lines instead of 100 (adjustable), and you copy a line with Ctrl+click, right-click or both.
  **Mentions** highlight lines with your name or your own keywords, optionally with a sound (your own messages don't
  count), and the **Chat Filter** hides messages with words you choose – only on your screen.
- **TRS Client: Auto Reconnect.** When the connection drops or the server restarts, the disconnect screen reconnects
  after a countdown (adjustable, with a cancel button and a limited number of attempts). Never after a ban, a whitelist
  kick, a login from another place or a wrong game version.
- **TRS Client: Queue & Alerts.** Tells you when you are (almost) through a server queue – known queue plugins work out
  of the box, you can add your own patterns. While the game is in the background it also tells you when someone mentions
  you, when you die or get disconnected: a notice, an optional sound and a flashing taskbar (from Minecraft 1.13).
- **TRS Client: scoreboard, tab list, boss bar and titles.** Move and resize the scoreboard in the HUD editor, change its
  background and hide the red numbers; the tab list can show the ping in milliseconds (coloured if you like); the boss
  bar and the big title texts can be moved and resized too (titles on Minecraft before 1.20.5 only resized).
- **TRS Client: Warnings.** Short, subtle notices when armour or a tool is almost broken, when you are hungry or low on
  health and when your inventory is full – with an optional sound and a cooldown. After dying, an arrow and the distance
  show you the way back to your death point.
- **TRS Client: Item Counter.** Arrows, totems, healing and splash potions, golden apples, ender pearls and blocks in
  your inventory at a glance, with icons. It also counts how many totems each opponent popped (from Minecraft 1.11).
- **TRS Client: Hit Feedback.** A hit marker at the crosshair when your hit lands, more critical and sharpness
  particles and an optional hit sound. Display only – your attacks stay exactly the same.
- **TRS Client: Auto-GG reworked.** Still off by default. It now sends exactly one message per round end, 0.5 to
  2 seconds later and at most every 10 seconds, and it knows the end-of-round messages of Hypixel, Minemen and PvP.Land
  (your own patterns still work).
- **TRS Client: Streamer Mode.** Replaces your name – and if you like the names of other players – in chat, tab list,
  name tags and scoreboard with a name you choose, and hides server addresses (server list and server HUD). Toggle it
  with a key of your choice. Everything new is only display or comfort and allowed on servers like Hypixel; the only
  thing the client sends by itself is Auto-GG, which stays off until you switch it on.

### Deutsch

- **TRS Client 0.9.1: Absturz behoben.** Mit manchen Mod-Kombinationen (zum Beispiel zusammen mit Essential) stürzte
  Minecraft mit 0.9.0 direkt beim Start ab. Der TRS Client hängt sich jetzt so ins Welt-Hosting ein, dass es nicht mehr
  mit anderen Mods kollidiert; Hosting funktioniert genau wie vorher. Der Client aktualisiert sich beim nächsten
  Spielstart von selbst.
- **TRS Client: dezenterer Hinweis zum öffentlichen Link.** Das rote Abzeichen „Öffentlicher Link aktiv“ liegt nicht
  mehr über dem Spiel. Solange der öffentliche Link an ist, erinnert ein kleiner, unauffälliger Hinweis im Pausemenü
  (und im Hosting-Fenster) daran.
- **TRS Client: ehrliche Ping-Anzeige.** Das Ping-HUD zeigt jetzt die echte Hin- und Rücklaufzeit zum Server, den
  Jitter (wie stark er schwankt) und einen kleinen Verlauf; auf Wunsch auch die TPS des Servers (geschätzt aus seinen
  Zeit-Meldungen), Zeitüberschreitungen und eine Warnung bei Ping-Spitzen (standardmäßig aus). Ab Minecraft 1.20.2 misst
  der TRS Client selbst – mit einer Ping-Anfrage alle 2 Sekunden (einstellbar, nie öfter als einmal pro Sekunde; dieselbe
  Anfrage, die die F3-Netzwerkgrafik schickt, nur viel seltener). Ältere Versionen erlauben das nicht, ohne Pakete zu
  fälschen – dort zeigt die Anzeige den Wert, den der Server in der Spielerliste meldet, und kennzeichnet ihn mit
  „Server“. Schneller wird deine Verbindung dadurch nicht – du siehst, wie sie wirklich ist.
- **TRS Client: Ping-Test in der Serverliste.** Ein Knopf „Ping-Test“ im Mehrspieler-Menü misst alle deine Server (wie
  Minecrafts eigener Serverlisten-Ping, höchstens vier gleichzeitig, einmal alle 10 Sekunden) und sortiert die Liste
  nach Ping; angeheftete Server bleiben oben. In 1.8.9–1.12.2 und 1.14.4–26.3, überall dort, wo der TRS-Menüstil an ist.
- **TRS Client: Netzwerk-Optimierung (Leistung, standardmäßig an).** Ankommende Daten werden auf deinem PC schneller
  verarbeitet: Die Entschlüsselung bei Servern im Online-Modus läuft etwa 2- bis 4,5-mal so schnell (je Paket gemessen,
  Bit für Bit dasselbe Ergebnis), und die Kompression legt nicht mehr für jedes Paket neue Felder an (bis zu etwa 87 KB
  weniger Speichermüll je Chunk-Paket – die Zeit selbst bleibt etwa gleich). Unter 1.7.10 schaltet sie außerdem
  TCP_NODELAY ein, das Minecraft 1.7.10 auslässt (ab 1.8 macht Minecraft das selbst). Ehrlich gesagt: Deinen Ping senkt
  das nicht – die Laufzeit der Pakete durchs Internet bleibt gleich, und am Server kommt genau dasselbe an. Es werden
  keine Pakete zurückgehalten, gebündelt oder gefälscht.
- **TRS Client: Niedrige Eingabe-Verzögerung (Leistung, standardmäßig aus).** Liest deine Maus direkt vor dem Bild und
  kann begrenzen, wie viele Bilder in der Warteschlange der Grafikkarte warten. Den größten Effekt haben wir unter
  1.8.9–1.12.2 mit FPS-Grenze gemessen: Bei 60 FPS wird die Mausbewegung etwa 15 ms frischer verwendet. In neuen Versionen
  liest Minecraft die Maus schon spät, und in unseren Tests war die Warteschlange der Grafikkarte leer – dort bringt es
  deutlich unter einer Millisekunde, und „Maximal“ kostet ein paar Prozent FPS. Die Modulseite zeigt die Messwerte live,
  so kannst du es auf deinem PC nachprüfen.
- **Launcher: Ping-Test für Server.** Die Server-Seite kann nach Ping sortieren und misst höchstens vier Server
  gleichzeitig; im Welten-Reiter einer Instanz gibt es einen „Ping-Test“ für jeden Server ihrer Serverliste.
- **Welt-Hosting: Ping je Spieler.** Die Spielerliste des Hosts zeigt neben „Direkt“ bzw. „Über Relay“ den Ping jedes
  Spielers, und das Ping-HUD eines Gastes zeigt, ob die Verbindung direkt oder über das Relay läuft.
- **Welt-Hosting mit Mods und Resource Pack.** Wenn du im TRS Client eine Welt hostest, kannst du jetzt deine Mods und
  ein Resource Pack mit deinen Gästen teilen – beides ist ab Werk aus und wird je Welt gemerkt. Du wählst, welche Mods
  mitgehen und ob sie Pflicht oder optional sind; Mods mit neuen Blöcken oder Items sind als Pflicht vorausgewählt, reine
  Client-Mods nicht. Der TRS Launcher erkennt deine Mods bei Modrinth und CurseForge (ohne Launcher nur Modrinth). Mods,
  die es in keinem Store gibt, kannst du auf Wunsch direkt übertragen – sonst sehen deine Gäste nur „selbst besorgen“.
  Das Resource Pack geht direkt an deine Gäste (nie über TRS), und sie bekommen die normale Minecraft-Frage, ob sie es
  verwenden wollen (ab Minecraft 1.20.3).
- **Launcher: Welten mit Mods beitreten.** Bei jedem Beitritt zu so einer Welt zeigt der Launcher ihre Mods – Pflicht
  oder optional, von Modrinth, CurseForge oder direkt vom Host, Größe, vorhanden oder fehlend – und du legst eine neue
  Instanz an, ergänzt eine Kopie einer vorhandenen (das Original bleibt unverändert) oder trittst ohne Mods bei, wenn
  nichts Pflicht fehlt. Store-Mods lädt er aus der offiziellen Quelle und prüft sie. Mods direkt vom Host kommen bei
  jedem Beitritt mit einer deutlichen Warnung und werden erst übernommen, wenn du „Ich vertraue diesem Host“ ankreuzt.
  Im Spiel führt „Im Launcher öffnen“ direkt zu diesem Dialog. Welten mit Mods sind unter Sozial → Welten und auf
  Weltkarten markiert.
- **TRS Client: besserer Chat.** Zeitstempel jetzt auch im 12-Stunden-Format, gleiche Nachrichten werden gestapelt
  („(x3)“), der Chat behält bis zu 1000 statt 100 Zeilen (einstellbar), und eine Zeile kopierst du mit Strg+Klick,
  Rechtsklick oder beidem. **Erwähnungen** heben Zeilen mit deinem Namen oder eigenen Stichwörtern hervor, auf Wunsch
  mit Ton (deine eigenen Nachrichten zählen nicht), und der **Chat-Filter** blendet Nachrichten mit Wörtern deiner Wahl
  aus – nur auf deinem Bildschirm.
- **TRS Client: Auto-Reconnect.** Bricht die Verbindung ab oder startet der Server neu, verbindet der „Verbindung
  getrennt“-Bildschirm nach einem Countdown neu (einstellbar, mit „Abbrechen“ und begrenzten Versuchen). Nie nach einem
  Bann, einem Whitelist-Kick, einer Anmeldung von woanders oder einer falschen Spielversion.
- **TRS Client: Warteschlange & Hinweise.** Sagt dir, wenn du in einer Server-Warteschlange (fast) dran bist – bekannte
  Warteschlangen-Plugins klappen sofort, eigene Muster kannst du ergänzen. Ist das Spiel im Hintergrund, meldet es auch,
  wenn dich jemand erwähnt, du stirbst oder die Verbindung getrennt wird: Hinweis, auf Wunsch Ton und blinkende
  Taskleiste (ab Minecraft 1.13).
- **TRS Client: Scoreboard, Tabliste, Bossleiste und Titel.** Scoreboard im HUD-Editor verschieben und skalieren,
  Hintergrund einstellen und die roten Zahlen ausblenden; die Tabliste zeigt den Ping auf Wunsch in Millisekunden (auch
  farbig); Bossleiste und die großen Titeltexte lassen sich ebenfalls verschieben und skalieren (Titel vor Minecraft
  1.20.5 nur in der Größe).
- **TRS Client: Warnungen.** Kurze, dezente Hinweise, wenn Rüstung oder Werkzeug fast kaputt sind, du Hunger oder wenig
  Leben hast und das Inventar voll ist – auf Wunsch mit Ton und Abklingzeit. Nach dem Tod zeigen ein Pfeil und die
  Entfernung den Weg zurück zum Todespunkt.
- **TRS Client: Zähler.** Pfeile, Totems, Heil- und Wurftränke, Goldäpfel, Enderperlen und Blöcke im Inventar auf einen
  Blick, mit Symbolen. Dazu zählt es, wie viele Totems jeder Gegner verbraucht hat (ab Minecraft 1.11).
- **TRS Client: Treffer-Feedback.** Ein Hitmarker am Fadenkreuz, wenn dein Schlag trifft, mehr Kritisch- und
  Schärfe-Partikel und auf Wunsch ein Treffer-Ton. Nur Anzeige – deine Angriffe bleiben genau gleich.
- **TRS Client: Auto-GG überarbeitet.** Weiterhin ab Werk aus. Es sendet jetzt genau eine Nachricht je Rundenende,
  0,5 bis 2 Sekunden danach und höchstens alle 10 Sekunden, und kennt die Rundenende-Meldungen von Hypixel, Minemen und
  PvP.Land (eigene Muster gehen weiterhin).
- **TRS Client: Streamer-Modus.** Ersetzt deinen Namen – und auf Wunsch die Namen anderer Spieler – in Chat, Tabliste,
  Namensschildern und Scoreboard durch einen Namen deiner Wahl und verbirgt Server-Adressen (Serverliste und
  Server-HUD). Umschalten per Taste deiner Wahl. Alles Neue ist nur Anzeige oder Komfort und auf Servern wie Hypixel
  erlaubt; das Einzige, was der Client selbst sendet, ist Auto-GG – und das bleibt aus, bis du es einschaltest.

## 0.8.0 – 2026-09-26 – Open House | Tag der offenen Tür
<!-- banner: accent=#3ecfcf motif=/news/0.8.0/banner.png -->
<!-- shots:
/news/0.8.0/worlds.png | Social → Worlds: join your friends' worlds, ask to join or enter a code | Sozial → Welten: Welten von Freunden beitreten, anfragen oder per Code
/news/0.8.0/host-world.png | Host your singleplayer world for friends – no port forwarding | Einzelspielerwelt für Freunde hosten – ohne Portfreigabe
/news/0.8.0/host-manage.png | Manage players, rights and join requests while you host | Spieler, Rechte und Beitrittsanfragen beim Hosten verwalten
/news/0.8.0/join-code.png | Join with a code in the TRS Client | Mit Code beitreten im TRS Client
/news/0.8.0/minimap-indoors.png | The minimap looks inside buildings, barriers are ignored | Die Minimap schaut in Gebäude, Barrieren werden ignoriert
/news/0.8.0/shield.png | Shield Position with live preview | Schild-Position mit Live-Vorschau
/news/0.8.0/sanctions.png | See your sanctions and appeal once | Eigene Strafen sehen und einmal Einspruch einlegen
-->

### English

- **TRS Client: the rubber duck.** A secret head cosmetic: a yellow rubber duck that sits on your head in the TRS
  Client – and everyone else with the TRS Client sees it too. It waddles when you walk, bobs when you stand, looks
  around and blinks, lags a little behind fast head turns, flaps its wings when you jump or fall, squashes on landing and
  quacks when you start sneaking or play an emote. You only get it with a code; without one it doesn't show up anywhere.
  Redeem the code in the launcher (Skins → TRS capes → Redeem code) and put the duck on straight away, or later under
  "Head cosmetics". Works in Minecraft 1.8.9–1.12.2 (Forge) and 1.14.4–26.3 (Fabric, Forge, NeoForge); a new switch
  under TRS Online hides TRS head cosmetics if you prefer.
- **TRS Client: Shield Position.** A new module in the PvP category holds your shield further to the side and lower in
  first person, so you see more of the fight. While blocking it switches to its own flatter pose – smoothly, without
  the old jump. Pick a preset ("Side", "Low", "Vanilla") or set position, rotation and size yourself for normal and for
  blocking; a live preview on the settings page shows how much of the screen the shield covers. If you like, the shield
  turns see-through while you block (opacity adjustable). Off by default; works with shields from other mods too, and
  third person stays unchanged. Available from Minecraft 1.10.2 (Forge) and 1.14.4 (Fabric) up to 26.3.
- **Join your friends' worlds from the launcher.** When a friend opens their singleplayer world in the TRS Client, you
  see it live under Social → Worlds with version, game mode and player count. Invited? Press "Join". Not invited? Press
  "Ask to join" (or enter a code like K7Q-M2X) – the launcher says "Request sent" and, as soon as the host lets you in,
  starts the game by itself. It picks the instance with the same Minecraft version and mod loader (you choose if there
  are several) and, if none fits, creates one with the TRS Client in one click. The game then joins the world on its
  own – no address to type, no port forwarding. If the game is already running, it joins right away.
- **World cards and notifications.** A world shared in chat shows up as a card ("Bob's world – 1.21.11 Fabric – Join")
  with its live state, and invites pop up as a notification with "Join" and "Decline". You also get a short note when a
  host lets you in, declines, removes you or closes the world.
- **Your own world at a glance.** While you host a world in the game, Social → Worlds shows its join code, how many
  players are in it and who is waiting – manage it in the game (pause menu → Host world).
- **TRS Client: host your singleplayer world for friends.** In the pause menu, "Host world" opens your world for up to
  10 players – no port forwarding. Pick a name, game mode, cheats, PvP, the maximum number of players and who can see it;
  the world is backed up first. You get a join code to copy, can invite friends (online friends first), accept or decline
  requests (also from a notification) and see every player with their connection ("Direct" or "Via relay"). Give players
  OP, make them spectators or take away building, remove or ban them (also for all your worlds), and stop hosting with one
  click. Friends connect directly when possible and through the TRS relay otherwise.
- **TRS Client: join friends' worlds.** Invites pop up with "Join" (quick-reply key), world cards in chat have a "Join"
  button, Social → Worlds lists your friends' open worlds with "Join" or "Ask to join", and the multiplayer screen has
  "Join with code". A different Minecraft version is shown clearly before you try.
- **TRS Client: public link (optional).** For friends without the TRS Client you can turn on a public link via the e4mc
  service. It is off by default, needs a confirmed warning every single time and shows a red "Public link active" badge
  in the game and the pause menu with a one-click "Deactivate".
- **TRS Client: the maps look inside buildings.** Under a roof or ceiling the minimap now shows the inside instead of
  the roof: floors, rooms, halls and lobbies – only blocks up to just below the roof (at most about 10 blocks above
  your head) count. Outdoors and under trees nothing changes, in caves the cave view stays in charge. The world map
  follows the minimap's level (the button at the top switches back to the surface). New setting "Hide roofs" in the
  Minimap module (on by default); like the cave view it is off on servers that ask for Fair Play.
- **TRS Client: barriers no longer get in the way of the maps.** Barrier blocks, light blocks and structure voids are
  treated like air – a lobby with an invisible barrier ceiling or barrier floors in empty worlds no longer leave dark
  gaps, the map shows what is below.
- **No double notifications while you play.** While a game with the TRS Client is running, the launcher stays quiet
  about messages, friend requests, friends coming online, invites and cape offers – no pop-up, no sound, no Windows
  notification – because the TRS Client already shows them in the game. They still count as unread. As soon as the
  game closes, the launcher notifies you again. Games without the TRS Client, update notes and errors are unchanged.
- **Moderation:** the team now has moderators, clearer sanctions (chat mute, social, upload and world-hosting bans,
  temporary account bans) and you can see your own sanctions and appeal each one once – even while banned. Settings →
  Privacy → "My sanctions" (and a notice at the top while one is active) shows the kind, what it blocks, when it ends
  and why; blocked actions now explain themselves instead of showing an error, and new sanctions or answers to your
  appeal arrive as a notification right away. The same works in the TRS Client under Social.
- **New team area for admins and moderators:** an overview of what needs attention, reports with filters and priority,
  appeals, a player file with the full sanction history (lift, shorten or extend with a reason), new sanctions with
  kind, duration and reason templates plus a confirmation step, capes and cosmetics, open worlds, codes, word filter,
  roles and the audit log – with global search, multi-select for bulk actions and keyboard shortcuts (/, j/k, a/r, ?).

### Deutsch

- **TRS Client: die Quietscheente.** Eine geheime Kopf-Kosmetik: eine gelbe Quietscheente, die im TRS Client auf deinem
  Kopf sitzt – und alle anderen mit TRS Client sehen sie auch. Sie watschelt beim Laufen, wippt im Stand, schaut sich um
  und blinzelt, hängt bei schnellen Kopfdrehungen etwas hinterher, schlägt beim Springen und Fallen mit den Flügeln,
  staucht sich bei der Landung und quakt, wenn du zu schleichen beginnst oder ein Emote spielst. Es gibt sie nur per
  Code; ohne Code taucht sie nirgends auf. Löse den Code im Launcher ein (Skins → TRS-Umhänge → Code einlösen) und setz
  die Ente gleich auf – oder später unter „Kopf-Kosmetik“. Läuft in Minecraft 1.8.9–1.12.2 (Forge) und 1.14.4–26.3
  (Fabric, Forge, NeoForge); ein neuer Schalter unter TRS Online blendet TRS-Kopf-Kosmetik aus, wenn du willst.
- **TRS Client: Schild-Position.** Ein neues Modul in der Kategorie PvP hält dein Schild in der 1. Person weiter
  seitlich und tiefer, damit du vom Kampf mehr siehst. Beim Blocken wechselt es in eine eigene, flachere Haltung – weich,
  ohne den alten Sprung. Wähle eine Vorlage („Seitlich“, „Tief“, „Vanilla“) oder stelle Position, Drehung und Größe für
  normal und fürs Blocken selbst ein; eine Live-Vorschau auf der Einstellungsseite zeigt, wie viel vom Bild das Schild
  verdeckt. Auf Wunsch wird das Schild beim Blocken durchsichtig (Deckkraft einstellbar). Ab Werk aus; klappt auch mit
  Schilden anderer Mods, die 3. Person bleibt unverändert. Verfügbar ab Minecraft 1.10.2 (Forge) bzw. 1.14.4 (Fabric)
  bis 26.3.
- **Den Welten deiner Freunde aus dem Launcher beitreten.** Öffnet ein Freund seine Einzelspielerwelt im TRS Client,
  siehst du sie live unter Sozial → Welten mit Version, Spielmodus und Spielerzahl. Eingeladen? „Beitreten“ drücken.
  Nicht eingeladen? „Anfragen“ (oder einen Code wie K7Q-M2X eingeben) – der Launcher meldet „Anfrage gesendet“ und
  startet das Spiel von selbst, sobald der Host dich hereinlässt. Er nimmt die Instanz mit gleicher Minecraft-Version
  und gleichem Mod-Loader (bei mehreren wählst du) und legt, wenn keine passt, mit einem Klick eine mit TRS Client an.
  Das Spiel tritt der Welt dann selbst bei – ohne Adresse eintippen, ohne Portfreigabe. Läuft das Spiel schon, tritt es
  sofort bei.
- **Weltkarten und Benachrichtigungen.** Eine im Chat geteilte Welt erscheint als Karte („Welt von Bob – 1.21.11
  Fabric – Beitreten“) mit aktuellem Stand, und Einladungen kommen als Benachrichtigung mit „Beitreten“ und „Ablehnen“.
  Außerdem bekommst du einen kurzen Hinweis, wenn der Host dich hereinlässt, ablehnt, entfernt oder die Welt schließt.
- **Deine eigene Welt im Blick.** Während du im Spiel eine Welt hostest, zeigt Sozial → Welten ihren Beitrittscode,
  wie viele Spieler drin sind und wer wartet – verwaltet wird sie im Spiel (Pausemenü → Welt hosten).
- **TRS Client: Einzelspielerwelt für Freunde hosten.** „Welt hosten“ im Pausemenü öffnet deine Welt für bis zu
  10 Spieler – ohne Portfreigabe. Name, Spielmodus, Cheats, PvP, maximale Spielerzahl und Sichtbarkeit wählst du selbst,
  vorher wird die Welt gesichert. Du bekommst einen Beitrittscode zum Kopieren, lädst Freunde ein (Online-Freunde zuerst),
  nimmst Anfragen an oder lehnst sie ab (auch über eine Benachrichtigung) und siehst jeden Spieler mit seiner Verbindung
  („Direkt“ oder „Über Relay“). Gib Spielern OP, mach sie zu Zuschauern oder nimm ihnen das Bauen, entferne oder sperre
  sie (auch für alle deine Welten) und beende das Hosting mit einem Klick. Freunde verbinden sich direkt, wenn es geht,
  sonst über das TRS-Relay.
- **TRS Client: Welten von Freunden beitreten.** Einladungen erscheinen mit „Beitreten“ (Schnellantwort-Taste),
  Weltkarten im Chat haben einen „Beitreten“-Knopf, Sozial → Welten zeigt die offenen Welten deiner Freunde mit
  „Beitreten“ oder „Anfragen“, und im Mehrspieler-Menü gibt es „Mit Code beitreten“. Eine andere Minecraft-Version wird
  vorher klar angezeigt.
- **TRS Client: öffentlicher Link (optional).** Für Freunde ohne TRS Client kannst du über den Dienst e4mc einen
  öffentlichen Link einschalten. Ab Werk ist er aus, braucht jedes Mal eine bestätigte Warnung und zeigt im Spiel und im
  Pausemenü ein rotes Abzeichen „Öffentlicher Link aktiv“ mit „Deaktivieren“ per Klick.
- **TRS Client: Die Karten schauen in Gebäude hinein.** Unter einem Dach oder einer Decke zeigt die Minimap jetzt das
  Innere statt des Dachs: Böden, Räume, Hallen und Lobbys – es zählen nur Blöcke bis knapp unter dem Dach (höchstens
  etwa 10 Blöcke über deinem Kopf). Im Freien und unter Bäumen bleibt alles wie bisher, in Höhlen bleibt die
  Höhlenansicht zuständig. Die Weltkarte folgt der Ebene der Minimap (der Knopf oben schaltet zurück zur Oberfläche).
  Neue Einstellung „Dach ausblenden“ im Minimap-Modul (standardmäßig an); wie die Höhlenansicht ist sie auf Servern
  aus, die Fair Play verlangen.
- **TRS Client: Barrieren stören die Karten nicht mehr.** Barriere-Blöcke, Licht-Blöcke und Strukturleeren zählen wie
  Luft – eine Lobby mit unsichtbarer Barriere-Decke oder Barriere-Böden in leeren Welten hinterlassen keine dunklen
  Lücken mehr, die Karte zeigt, was darunter liegt.

- **Keine doppelten Benachrichtigungen beim Spielen.** Läuft ein Spiel mit TRS Client, schweigt der Launcher zu
  Nachrichten, Freundesanfragen, Freunden, die online kommen, Einladungen und Umhang-Angeboten – kein Hinweis, kein
  Ton, keine Windows-Benachrichtigung –, denn der TRS Client zeigt sie schon im Spiel. Sie zählen weiter als ungelesen.
  Sobald das Spiel zu ist, meldet sich der Launcher wieder. Spiele ohne TRS Client, Update-Hinweise und Fehler bleiben
  wie bisher.
- **Moderation:** Das Team hat jetzt Moderatoren und klarere Strafen (Chat-Stumm, Sozial-, Upload- und
  Welt-Hosting-Sperre, befristeter Konto-Bann). Du siehst deine eigenen Strafen und kannst gegen jede einmal Einspruch
  einlegen – auch wenn dein Konto gesperrt ist. Einstellungen → Datenschutz → „Meine Strafen“ (und ein Hinweis oben,
  solange eine aktiv ist) zeigt Art, was gesperrt ist, wann sie endet und warum; gesperrte Aktionen erklären sich jetzt
  selbst statt einen Fehler zu zeigen, und neue Strafen oder Antworten auf deinen Einspruch kommen sofort als
  Benachrichtigung. Im TRS Client geht das genauso unter Sozial.
- **Neuer Team-Bereich für Admins und Moderatoren:** Übersicht über alles, was Aufmerksamkeit braucht, Meldungen mit
  Filtern und Priorität, Einsprüche, Spieler-Akte mit dem ganzen Strafverlauf (aufheben, verkürzen oder verlängern mit
  Begründung), neue Strafen mit Art, Dauer, Grund-Vorlagen und Bestätigung, Umhänge und Kosmetik, offene Welten, Codes,
  Wortfilter, Rollen und Audit-Log – mit globaler Suche, Mehrfachauswahl für Sammelaktionen und Tastenkürzeln
  (/, j/k, a/r, ?).

## 0.7.0 – 2026-09-26 – The Together Update | Das Zusammen-Update

<!-- banner: accent=#ff9f3d motif=/news/0.7.0/banner.png -->
<!-- shots:
/news/0.7.0/chat.png | Social: chat with friends, groups, pictures and server invites | Sozial: Chat mit Freunden, Gruppen, Bildern und Server-Einladungen
/news/0.7.0/friends.png | Friends, requests and blocked players side by side | Freunde, Anfragen und Blockierte nebeneinander
/news/0.7.0/notifications.png | Notifications for messages, requests and friends coming online | Benachrichtigungen für Nachrichten, Anfragen und Freunde, die online kommen
/news/0.7.0/ingame-chat.png | Chat right inside the game with the TRS Client | Chat direkt im Spiel mit dem TRS Client
/news/0.7.0/ingame-quick-reply.png | Quick reply without leaving the game | Schnellantwort, ohne das Spiel zu verlassen
/news/0.7.0/clip-trim.png | New clip player: play, trim and share clips | Neuer Clip-Player: Clips abspielen, zuschneiden und teilen
/news/0.7.0/news-dialog.png | Update news with banner and screenshots | Update-News mit Banner und Screenshots
-->

### English

- **TRS Client: chat with your friends in the game.** The Friends screen is now "Social" with the tabs Chat and
  Friends (title screen, pause menu, TRS menu, or its own key). Write to friends and groups, reply to a message, copy,
  edit or delete your own, mark as unread, react, and see when someone is typing or has read your message – all live,
  without reloading. Send up to 10 screenshots of this instance at once ("Select pictures"), open pictures large, and
  invite friends to the server you're on: they see an invite card with the server icon and player count and join with
  one click (you're asked before leaving your current world). Create and manage groups, and the Friends tab shows your
  friends, requests (including cape offers) and blocked players side by side.
- **TRS Client: notifications in the game.** New messages, server invites, friend requests, cape offers and friends
  coming online appear as small notifications in the top right for 5 seconds – above every menu, and they slide down
  out of the way of Minecraft's own pop-ups (recipes, tips, advancements). Press the quick-reply key (Y) to answer
  right away or join an invite without leaving the game. In the TRS menu under Social you choose the corner, duration
  (3–10 s), sound, "Do not disturb" (also automatically in fullscreen) and which kinds you want.
- **TRS Client: report and stay safe.** Report messages, pictures, players and groups with a reason and an optional
  note; you get feedback when the team has looked at it. Text from others is always shown without colour or format
  codes, links only open after you confirm them, and a chat mute from the team is shown clearly.
- **TRS Client wardrobe: your own skin is back.** Under Skins, the library now starts with a "Current" card showing the
  skin your account is wearing right now (slim or classic arms; the default skin for offline accounts). Only this card
  has the "worn" lamp; if the same skin is also in your library, that entry shows a small "= current" tag instead of a
  second lamp. From the card you can save the skin to your synced library (named after your account, without creating
  a duplicate) or open it in the editor as a template. It updates right after you apply another skin or switch
  accounts in the game, and the highlighted card now only means "shown in the preview", not "worn".
- **Clip gallery.** All your clips as tiles with a preview picture, length and date – search by name, filter by
  instance and sort by date, length, size or name. Hover over a tile for a moving preview. Every instance also has
  its own "Clips" tab.
- **New clip player.** Play/pause, a timeline that shows a small preview picture while you hover over it, volume and
  mute, playback speed and full screen – with keyboard shortcuts (Space or K, ←/→ 5 seconds, J/L 10 seconds, F, M,
  ,/. single frames, Alt+←/→ for the previous/next clip). If a clip can't be played in the launcher, it offers to open
  it in your system's video player.
- **Trim clips.** Set start and end on the timeline (or with I and O), preview the selection and save it as a new
  clip – the original always stays. When the start is on a keyframe, the clip is copied without re-encoding in an
  instant; otherwise the launcher explains why it has to re-encode (exact, takes a moment) and lets you cut fast at
  the keyframe instead.
- **Share clips.** "Save as …", copy the clip as a file to paste it into Discord or a folder, show it in the folder,
  rename and delete (to the recycle bin).
- **TRS Client: clip preview in game.** In "Clips & Images" a clip now opens a small animated preview with play/pause
  and a timeline, plus "Open in launcher": the launcher comes to the front and plays the clip. Works when the game was
  started from the TRS Launcher.
- **Update news with screenshots.** Clicking an update – on the start page, in the news or in "What's new" after an
  update – opens the post right in the launcher: the update banner, a gallery of screenshots of the new features
  (click to enlarge, browse with the arrows or the arrow keys), the release notes and "View on website" for the blog
  post. Older updates got their screenshots too.
- **New: Social – chat with your friends.** "Friends" is now "Social" with two tabs. **Chat:** direct messages and
  groups (create a group from your friends, rename it, add or remove members, hand over ownership, leave). Messages
  show up instantly with day separators, replies, "(edited)", reactions (👍 ❤️ 😂 😮 😢 😡 🎉 🔥 👀 ✅), "is typing …"
  and read receipts. Send up to 10 pictures per message – screenshots of all your instances, favourites or files from
  your PC (also by drag & drop or Ctrl+V) – and invite friends to a server: the invite card shows the icon and player
  count, and "Join" starts a matching instance and connects right away. Right-click a message to reply, copy, edit or
  delete your own, mark it as unread or report it. Mute conversations for an hour, a day or until you unmute.
  **Friends:** friend list with online status and "last online", requests (including cape offers) and blocked players
  side by side.
- **Everything updates live.** Friends, requests, online status, cape offers, messages and report feedback now arrive
  within a moment – no more reloading or waiting. If the connection drops, the launcher reconnects on its own and
  catches up on everything it missed.
- **Notifications.** Small pop-ups for new messages (with quick reply), server invites (Join), friend requests and cape
  offers (Accept/Decline) and friends coming online. Choose the corner, how long they stay (3–10 s), sound on/off, Do
  not disturb (also automatically while a game runs in fullscreen) and which kinds you want. When the launcher is in
  the background, Windows shows them too.
- **Report and stay safe.** Report messages, pictures, players or groups with a reason; "My reports" shows what
  happened. New privacy switches for read receipts and the typing indicator. Links in messages only open after you
  confirm them.
- **For admins:** a new "Reports" tab with the context of each report, reported pictures, notes, history, word filter
  and actions (delete message, warn, mute, ban, dismiss).

### Deutsch

- **TRS Client: Chatten mit Freunden im Spiel.** Aus dem Freunde-Bildschirm wird „Sozial“ mit den Reitern Chat und
  Freunde (Titelbildschirm, Pausenmenü, TRS-Menü oder eigene Taste). Schreib Freunden und Gruppen, antworte auf eine
  Nachricht, kopiere, bearbeite oder lösche eigene, markiere als ungelesen, reagiere und sieh, wer gerade tippt oder
  deine Nachricht gelesen hat – alles live, ohne neu zu laden. Schick bis zu 10 Bildschirmfotos dieser Instanz auf
  einmal („Bilder auswählen“), öffne Bilder groß und lade Freunde auf deinen Server ein: Sie sehen eine Einladungskarte
  mit Server-Symbol und Spielerzahl und treten mit einem Klick bei (vorher kommt eine Rückfrage, wenn du gerade in einer
  Welt bist). Erstelle und verwalte Gruppen; der Reiter Freunde zeigt Freunde, Anfragen (auch Umhang-Angebote) und
  Blockierte nebeneinander.
- **TRS Client: Benachrichtigungen im Spiel.** Neue Nachrichten, Server-Einladungen, Freundschaftsanfragen,
  Umhang-Angebote und Freunde, die online kommen, erscheinen 5 Sekunden lang oben rechts – über jedem Menü, und sie
  rutschen unter Minecrafts eigene Hinweise (Rezepte, Tipps, Fortschritte), statt sie zu verdecken. Mit der Schnellantwort-Taste
  (Y) antwortest du sofort oder trittst einer Einladung bei, ohne das Spiel zu verlassen. Im TRS-Menü unter Sozial
  stellst du Ecke, Dauer (3–10 s), Ton, „Nicht stören“ (auch automatisch im Vollbild) und die Arten ein.
- **TRS Client: Melden und Sicherheit.** Melde Nachrichten, Bilder, Spieler und Gruppen mit Grund und optionalem Text;
  du bekommst eine Rückmeldung, wenn das Team es geprüft hat. Text von anderen wird immer ohne Farb- und
  Formatierungscodes gezeigt, Links öffnen sich erst nach deiner Bestätigung, und eine Chat-Stummschaltung durch das
  Team wird deutlich angezeigt.
- **TRS-Client-Garderobe: dein eigener Skin ist wieder da.** Unter „Skins“ beginnt die Bibliothek jetzt mit der Karte
  „Aktuell“ – dem Skin, den dein Konto gerade trägt (Slim- oder Classic-Arme; bei Offline-Konten der Standard-Skin).
  Nur diese Karte hat die Lampe „getragen“; liegt derselbe Skin auch in deiner Bibliothek, zeigt dieser Eintrag statt
  einer zweiten Lampe das kleine Schild „= aktuell“. Über die Karte speicherst du den Skin in deiner synchronisierten
  Bibliothek (mit deinem Kontonamen, ohne Doppelung) oder öffnest ihn als Vorlage im Editor. Sie aktualisiert sich
  sofort, wenn du einen anderen Skin anwendest oder im Spiel das Konto wechselst, und die hervorgehobene Karte heißt
  jetzt nur noch „in der Vorschau“, nicht „getragen“.
- **Clip-Galerie.** Alle Clips als Kacheln mit Vorschaubild, Länge und Datum – nach Namen suchen, nach Instanz filtern
  und nach Datum, Länge, Größe oder Name sortieren. Beim Überfahren einer Kachel läuft eine kleine Vorschau. Jede
  Instanz hat außerdem einen eigenen Reiter „Clips“.
- **Neuer Clip-Player.** Abspielen/Pause, eine Zeitleiste, die beim Überfahren ein kleines Vorschaubild zeigt,
  Lautstärke und Stummschalten, Wiedergabetempo und Vollbild – mit Tastenkürzeln (Leertaste oder K, ←/→ 5 Sekunden, J/L
  10 Sekunden, F, M, ,/. Einzelbilder, Alt+←/→ für den vorherigen/nächsten Clip). Lässt sich ein Clip im Launcher nicht
  abspielen, bietet er an, ihn im Videoplayer des Systems zu öffnen.
- **Clips zuschneiden.** Start und Ende auf der Zeitleiste setzen (oder mit I und O), den Ausschnitt ansehen und als
  neuen Clip speichern – das Original bleibt immer erhalten. Liegt der Start auf einem Keyframe, wird der Clip ohne
  Neukodierung sofort kopiert; sonst erklärt der Launcher, warum er neu kodieren muss (genau, dauert einen Moment), und
  bietet an, stattdessen schnell am Keyframe zu schneiden.
- **Clips teilen.** „Speichern unter …“, den Clip als Datei kopieren und in Discord oder einen Ordner einfügen, im Ordner
  zeigen, umbenennen und löschen (in den Papierkorb).
- **TRS Client: Clip-Vorschau im Spiel.** In „Clips & Bilder“ öffnet ein Clip jetzt eine kleine animierte Vorschau mit
  Abspielen/Pause und Zeitleiste, dazu „Im Launcher öffnen“: Der Launcher kommt nach vorn und spielt den Clip ab.
  Klappt, wenn das Spiel über den TRS Launcher gestartet wurde.
- **Update-News mit Screenshots.** Ein Klick auf ein Update – auf der Startseite, in den Neuigkeiten oder in „Was ist
  neu“ nach einem Update – öffnet den Beitrag direkt im Launcher: das Update-Banner, eine Galerie mit Screenshots der
  Neuerungen (zum Vergrößern anklicken, mit den Pfeilen oder Pfeiltasten blättern), die Versionshinweise und „Auf
  Website ansehen“ für den Blog-Beitrag. Ältere Updates haben ihre Screenshots ebenfalls bekommen.
- **Neu: Sozial – chatte mit deinen Freunden.** Aus „Freunde“ wird „Sozial“ mit zwei Reitern. **Chat:**
  Direktnachrichten und Gruppen (Gruppe aus deinen Freunden erstellen, umbenennen, Mitglieder hinzufügen oder entfernen,
  Besitz übergeben, verlassen). Nachrichten erscheinen sofort – mit Tagestrennern, Antworten, „(bearbeitet)“,
  Reaktionen (👍 ❤️ 😂 😮 😢 😡 🎉 🔥 👀 ✅), „schreibt …“ und Lesebestätigungen. Bis zu 10 Bilder pro Nachricht –
  Screenshots aller Instanzen, Favoriten oder Dateien vom PC (auch per Drag & Drop oder Strg+V) – und Einladungen auf
  einen Server: Die Karte zeigt Icon und Spielerzahl, „Beitreten“ startet eine passende Instanz und verbindet direkt.
  Rechtsklick auf eine Nachricht: antworten, kopieren, eigene bearbeiten oder löschen, als ungelesen markieren, melden.
  Unterhaltungen lassen sich für eine Stunde, einen Tag oder bis auf Weiteres stummschalten. **Freunde:** Freundesliste
  mit Online-Status und „zuletzt online“, Anfragen (auch Umhang-Angebote) und blockierte Spieler nebeneinander.
- **Alles aktualisiert sich live.** Freunde, Anfragen, Online-Status, Umhang-Angebote, Nachrichten und Rückmeldungen zu
  Meldungen kommen jetzt sofort an – kein Neuladen, kein Warten mehr. Bricht die Verbindung ab, verbindet sich der
  Launcher selbst neu und holt alles Verpasste nach.
- **Benachrichtigungen.** Kleine Hinweise bei neuen Nachrichten (mit Schnellantwort), Server-Einladungen (Beitreten),
  Freundschaftsanfragen und Umhang-Angeboten (Annehmen/Ablehnen) und wenn Freunde online kommen. Einstellbar: Ecke,
  Anzeigedauer (3–10 s), Ton an/aus, Nicht stören (auch automatisch, solange ein Spiel im Vollbild läuft) und welche
  Arten du willst. Ist der Launcher im Hintergrund, zeigt Windows sie zusätzlich an.
- **Melden und sicher bleiben.** Nachrichten, Bilder, Spieler oder Gruppen mit Grund melden; „Meine Meldungen“ zeigt,
  was daraus wurde. Neue Datenschutz-Schalter für Lesebestätigungen und „schreibt …“. Links in Nachrichten öffnen sich
  erst nach einer Bestätigung.
- **Für Admins:** neuer Reiter „Meldungen“ mit Kontext jeder Meldung, gemeldeten Bildern, Notizen, Verlauf, Wortfilter
  und Entscheidungen (Nachricht löschen, verwarnen, stummschalten, sperren, abweisen).
- **TRS Client: the resource loading screen stays in the TRS style from start to finish.** Fading in, loading and
  fading out over the next screen no longer let the red Mojang screen, its logo or the white loading bar shine
  through – at game start, after F3+T or changing resource packs, and when a server sends its resource pack while you
  join. On the "Connecting to the server" screen the TRS logo, the status and the lamp row now always sit above the
  "Cancel" button instead of being covered by it, at every window size and GUI scale.

### Deutsch

- **TRS Client: Der Ladebildschirm für Ressourcen bleibt von Anfang bis Ende im TRS-Stil.** Beim Einblenden, Laden und
  Ausblenden über dem nächsten Bildschirm scheinen der rote Mojang-Bildschirm, sein Logo und der weiße Ladebalken nicht
  mehr durch – beim Spielstart, nach F3+T oder dem Wechsel von Ressourcenpaketen und wenn ein Server beim Beitreten
  sein Ressourcenpaket schickt. Auf dem Bildschirm „Verbinde mit dem Server“ stehen TRS-Logo, Status und Lampenreihe
  jetzt immer über dem Knopf „Abbrechen“, statt von ihm verdeckt zu werden – bei jeder Fenstergröße und GUI-Skalierung.

## 0.6.4 – 2026-09-26 – The Workshop Update | Das Werkstatt-Update
<!-- banner: accent=#d99a5b motif=/news/0.6.4/banner.png -->

### English

- **New instance page with tabs.** Content, Files, Worlds, Screenshots, History, Logs and Share now sit in one tab bar
  below the instance header (icon, name, version, play time, last played, Play/Stop, settings). The page remembers the
  last tab for every instance, and the tabs work with the arrow keys.
- **Much better logs.** Search with highlighted matches, filters for errors, warnings and info with counts, coloured
  lines and collapsible stack traces. Besides the live log you can open older logs (also packed `.log.gz` files), crash
  reports and the launcher's own output. The view follows new lines while you are at the bottom and offers "Jump to
  bottom" when you scroll up; it stays smooth even with more than 100,000 lines. Clear the view, copy the visible
  lines, switch to full screen, or share: after a confirmation the log is uploaded to mclo.gs without login tokens and
  user names, and you get the link with a QR code, a copy button and "Open in browser".
- **Files tab.** Browse the instance folder with breadcrumbs, icons for mods, configs, worlds, resource packs,
  shaders, screenshots and logs, size and dates, sorting and a filter. Create folders and files, rename, move to the
  trash, show in the file manager, select several entries, and upload with the file picker or by dragging files and
  folders into the window. Everything stays inside the instance folder.
- **Worlds tab.** Every world shows its real name, game mode, hardcore/cheats, the version it was last played in, size
  and last played date. Open the folder, back a world up as a ZIP (into the "backups" folder) or move it to the trash.
  Below you find the instance's servers: add, edit and remove them, and "Join" starts the game and connects straight
  away. Servers from the launcher's server list are marked.
- **Share tab.** Export the instance as a modpack, share the log, back up a world or pick single files – all in one
  place.

### Deutsch

- **Neue Instanzseite mit Tabs.** Inhalte, Dateien, Welten, Screenshots, Verlauf, Logs und Teilen stehen jetzt in einer
  Tab-Leiste unter dem Instanz-Kopf (Icon, Name, Version, Spielzeit, zuletzt gespielt, Spielen/Stoppen, Einstellungen).
  Die Seite merkt sich den zuletzt offenen Tab je Instanz, und die Tabs lassen sich mit den Pfeiltasten bedienen.
- **Viel bessere Logs.** Suche mit hervorgehobenen Treffern, Filter für Fehler, Warnungen und Info mit Anzahl, farbige
  Zeilen und aufklappbare Stacktraces. Neben dem Live-Log lassen sich ältere Logs (auch gepackte `.log.gz`),
  Absturzberichte und die Ausgabe des Launchers öffnen. Die Ansicht läuft mit, solange du unten bist, und bietet „Nach
  unten“, wenn du hochscrollst; auch mit mehr als 100 000 Zeilen bleibt sie flüssig. Ansicht leeren, sichtbare Zeilen
  kopieren, Vollbild oder teilen: Nach einer Bestätigung landet der Log ohne Anmelde-Tokens und Benutzernamen auf
  mclo.gs, und du bekommst den Link mit QR-Code, Kopieren-Knopf und „Im Browser öffnen“.
- **Tab „Dateien“.** Durchsuche den Instanzordner mit Brotkrumen-Pfad, Symbolen für Mods, Configs, Welten,
  Ressourcenpakete, Shader, Screenshots und Logs, Größe und Datum, Sortierung und Filter. Ordner und Dateien anlegen,
  umbenennen, in den Papierkorb legen, im Dateimanager zeigen, mehrere Einträge auswählen und per Dateiauswahl oder
  Drag & Drop von Dateien und Ordnern hochladen. Alles bleibt im Instanzordner.
- **Tab „Welten“.** Jede Welt zeigt ihren echten Namen, Spielmodus, Hardcore/Cheats, die zuletzt gespielte Version,
  Größe und das Datum. Ordner öffnen, Welt als ZIP sichern (in den Ordner „backups“) oder in den Papierkorb legen.
  Darunter stehen die Server der Instanz: hinzufügen, bearbeiten, entfernen – „Beitreten“ startet das Spiel und
  verbindet sofort. Server aus der Serverliste des Launchers sind markiert.
- **Tab „Teilen“.** Instanz als Modpack exportieren, Log teilen, Welt sichern oder einzelne Dateien heraussuchen – alles
  an einem Ort.

## 0.6.3 – 2026-09-26 – The Safety Net Update | Das Sicherheitsnetz-Update
<!-- banner: accent=#e8d44d motif=/news/0.6.3/banner.png -->

### English

- **No more "Incompatible mods found!" because of a missing mod.** Some mods need another mod to run (More Culling
  from the Max FPS package needs Cloth Config, for example). If a download was cancelled or failed on the first start,
  the mod could end up without its partner and the game crashed on every start. The launcher now always downloads the
  needed mods first, retries the performance package on the next start if something was missing, and checks before
  every start whether a mod lacks a mod it needs – if so, it installs or switches it back on and tells you. Updating
  or switching a mod version and installing from Discover also bring new required mods along now.
- **One click to install what's missing.** If the game still stops because a mod is missing, the crash message names
  it and offers "Install …".
- The first start of a new instance shows a "Mods" step while the performance mods are downloaded, instead of looking
  stuck.

### Deutsch

- **Kein „Incompatible mods found!“ mehr wegen einer fehlenden Mod.** Manche Mods brauchen eine andere Mod (More
  Culling aus dem Max-FPS-Paket etwa Cloth Config). Wurde beim ersten Start ein Download abgebrochen oder schlug fehl,
  konnte die Mod ohne ihren Partner dastehen – und das Spiel stürzte bei jedem Start ab. Der Launcher lädt die
  benötigten Mods jetzt immer zuerst, ergänzt das Leistungspaket beim nächsten Start, wenn etwas fehlte, und prüft vor
  jedem Start, ob einer Mod eine benötigte Mod fehlt – dann installiert er sie oder schaltet sie wieder ein und sagt es
  dir. Auch Mod-Updates, Versionswechsel und Installationen aus „Entdecken“ bringen neue Pflicht-Mods jetzt mit.
- **Fehlendes mit einem Klick installieren.** Bricht das Spiel trotzdem wegen einer fehlenden Mod ab, nennt die
  Absturzmeldung sie und bietet „… installieren“ an.
- Der erste Start einer neuen Instanz zeigt den Schritt „Mods“, während die Leistungs-Mods geladen werden – statt
  hängenzubleiben.

## 0.6.2 – 2026-09-26 – The Sharing Update | Das Teilen-Update
<!-- banner: accent=#ff5fa2 motif=/news/0.6.2/banner.png -->

### English

- **Share your own capes with friends.** Once the team has approved a cape you uploaded, "Share with a friend" (skins
  page in the launcher, or Wardrobe → Capes in the game) offers it to a friend. Your friend sees the offer with a
  preview in the launcher (Friends → Requests, and above your capes) and in the game (wardrobe and friends screen) and
  can accept or decline it. Accepted capes show up in their collection, can be worn like any unlocked cape and are
  visible to other players. Friends may pass a shared cape on to their own friends – up to 20 players per cape. You see
  who has your cape and can take it back at any time; everyone they passed it on to loses it too. Friends can give a
  shared cape back. Removing or blocking a friend cancels open offers between you. New offers show up with a note and a
  "NEW" mark.

### Deutsch

- **Eigene Umhänge mit Freunden teilen.** Sobald das Team einen hochgeladenen Umhang freigegeben hat, bietet „Mit
  Freund teilen“ (Skins-Seite im Launcher oder Garderobe → Umhänge im Spiel) ihn einem Freund an. Dein Freund sieht das
  Angebot mit Vorschau im Launcher (Freunde → Anfragen und über den Umhängen) und im Spiel (Garderobe und Freunde) und
  kann es annehmen oder ablehnen. Angenommene Umhänge stehen danach in seiner Sammlung, lassen sich wie freigeschaltete
  tragen und sind für andere sichtbar. Freunde dürfen einen geteilten Umhang an eigene Freunde weitergeben – höchstens
  20 Spieler je Umhang. Du siehst, wer deinen Umhang hat, und kannst ihn jederzeit zurücknehmen; dann verlieren ihn auch
  alle, an die er weitergegeben wurde. Freunde können einen geteilten Umhang zurückgeben. Entfernst oder blockierst du
  einen Freund, fallen offene Angebote zwischen euch weg. Neue Angebote erscheinen mit Hinweis und „NEU“-Markierung.

## 0.6.1 – 2026-09-26 – The Polish Update | Das Feinschliff-Update
<!-- banner: accent=#ff7a3d motif=/news/0.6.1/banner.png -->

### English

- **The redstone background moves again on every PC.** On some PCs it stood still – Windows reports "reduce motion"
  as soon as its animation effects are off (performance options, remote desktop, tuning tools). New setting under
  Settings → Appearance → "Animations": "Always on" (default), "Follow Windows/system" or "Reduced". It applies to all
  animations in the launcher. The background also picks up again reliably after the window was minimised or
  restored, and a hiccup in the circuit no longer freezes it.
- **Clips always tell you what's going on.** Pressing F9 or F10 in the game now always shows a clear message: clips
  are off, the game wasn't started from the TRS Launcher, the recorder is still downloading (with progress), no video
  encoder works, or the clip was saved. The buttons in "Clips & Images" answer the same way.
- **Turn clips on right from the game.** If clips are off, press F9 (or F10) a second time – or "Turn on now" in
  "Clips & Images" – and the launcher switches clips on and starts recording straight away. The message says what
  gets recorded (game window and system sound; the microphone only if you turned it on in the launcher). The switch in
  the launcher settings follows along. With older launchers the game points you to Settings → Clips instead.
- **Fewer download retries.** If the recorder (FFmpeg) can't be downloaded, the launcher waits a minute before trying
  again instead of retrying every second.
- **A much better minimap.** The TRS Client minimap now glides: movement, turning and zooming are smooth instead of
  jumping from block to block, and the map is sharp at every zoom level (one pixel per block, drawn crisp). It shows
  real block colours with biome-tinted grass and leaves, soft relief shading, clear water that gets darker with depth,
  round or square, in a redstone frame with compass letters. Below it: coordinates, biome and in-game time if you
  like. Underground and in the Nether it switches to a cave view on its own. Players show their faces (TRS friends get
  a golden ring), and you can show hostile mobs and animals as dots. Waypoints and your last death point appear on the
  edge when they are far away.
- **New: fullscreen world map (M).** Everything you have explored stays on the map – saved per world, server and
  dimension on your PC (size-limited). Drag to move, scroll to zoom around the mouse, see the coordinates under the
  cursor and jump back to yourself with one click. Right-click to create, edit, hide or delete waypoints right on the
  map. If another map mod already uses M, the TRS key starts unbound instead of clashing.
- **Fair Play with one switch.** One switch turns off everything a map could use to cheat: no cave view, and players
  and mobs only show up when you could actually see them. Servers that ask map mods for fair play (the common Xaero
  codes) are respected automatically.
- **Everything adjustable.** Shape, size, zoom, opacity, rotation, every marker and every line below the map can be set
  in the TRS menu; position and size in the HUD editor.

### Deutsch

- **Der Redstone-Hintergrund bewegt sich wieder auf jedem PC.** Auf manchen PCs stand er still – Windows meldet
  „Bewegung reduzieren“, sobald die Animationseffekte aus sind (Leistungsoptionen, Remotedesktop, Tuning-Tools). Neue
  Einstellung unter Einstellungen → Aussehen → „Animationen“: „Immer an“ (Standard), „Wie Windows/System“ oder
  „Reduziert“. Sie gilt für alle Animationen im Launcher. Außerdem läuft der Hintergrund nach dem Minimieren oder
  Wiederherstellen des Fensters zuverlässig weiter, und ein Aussetzer in der Schaltung lässt ihn nicht mehr einfrieren.
- **Clips sagen dir immer, was los ist.** F9 oder F10 im Spiel zeigt jetzt immer eine klare Meldung: Clips sind aus,
  das Spiel wurde nicht über den TRS Launcher gestartet, die Aufnahme-Komponente lädt noch (mit Fortschritt), kein
  Video-Encoder läuft oder der Clip ist gespeichert. Die Knöpfe unter „Clips & Bilder“ antworten genauso.
- **Clips direkt im Spiel einschalten.** Sind Clips aus, drück F9 (oder F10) ein zweites Mal – oder „Jetzt
  einschalten“ unter „Clips & Bilder“ – und der Launcher schaltet Clips ein und nimmt sofort auf. Die Meldung sagt, was
  aufgenommen wird (Spielfenster und Systemton; das Mikrofon nur, wenn du es im Launcher eingeschaltet hast). Der
  Schalter in den Launcher-Einstellungen zieht mit. Mit älteren Launchern verweist das Spiel stattdessen auf
  Einstellungen → Clips.
- **Weniger Download-Versuche.** Lässt sich die Aufnahme-Komponente (FFmpeg) nicht laden, wartet der Launcher eine
  Minute, statt es jede Sekunde neu zu versuchen.
- **Eine viel bessere Minimap.** Die Minimap des TRS Client gleitet jetzt: Bewegen, Drehen und Zoomen laufen flüssig
  statt von Block zu Block zu springen, und die Karte ist in jeder Zoomstufe scharf (ein Pixel je Block, klar
  gezeichnet). Sie zeigt echte Blockfarben mit Gras und Laub in Biomfarbe, sanfte Relief-Schattierung, klares Wasser,
  das mit der Tiefe dunkler wird, rund oder eckig, im Redstone-Rahmen mit Himmelsrichtungen. Darunter auf Wunsch
  Koordinaten, Biom und Spielzeit. Unter Tage und im Nether wechselt sie von selbst in die Höhlenansicht. Spieler
  erscheinen mit Gesicht (TRS-Freunde mit goldenem Ring), feindliche Kreaturen und Tiere auf Wunsch als Punkte.
  Wegpunkte und dein letzter Todespunkt stehen am Rand, wenn sie weit weg sind.
- **Neu: Weltkarte im Vollbild (M).** Alles, was du erkundet hast, bleibt auf der Karte – gespeichert je Welt, Server
  und Dimension auf deinem PC (in der Größe begrenzt). Ziehen zum Verschieben, Mausrad zum Zoomen um den Mauszeiger,
  Koordinaten unter dem Zeiger und mit einem Klick zurück zu dir. Per Rechtsklick legst du Wegpunkte direkt auf der
  Karte an, bearbeitest, versteckst oder löschst sie. Nutzt eine andere Karten-Mod schon M, startet die TRS-Taste
  unbelegt statt doppelt belegt.
- **Fair Play mit einem Schalter.** Ein Schalter schaltet alles ab, womit eine Karte schummeln könnte: keine
  Höhlenansicht, Spieler und Kreaturen nur, wenn du sie wirklich sehen könntest. Server, die von Karten-Mods Fair Play
  verlangen (die verbreiteten Xaero-Codes), werden automatisch beachtet.
- **Alles einstellbar.** Form, Größe, Zoom, Deckkraft, Drehung, jede Markierung und jede Zeile unter der Karte stellst
  du im TRS-Menü ein; Lage und Größe im HUD-Editor.

## 0.6.0 – 2026-09-25 – The Sync Update | Das Sync-Update
<!-- banner: accent=#4be38a motif=/news/0.6.0/banner.png -->

### English

- **Wardrobe with a full skin editor in the game.** The TRS Client now has a wardrobe (button under your figure on the title screen, TRS menu or
  your own key): your skins with favourites and a library, outfits (skin + cape) to flip through, your Minecraft and
  TRS capes, and your emote wheel – all with a big 3D preview. "Add skin" takes a file, a link or a player name. Apply
  a skin and it becomes your Minecraft skin right away. The editor lets you paint straight onto the 3D figure or the
  flat skin: brush, eraser, eyedropper, fill, left/right mirror, undo/redo, colour palette with recent colours and hex
  input, inner and outer layer, classic or slim arms and blank templates. With the TRS services on, your skins are the
  same "My skins" as in the launcher, and favourites, outfits and emote wheel follow you to every PC.
- **Import from many more launchers.** "Import from another launcher" now also finds Lunar Client, Badlion, OneClient,
  ATLauncher, GDLauncher (old and new) and TLauncher, next to the official launcher, CurseForge, the Modrinth App, Prism
  and MultiMC. It shows which launchers are on your PC and, per instance, exactly what comes along: worlds, mods,
  resource and shader packs, settings and your server list. The version and mod loader are detected for you. Lunar
  and Badlion bring your own worlds, packs, settings and mods – their built-in client mods aren't free and stay behind,
  TRS Client takes their place. Feather is recognised but keeps no readable list, so pick its folder by hand.
- **Imported mods stay updatable.** Content from CurseForge, ATLauncher and GDLauncher keeps its origin, so you can
  update it right away. Files missing from a CurseForge instance are downloaded for you.
- **Skins from ATLauncher.** "Add skin" → "From other launchers" now also brings over the skins of your ATLauncher
  accounts.
- **Safer imports.** Sign-in data of other launchers is never read or copied, other launchers' files are only read,
  and links that point outside the launcher's own folders are skipped.
- **New TRS Client title screen: you on a redstone turntable.** Your own skin (and your TRS or Mojang cape) now
  stands on a slowly turning redstone turntable on the left of the title screen – drag it to spin it, and your head
  follows the mouse. Below it are your name and a "Wardrobe" button. A new bar on the right leads to Accounts,
  Friends, Clips & Images and the TRS settings; the parts that aren't ready yet say "Coming soon". In small
  windows the bar shrinks to icons and the figure steps aside. Works in every supported Minecraft version, from 1.8.9
  to 26.3.
- **Switch accounts in the game.** With the TRS Client you can now switch Minecraft accounts right in the game – no
  restart. Started from the launcher, all your launcher accounts are there, and "Add account" signs in through the
  launcher and saves the new account there too. Started from another launcher, you can sign in with Microsoft in the
  game and keep accounts per game folder. Find it in the TRS menu under "Accounts".
- **Menus in the redstone look.** The pause menu, server list, loading screens, options and world list now wear the
  TRS redstone style: redstone background, stone buttons that light up, server cards with redstone ping bars. Every
  button keeps working – also the ones other mods add. Pin your favourite servers to keep them on top, and see which
  TRS friends are playing on a server right in the list. The pause menu gets buttons for Wardrobe, Accounts, Clips,
  Friends and a new "Server info" page. Loading screens and the start-up screen show a row of redstone lamps. Each menu
  can go back to the classic look in the TRS menu under "Menu Style".
- **Friends in the game.** See your TRS friends with their Minecraft faces, who is online and what they are playing,
  answer friend requests, add friends by name, remove or block players – right in the game from the title screen, the
  TRS menu or the pause menu.
- **Clips & Images in the game.** All your clips and screenshots as tiles with previews: look at screenshots in full
  size, start or stop a recording, save a clip, open the folder or delete files (with a safety question).
- **Safer connection between launcher and game.** Clips and account switching now use a key the game only gets in
  memory – no more secret in a file in your game folder.
- **The skin preview shows your TRS cape.** After a restart the 3D preview now shows the TRS cape you wear – the way
  other TRS players see you in game – instead of your Mojang cape. Click a Mojang cape to look at that one instead.
- **Your skins, presets and look follow you to every PC.** With the TRS services on, "My skins", your own presets and
  your theme, accent colour and language are synced with your Minecraft account – add a skin on one PC and it's there
  on the next one, delete it and it's gone everywhere. Your Java and memory settings stay on each PC. You can rename
  skins in your collection now, too. Don't want it? Turn off "Sync with TRS account" under Settings → Privacy.
- **Adding skins is much easier.** "Add skin" now opens a menu: pick several PNG files at once, drag them straight into
  the window, paste a link, type a player's name to copy their skin, or bring over the skins you saved in the official
  Minecraft Launcher, Prism Launcher or the Modrinth App – with a checklist and previews. The launcher recognises slim
  (Alex) arms by itself; for a single skin you can still change the name and model before it's added.
- **Upload capes the way you want them.** The new cape dialog works like cropping a profile picture: pick any image
  (PNG, JPEG, WebP), drag and zoom to choose the part that goes on the cape and watch it live on the player in 3D.
  Animated GIFs, several images at once, sprite sheets and TRS Studio exports become animated capes with up to 16
  frames – you pick the speed. Custom capes can now be up to 512×256 pixels per frame and 5 MB.
- **TRS capes in HD in the game.** The TRS Client now also loads large HD capes up to 8 MB.
- **For the team: reviewing capes is faster.** Click a waiting cape to see it on the player in 3D, the texture pixel
  by pixel with zoom and every frame, and who uploaded it. Approve with A, reject with D (with a reason to pick) and
  browse with the arrow keys.
- **Mods that don't get along are caught before the game starts.** Some mods only work with certain versions of
  other mods (Sodium 0.8.14, for example, refuses Iris 1.10.7) – Modrinth doesn't say so, but the mod files do. The
  launcher now reads those rules: FPS-Boost and other presets pick versions that fit together (and tell you when a
  version was chosen for that reason), updates that would break another mod are held back, and an instance that
  already has such a pair gets a "Fix" button in the content list. If the game still stops with "Incompatible mods
  found", the crash notice offers to switch the mod to a compatible version with one click.
- **Your TRS Client settings follow you to every PC.** With the TRS services on, your modules and their settings,
  HUD layouts and profiles and your TRS keys are saved with your Minecraft account – set up the client once and it
  looks the same on your next PC or game folder. The theme, accent colour and language from the launcher now also
  switch in the game right away. Waypoints, server lists and Minecraft's own options stay on each PC. Don't want it?
  Turn off "Sync with TRS account" on the "TRS Online Features" page in the TRS menu.
- **A short introduction when you start the TRS Client for the first time.** Four quick steps in the redstone look:
  language and theme, performance (pretty or maximum FPS, frame limit, VSync, Dynamic FPS), your TRS keys – keys that
  clash with Minecraft or other mods are highlighted and can be changed right there – and a module pack with a HUD
  layout. Skip it any time. Everyone sees it once – also if you're updating from an earlier TRS Client – and only once
  per TRS account: finish or skip it on one PC and it won't come back on the others. You can start it again in the
  TRS menu.
- **Module packs.** One click sets up the client for your play style – PvP, Redstone, Comfort or Minimal – with a
  matching HUD layout. You see beforehand what gets turned on, off and moved, and you can undo it. Find them in the
  TRS menu under "Presets".
- **"NEW" in the TRS menu.** Modules and settings that arrive with a client update carry a "NEW" sign until you've
  opened them once – on every PC with your account. Updating from an earlier TRS Client, you'll find it on everything
  new in this update: the wardrobe (and its key), menu style, friends, clips & images, accounts, module packs, the
  built-in optimizations, the graphics mode and account sync – in the TRS menu and on the title screen.
- **Much higher FPS in new instances.** Minecraft starts with VSync on and a frame limit of 120 FPS, so even a
  strong PC got stuck at around 100 FPS. New instances now start with an unlimited frame rate and VSync off – your
  own settings in existing instances stay as they are.
- **TRS Client: performance check finds the frame limit.** The check in the TRS menu now flags a frame limit below
  "Unlimited" and lifts it with one click, and every FPS Boost level lifts it too. Undo restores it.
- **TRS Client: no more FPS cap while playing.** If Minecraft missed that its window got the focus back, the
  background limiter of the TRS Client could hold the running game at about 100 FPS. It now asks the system
  directly and never slows down the game you are playing.
- **TRS Client: built-in optimizations (Fabric).** The TRS Client now brings free performance mods along – Lithium,
  FerriteCore, ImmediatelyFast, ModernFix and BadOptimizations, wherever they exist for your Minecraft version – so
  it is faster even without extra mods. If you install one of them yourself, the newer version is used. You can
  switch them off in the TRS menu under Performance (takes effect at the next start through the TRS Launcher). The
  FPS Boost preset of the launcher no longer installs these mods a second time – unless you switched the built-in
  optimizations off or the TRS Client is off for that instance.
- **TRS Client: graphics mode "Pretty" or "Max FPS".** Pick it in the TRS menu under Performance or in the
  instance settings of the launcher. Pretty keeps the look; Max FPS turns off clouds and smooth lighting, lowers
  particles and uses fast graphics and fast leaves. Settings you changed yourself stay as they are, and switching
  back restores the rest.
- **TRS Client: lighter HUD on Minecraft 1.21.6 and newer.** HUD texts no longer create lots of short-lived data
  every frame, which means fewer small stutters.
- **The TRS badge is now live.** The badge next to a player's name now means "playing with TRS right now": it shows
  while someone is in a world or on a server with the TRS Client, or plays a game started by the TRS Launcher – not
  when a TRS user only has the launcher open or plays with another client. It appears and disappears within seconds.
  For privacy, you only see other players' badges while you are in game yourself; your own you always see. "Show TRS
  badge" in the privacy settings still turns yours off.

### Deutsch

- **Garderobe mit vollem Skin-Editor im Spiel.** Der TRS Client hat jetzt eine Garderobe (Knopf unter deiner Figur im Titelbildschirm,
  TRS-Menü oder eigene Taste): deine Skins mit Favoriten und Bibliothek, Outfits (Skin + Umhang) zum Durchblättern,
  deine Minecraft- und TRS-Umhänge und dein Emote-Rad – alles mit großer 3D-Vorschau. „Skin hinzufügen“ nimmt eine
  Datei, einen Link oder einen Spielernamen. Ein Klick auf „Anwenden“ macht den Skin sofort zu deinem Minecraft-Skin.
  Im Editor malst du direkt auf der 3D-Figur oder auf dem flachen Skin: Pinsel, Radierer, Pipette, Füllen,
  Links/Rechts-Spiegeln, Rückgängig/Wiederholen, Farbpalette mit zuletzt benutzten Farben und Hex-Eingabe, innere und
  äußere Ebene, Classic- oder Slim-Arme und leere Vorlagen. Mit eingeschalteten TRS-Diensten sind deine Skins dieselben
  „Meine Skins“ wie im Launcher, und Favoriten, Outfits und Emote-Rad folgen dir auf jeden PC.
- **Import aus viel mehr Launchern.** „Aus anderem Launcher importieren“ findet jetzt auch Lunar Client, Badlion,
  OneClient, ATLauncher, GDLauncher (alt und neu) und TLauncher – neben dem offiziellen Launcher, CurseForge, der
  Modrinth App, Prism und MultiMC. Du siehst, welche Launcher auf deinem PC liegen, und je Instanz genau, was mitkommt:
  Welten, Mods, Ressourcen- und Shaderpakete, Einstellungen und deine Serverliste. Version und Modloader werden
  erkannt. Bei Lunar und Badlion kommen deine eigenen Welten, Pakete, Einstellungen und Mods mit – die eingebauten
  Client-Mods sind nicht frei und bleiben zurück, der TRS Client übernimmt ihren Platz. Feather wird erkannt,
  speichert aber keine lesbare Liste – wähle seinen Ordner dann von Hand.
- **Importierte Mods bleiben aktualisierbar.** Inhalte aus CurseForge, ATLauncher und GDLauncher behalten ihre
  Herkunft, du kannst sie also sofort aktualisieren. Fehlen in einer CurseForge-Instanz Dateien, werden sie geladen.
- **Skins aus dem ATLauncher.** „Skin hinzufügen“ → „Aus anderen Launchern“ holt jetzt auch die Skins deiner
  ATLauncher-Konten.
- **Sicherer importieren.** Anmeldedaten anderer Launcher werden nie gelesen oder kopiert, fremde Dateien nur
  gelesen, und Verknüpfungen, die aus den Launcher-Ordnern herausführen, werden übersprungen.
- **Neuer Titelbildschirm im TRS Client: du auf einer Redstone-Drehscheibe.** Dein eigener Skin (mit deinem TRS-
  oder Mojang-Umhang) steht jetzt links auf einer langsam drehenden Redstone-Drehscheibe – zieh daran, um sie zu
  drehen, und dein Kopf folgt der Maus. Darunter stehen dein Name und ein „Garderobe“-Knopf. Eine neue Leiste rechts führt
  zu Konten, Freunde, Clips & Bilder und den TRS-Einstellungen; was noch nicht fertig ist, meldet „Kommt
  bald“. In kleinen Fenstern wird die Leiste zu Symbolen und die Figur macht Platz. Klappt in jeder unterstützten
  Minecraft-Version von 1.8.9 bis 26.3.
- **Konten im Spiel wechseln.** Mit dem TRS Client wechselst du dein Minecraft-Konto jetzt direkt im Spiel – ohne
  Neustart. Über den Launcher gestartet sind alle Launcher-Konten da, und „Konto hinzufügen“ meldet über den Launcher
  an und speichert das neue Konto auch dort. Über einen anderen Launcher gestartet, meldest du dich im Spiel bei
  Microsoft an und behältst Konten je Spielordner. Zu finden im TRS-Menü unter „Konten“.
- **Menüs im Redstone-Look.** Pausenmenü, Serverliste, Ladebildschirme, Einstellungen und Weltenliste tragen jetzt den
  TRS-Redstone-Stil: Redstone-Hintergrund, Steinknöpfe, die aufleuchten, Serverkarten mit Redstone-Ping-Balken. Jeder
  Knopf funktioniert weiter – auch die von anderen Mods. Hefte Lieblingsserver an, damit sie oben bleiben, und sieh
  direkt in der Liste, welche TRS-Freunde gerade auf einem Server spielen. Das Pausenmenü bekommt Knöpfe für
  Garderobe, Konten, Clips, Freunde und eine neue Seite „Server-Info“. Ladebildschirme und der Startbildschirm zeigen
  eine Reihe Redstone-Lampen. Jedes Menü lässt sich im TRS-Menü unter „Menü-Stil“ wieder klassisch stellen.
- **Freunde im Spiel.** Sieh deine TRS-Freunde mit ihrem Minecraft-Gesicht, wer online ist und was gespielt wird,
  beantworte Freundschaftsanfragen, füge Freunde per Name hinzu, entferne oder blockiere Spieler – direkt im Spiel
  über Titelbildschirm, TRS-Menü oder Pausenmenü.
- **Clips & Bilder im Spiel.** Alle Clips und Bildschirmfotos als Kacheln mit Vorschau: Bilder groß ansehen, Aufnahme
  starten oder stoppen, Clip speichern, Ordner öffnen oder Dateien löschen (mit Sicherheitsfrage).
- **Sicherere Verbindung zwischen Launcher und Spiel.** Clips und Kontowechsel nutzen jetzt einen Schlüssel, den das
  Spiel nur im Arbeitsspeicher bekommt – kein Geheimnis mehr in einer Datei im Spielordner.
- **Die Skin-Vorschau zeigt deinen TRS-Umhang.** Nach einem Neustart zeigt die 3D-Vorschau jetzt den TRS-Umhang, den du
  trägst – so, wie andere TRS-Spieler dich im Spiel sehen – statt deines Mojang-Umhangs. Klick einen Mojang-Umhang
  an, um den anzusehen.
- **Deine Skins, Presets und dein Look kommen mit auf jeden PC.** Mit eingeschalteten TRS-Diensten werden „Meine
  Skins“, deine eigenen Presets sowie Theme, Akzentfarbe und Sprache mit deinem Minecraft-Account synchronisiert – auf
  einem PC einen Skin hinzufügen, und er ist auf dem nächsten da; löschen, und er ist überall weg. Java- und
  Speicher-Einstellungen bleiben auf jedem PC. Skins in deiner Sammlung lassen sich jetzt auch umbenennen. Nicht
  gewollt? Unter Einstellungen → Datenschutz „Mit TRS-Konto synchronisieren“ ausschalten.
- **Skins hinzufügen geht viel leichter.** „Skin hinzufügen“ öffnet jetzt ein Menü: mehrere PNG-Dateien auf einmal
  wählen, sie direkt ins Fenster ziehen, einen Link einfügen, per Spielername den Skin eines Spielers übernehmen oder
  die Skins aus dem offiziellen Minecraft Launcher, dem Prism Launcher oder der Modrinth App holen – mit Auswahlliste
  und Vorschau. Schlanke (Alex-)Arme erkennt der Launcher selbst; bei einem einzelnen Skin kannst du Name und Modell
  vor dem Hinzufügen noch ändern.
- **Umhänge hochladen, wie du sie willst.** Der neue Umhang-Dialog funktioniert wie das Zuschneiden eines Profilbilds:
  beliebiges Bild wählen (PNG, JPEG, WebP), den Ausschnitt verschieben und zoomen und ihn live in 3D am Spieler
  sehen. Animierte GIFs, mehrere Bilder auf einmal, Sprite-Sheets und TRS-Studio-Exporte werden zu animierten
  Umhängen mit bis zu 16 Frames – das Tempo bestimmst du. Eigene Umhänge dürfen jetzt bis 512×256 Pixel je Frame und
  5 MB groß sein.
- **TRS-Umhänge in HD im Spiel.** Der TRS Client lädt jetzt auch große HD-Umhänge bis 8 MB.
- **Fürs Team: Umhänge schneller prüfen.** Ein Klick auf einen wartenden Umhang zeigt ihn in 3D am Spieler, die
  Textur Pixel für Pixel mit Zoom und allen Frames und wer ihn hochgeladen hat. Freigeben mit A, ablehnen mit D (mit
  auswählbarem Grund), blättern mit den Pfeiltasten.
- **Mods, die sich nicht vertragen, fallen vor dem Spielstart auf.** Manche Mods laufen nur mit bestimmten Versionen
  anderer Mods (Sodium 0.8.14 etwa verweigert Iris 1.10.7) – bei Modrinth steht das nicht, in den Mod-Dateien schon.
  Der Launcher liest diese Regeln jetzt: FPS-Boost und andere Presets wählen Versionen, die zusammenpassen (und sagen
  dir, wenn eine Version deshalb gewählt wurde), Updates, die eine andere Mod kaputt machen würden, werden
  zurückgehalten, und eine Instanz, die schon so ein Paar hat, bekommt in der Inhaltsliste einen „Beheben“-Knopf.
  Stoppt das Spiel trotzdem mit „Incompatible mods found“, bietet der Absturz-Hinweis an, die Mod mit einem Klick
  gegen eine passende Version zu tauschen.
- **Deine TRS-Client-Einstellungen folgen dir auf jeden PC.** Mit eingeschalteten TRS-Diensten werden deine Module
  und ihre Einstellungen, HUD-Layouts und -Profile und deine TRS-Tasten mit deinem Minecraft-Konto gespeichert – den
  Client einmal einrichten, und er sieht auf dem nächsten PC oder Spielordner genauso aus. Thema, Akzentfarbe und
  Sprache aus dem Launcher wechseln jetzt auch im Spiel sofort mit. Wegpunkte, Serverlisten und Minecrafts eigene
  Optionen bleiben auf jedem PC. Nicht gewünscht? Schalte „Mit TRS-Konto synchronisieren“ auf der Seite
  „TRS-Online-Funktionen“ im TRS-Menü aus.
- **Eine kurze Einführung beim ersten Start des TRS Clients.** Vier schnelle Schritte im Redstone-Look: Sprache und
  Thema, Leistung (schön oder maximale FPS, Bildraten-Grenze, VSync, Dynamische FPS), deine TRS-Tasten – Tasten, die
  mit Minecraft oder anderen Mods kollidieren, leuchten und lassen sich direkt ändern – und ein Modul-Paket mit
  HUD-Vorlage. Jederzeit überspringbar. Alle sehen sie einmal – auch wenn du von einem älteren TRS Client
  aktualisierst – und nur einmal pro TRS-Konto: Auf einem PC abgeschlossen oder übersprungen, kommt sie auf den
  anderen nicht wieder. Im TRS-Menü kannst du sie erneut starten.
- **Modul-Pakete.** Ein Klick richtet den Client für deinen Spielstil ein – PvP, Redstone, Komfort oder Minimal – mit
  passender HUD-Vorlage. Vorher siehst du, was ein-, aus- und umgestellt wird, und du kannst es rückgängig machen. Zu
  finden im TRS-Menü unter „Pakete“.
- **„NEU“ im TRS-Menü.** Module und Einstellungen, die mit einem Client-Update kommen, tragen ein „NEU“-Schild, bis du
  sie einmal geöffnet hast – auf jedem PC mit deinem Konto. Aktualisierst du von einem älteren TRS Client, steht es an
  allem, was dieses Update bringt: Garderobe (samt Taste), Menü-Stil, Freunde, Clips & Bilder, Konten, Modul-Pakete,
  eingebaute Optimierungen, Grafik-Modus und Konto-Sync – im TRS-Menü und auf dem Startbildschirm.
- **Deutlich mehr FPS in neuen Instanzen.** Minecraft startet mit VSync und einer Bildraten-Grenze von 120 FPS –
  selbst ein starker PC blieb so bei rund 100 FPS hängen. Neue Instanzen starten jetzt mit unbegrenzter Bildrate
  und ohne VSync; deine eigenen Einstellungen in bestehenden Instanzen bleiben, wie sie sind.
- **TRS Client: Der Leistungs-Check findet die Bildraten-Grenze.** Der Check im TRS-Menü meldet jetzt eine Grenze
  unter „Unbegrenzt“ und hebt sie mit einem Klick auf; auch jede FPS-Boost-Stufe hebt sie auf. „Rückgängig“ stellt
  sie wieder her.
- **TRS Client: Keine FPS-Bremse mehr beim Spielen.** Hat Minecraft verpasst, dass sein Fenster wieder im
  Vordergrund ist, konnte die Hintergrund-Bremse des TRS Clients das laufende Spiel bei etwa 100 FPS halten. Sie
  fragt jetzt direkt beim System nach und bremst nie das Spiel, das du gerade spielst.
- **TRS Client: Eingebaute Optimierungen (Fabric).** Der TRS Client bringt jetzt freie Leistungs-Mods gleich mit –
  Lithium, FerriteCore, ImmediatelyFast, ModernFix und BadOptimizations, soweit es sie für deine Minecraft-Version
  gibt – und ist damit auch ohne zusätzliche Mods schneller. Installierst du eine davon selbst, wird die neuere
  Fassung benutzt. Abschalten kannst du sie im TRS-Menü unter Leistung (wirkt beim nächsten Start über den TRS
  Launcher). Das FPS-Boost-Preset des Launchers installiert diese Mods nicht mehr doppelt – außer du hast die
  eingebauten Optimierungen ausgeschaltet oder der TRS Client ist für die Instanz aus.
- **TRS Client: Grafik-Modus „Schön“ oder „Max FPS“.** Wählbar im TRS-Menü unter Leistung oder in den
  Instanz-Einstellungen des Launchers. „Schön“ behält die Optik; „Max FPS“ schaltet Wolken und weiche Beleuchtung
  aus, senkt die Partikel und nutzt schnelle Grafik und schnelles Laub. Was du selbst geändert hast, bleibt, und
  beim Zurückschalten kommt der Rest wieder.
- **TRS Client: Leichteres HUD ab Minecraft 1.21.6.** HUD-Texte erzeugen nicht mehr in jedem Bild viele kurzlebige
  Daten – das heißt weniger kleine Ruckler.
- **Das TRS-Symbol ist jetzt live.** Das Symbol neben einem Spielernamen heißt jetzt „spielt gerade mit TRS“: Es
  erscheint, solange jemand mit dem TRS Client in einer Welt oder auf einem Server ist oder ein vom TRS Launcher
  gestartetes Spiel spielt – nicht, wenn ein TRS-Nutzer nur den Launcher offen hat oder mit einem anderen Client
  spielt. Es kommt und geht innerhalb von Sekunden. Zum Schutz deiner Privatsphäre siehst du die Symbole anderer nur,
  während du selbst im Spiel bist; dein eigenes siehst du immer. „TRS-Symbol zeigen“ in den Datenschutz-Einstellungen
  schaltet deins weiterhin ab.

## 0.5.1 – 2026-09-24 – The Turbo Update | Das Turbo-Update

<!-- banner: accent=#3dd6ff motif=/news/0.5.1/banner.png -->

### English

- **No more freezes when players join or leave.** With the TRS Client, the game could freeze for up to a minute
  as soon as another player came online or left – for both players at the same moment. Fixed in TRS Client
  0.4.1, which installs itself automatically.
- **Smoother frames, fewer stutters.** The FPS boost at launch now uses a garbage collector setup made for the game
  client (G1 with short pauses); ZGC is only used from 12 GB of memory, where it no longer causes hitches. The
  settings follow the Java version the game really starts with – also your own Java.
- **Your own JVM arguments no longer switch the boost off.** Only the settings you set yourself replace the
  launcher's (your own memory size, garbage collector or option wins); everything else stays tuned.
- **Memory that fits your PC.** New installations start with 6 GB on PCs with 16 GB or more, 4 GB from 8 GB and
  half of the memory below that. If more is set than the PC has, the game gets at most your memory minus 2 GB so
  Windows doesn't have to swap. Existing settings are kept.
- **FPS boost with shaders – pick your level.** When creating an instance or applying presets, the FPS boost now
  comes in three levels: **Max FPS** (optimisations only, for weaker PCs), **Light shaders** (plus Iris and the
  very light MakeUp – Ultra Fast shader) and **Pretty shaders** (plus Iris and Complementary Reimagined). The shader
  is switched on right away; press **K** in game to turn shaders on or off. Shaders are available with Fabric,
  Quilt and NeoForge; elsewhere they are skipped and named in the summary. Nvidium can't be combined with shaders
  and is left out. New instances still start with Max FPS.
- **Real faces for your friends.** The friends list and the admin player search show each player's Minecraft face
  instead of a placeholder.
- **FPS boost for existing instances.** Instances with a mod loader but without Sodium, Embeddium or OptiFine now
  show a small hint with the level choice – one click installs the boost. Hide it once and it stays hidden for that
  instance; modpacks never show it.
- **Show off on Discord.** With Discord open, your profile now shows "Playing TRS Launcher" – while you play also the
  Minecraft version, the mod loader and your play time, plus a button for friends to get the launcher. Server
  addresses and names are never shown. Turn it off under Settings → Privacy.
- **TRS Client runs smoother.** The HUD is now drawn in one go instead of piece by piece (far fewer draw calls,
  especially on Minecraft 1.20–1.21.1), “Hide entities behind walls” works in the background and no longer causes
  stutters, capes, badges and the online features do less work per frame, and saving settings never makes the game
  wait for the disk. The Colors module needs less work per frame, too.
- **Dynamic FPS: no AFK limit by default.** Standing still no longer lowers your FPS – that also keeps FPS
  measurements and recordings honest. The limits in the background and when minimized stay; the AFK limit can
  still be switched on.
- **Shader packs:** while a shader pack is active (Iris, Oculus or OptiFine), Colors pauses – the pack does its own
  color work – and hiding entities behind walls rests, so every shadow stays in place.
- **Armor display across.** New setting “Orientation”: vertical as before or horizontal – the items in one row with
  the durability below. The HUD editor sizes and anchors it correctly.
- **New TRS badge:** a small glowing redstone dust pile in front of TRS players’ names in the tab list and above
  their heads.
- The clip keys (F9/F10) never make the game wait for the launcher connection.

### Deutsch

- **Kein Einfrieren mehr, wenn Spieler kommen oder gehen.** Mit dem TRS Client konnte das Spiel bis zu einer Minute
  einfrieren, sobald ein anderer Spieler online kam oder ging – bei beiden gleichzeitig. Behoben im TRS Client
  0.4.1, der sich automatisch aktualisiert.
- **Flüssigere Bilder, weniger Ruckler.** Der FPS-Boost beim Start nutzt jetzt eine Speicherbereinigung, die für
  das Spiel gemacht ist (G1 mit kurzen Pausen); ZGC kommt erst ab 12 GB Arbeitsspeicher zum Einsatz, wo es nicht
  mehr hängt. Die Einstellungen richten sich nach der Java, mit der das Spiel wirklich startet – auch nach deiner
  eigenen.
- **Eigene JVM-Argumente schalten den Boost nicht mehr ab.** Nur was du selbst angibst, ersetzt die Werte des
  Launchers (eigene Speichergröße, eigene Speicherbereinigung oder gleiche Option gewinnt); der Rest bleibt
  abgestimmt.
- **Arbeitsspeicher passend zu deinem PC.** Neue Installationen starten mit 6 GB auf PCs ab 16 GB, mit 4 GB ab
  8 GB und darunter mit der Hälfte. Ist mehr eingestellt, als der PC hat, bekommt das Spiel höchstens deinen
  Speicher minus 2 GB, damit Windows nicht auslagern muss. Bestehende Einstellungen bleiben.
- **FPS-Boost mit Shadern – Stufe wählbar.** Beim Anlegen einer Instanz und bei „Preset anwenden“ gibt es den
  FPS-Boost jetzt in drei Stufen: **Max FPS** (nur Optimierungen, für schwache PCs), **Shader leicht** (dazu Iris
  und der sehr sparsame Shader MakeUp – Ultra Fast) und **Shader schön** (dazu Iris und Complementary Reimagined).
  Der Shader ist gleich eingeschaltet; mit der Taste **K** schaltest du Shader im Spiel an und aus. Shader gibt es
  mit Fabric, Quilt und NeoForge; sonst werden sie übersprungen und in der Zusammenfassung genannt. Nvidium lässt
  sich nicht mit Shadern kombinieren und bleibt dann weg. Neue Instanzen starten weiterhin mit Max FPS.
- **Echte Gesichter bei Freunden.** Die Freundesliste und die Admin-Spielersuche zeigen das Minecraft-Gesicht jedes
  Spielers statt eines Platzhalters.
- **FPS-Boost für bestehende Instanzen.** Instanzen mit Modloader, aber ohne Sodium, Embeddium oder OptiFine zeigen
  jetzt einen kleinen Hinweis mit Stufenwahl – ein Klick installiert den Boost. Einmal ausgeblendet, bleibt er für
  diese Instanz weg; bei Modpacks erscheint er nie.
- **Zeig auf Discord, was du spielst.** Ist Discord offen, steht auf deinem Profil jetzt „Spielt TRS Launcher“ – beim
  Spielen auch Minecraft-Version, Modloader und Spielzeit, dazu ein Knopf, mit dem sich Freunde den Launcher holen
  können. Server-Adressen und Namen werden nie gezeigt. Abschaltbar unter Einstellungen → Datenschutz.
- **TRS Client läuft flüssiger.** Das HUD wird jetzt in einem Rutsch gezeichnet statt Stück für Stück (viel
  weniger Zeichenaufrufe, vor allem in Minecraft 1.20–1.21.1), „Hinter Wänden ausblenden“ rechnet im Hintergrund und
  sorgt nicht mehr für Ruckler, Umhänge, Abzeichen und die Online-Funktionen machen weniger Arbeit je Bild, und
  beim Speichern der Einstellungen wartet das Spiel nie auf die Festplatte. Auch das Modul „Farben“ braucht je Bild
  weniger Arbeit.
- **Dynamische FPS: keine AFK-Bremse mehr als Standard.** Wer stillsteht, verliert keine FPS mehr – so bleiben auch
  FPS-Messungen und Aufnahmen ehrlich. Die Grenzen im Hintergrund und minimiert bleiben; die AFK-Grenze lässt sich
  weiterhin einschalten.
- **Shaderpacks:** Solange ein Shaderpack aktiv ist (Iris, Oculus oder OptiFine), pausiert „Farben“ – das Pack
  färbt selbst – und das Ausblenden hinter Wänden ruht, damit jeder Schatten bleibt.
- **Rüstungsanzeige quer.** Neue Einstellung „Ausrichtung“: senkrecht wie bisher oder waagerecht – die Gegenstände
  in einer Zeile, die Haltbarkeit darunter. Der HUD-Editor passt Größe und Ankerung richtig an.
- **Neues TRS-Abzeichen:** ein kleines, leuchtendes Häufchen Redstone-Staub vor den Namen von TRS-Spielern in der
  Tabliste und über ihren Köpfen.
- Die Clip-Tasten (F9/F10) lassen das Spiel nie auf die Verbindung zum Launcher warten.

## 0.5.0 – 2026-09-24 – The Showtime Update | Das Showtime-Update

<!-- banner: accent=#ffc24b motif=/news/0.5.0/banner.png -->
<!-- shots:
/news/0.5.0/emote-wheel.png | The emote wheel in the TRS Client | Das Emote-Rad im TRS Client
/news/0.5.0/redstone-overlay.png | Redstone tools: signal strength over every dust | Redstone-Werkzeuge: Signalstärke über jedem Staub
/news/0.5.0/cape-physics.png | Cape physics with a live preview | Umhang-Physik mit Live-Vorschau
/news/0.5.0/zoom.png | Smooth zoom | Weicher Zoom
/news/0.5.0/colors.png | Toggle sprint and sneak, key strokes and colourful HUD modules | Sprinten und Schleichen umschalten, Tastenanzeige und farbige HUD-Module
-->

### English

- **TRS Client 0.4.0 – show yourself.** Hold **G** for the emote wheel and wave, dance or cheer – other TRS players
  see it. New comfort keys: smooth **zoom** (V), **freelook** (Left Alt) and toggle sprint/sneak.
- **Redstone tools.** See the signal strength over every piece of dust, a redstone overlay (F6) and a clock
  meter for your circuits – right in the game.

- **Mod presets.** Create your own presets (e.g. “My basics”) with mods, resource packs and shaders from
  Modrinth, tick them when creating an instance or apply them later – mark a preset as “always automatic” and it’s
  preselected every time. Each mod is only installed if there’s a version for your Minecraft version and loader
  (with its required dependencies); anything else is skipped and named in a short summary. Share presets with
  friends as a small file.
- **Ready-made TRS presets:** FPS boost (the old performance pack, now also with fixes for Forge 1.12.2/1.8.9),
  Voice chat (Simple Voice Chat), Replay (Flashback, otherwise ReplayMod) and Nvidium for NVIDIA cards from GTX 16xx.
- **More FPS at launch.** Tuned Java settings per Java version and memory (ZGC or G1, memory reserved up front),
  optional higher process priority, and the dedicated graphics card is now only set for the launcher’s own Java –
  switching it off undoes it.
- **Clips & recording (like ShadowPlay/Medal).** Turn it on under Settings → Clips: while a game from the launcher
  runs, **F9** saves the last 15–120 seconds as an MP4 and **F10** starts and stops a normal recording. Only the
  game window is recorded, with system sound (microphone optional, off by default), using your graphics card
  (NVIDIA, AMD, Intel) or software as a fallback. The recorder (FFmpeg) is downloaded once when you switch it on.
  The new **Clips** page plays, renames, deletes and shows your clips, with running recordings and storage use;
  a storage limit moves the oldest clips to the recycle bin. Everything stays on your PC. Off by default.
- **TRS Client: clip keys and recording display.** F9/F10 (changeable in the Minecraft controls) with a small HUD
  element – red dot and time while recording, "Clip saved (30 s)" – movable in the HUD editor. Without the TRS
  Launcher the keys only show a hint.
- **TRS capes load again.** The cape page failed as soon as one of the new HD capes (up to 512×256) was in the
  list.
- **Modpack downloads no longer give up so quickly.** Better MC and other packs sometimes list a file size that is
  off by one byte – the launcher now trusts the file's checksum instead and installs them. Dropped connections,
  timeouts and busy servers are retried patiently for about a minute; only files that really don't exist fail
  right away.
- **Changelog.** Every update now comes with notes in English and German, shown once after updating.
- **Publisher.** The launcher now shows "Theredstonee" as publisher in Windows (apps list and file properties).
- **CurseForge is here.** Discover now has a Modrinth ⇄ CurseForge switch: search mods, resource packs, shaders and
  data packs on CurseForge with the same filters, open project pages, pick versions, and install – dependencies come
  along, and updates are found just like for Modrinth content. CurseForge modpacks (also as a downloaded .zip) become a
  new instance. If an author only allows downloads on CurseForge itself, the launcher doesn't sneak around that: it
  shows the files with a button to their CurseForge page and picks them up from your downloads folder automatically.
- **TRS Client: cape settings and colors.** Cape Physics now has its own settings like WaveyCapes – style (smooth or
  blocky), wind (off, waves, gusts), movement (vanilla, swinging, calm "Dungeons"), gravity, lift when running,
  stiffness and detail – with a live, turning preview of your own player and a reset button; saved in your
  profiles. The new "Colors" module adjusts saturation (0–200 %), contrast, brightness, vibrance and color
  temperature of the game image right away, while the HUD and menus keep their colors.
- **TRS Client: new "Performance" category for more FPS.** *FPS Boost* sets everything to Low, Medium or High with
  one click and shows the FPS before and after; a performance check finds FPS killers in your video settings
  (VSync, simulation distance, render distance, Fabulous graphics, onboard graphics although a graphics card is
  installed …) and fixes them with one click – every change can be undone, even after a restart. *Dynamic FPS*
  limits the frame rate in the background, minimized and when you are AFK and makes the game quieter; full FPS the
  moment you come back. *Entity Culling* skips mobs behind walls and far away mobs, chests, signs, dropped items,
  item frames and name tags; *Particles* sets a limit and switches off explosions, rain splashes or smoke; *World
  Details* hides sky, stars, fog, rain/snow and texture animations. Mods like Sodium, OptiFine, EntityCulling or
  Dynamic FPS are detected – their part is left to them ("taken over by …"). Everything is saved in your profiles.
- **New address for the TRS services.** Launcher and TRS Client now talk to trs-launcher.theredstonee.de, where the
  TRS website will live too. The old address keeps working, so capes, friends and your online status carry on
  without you doing anything.
- **Website sign-in.** TRS admins can sign in to the website with the launcher: the website shows a code, enter it
  under Admin, Settings → Privacy or via Ctrl+K ("Confirm website sign-in") and confirm. The launcher clearly asks
  first – only confirm if you are on the website yourself right now, and never give the code to anyone.
- **Linux support.** The launcher now runs on Linux – Arch, Ubuntu/Debian, Fedora and others – as AppImage (updates
  itself), .deb, .rpm, in the AUR (`trs-launcher-bin`) and ready for Flatpak. Java, all mod loaders and old versions
  like 1.8.9 work just like on Windows; sign-in keys go into your system keyring. Instances from Prism Launcher,
  MultiMC, the Modrinth App (also as Flatpak) and `~/.minecraft` can be imported. Windows-only features (firewall
  helper, clips) are hidden there for now.
- **Dedicated GPU on Linux too.** On laptops with two graphics chips, the game runs on the stronger one
  (PRIME offload).
- **Every update has its own banner.** The update card, the news and the update post show a banner with the
  update's name, its own colour and a pixel-art picture – the same redstone look every time. Older updates got
  their names and pictures too.

### Deutsch

- **TRS Client 0.4.0 – zeig dich.** Halte **G** für das Emote-Rad und winke, tanze oder jubel – andere TRS-Spieler
  sehen es. Neue Komfort-Tasten: weicher **Zoom** (V), **Freelook** (linke Alt-Taste) und Sprinten/Schleichen zum
  Umschalten.
- **Redstone-Werkzeuge.** Sieh die Signalstärke über jedem Staub, ein Redstone-Overlay (F6) und einen Takt-Messer
  für deine Schaltungen – direkt im Spiel.

- **Mod-Presets.** Eigene Presets anlegen (z. B. „Meine Basics“) mit Mods, Ressourcenpaketen und Shadern von
  Modrinth, beim Anlegen einer Instanz ankreuzen oder später anwenden – als „immer automatisch“ markiert, sind sie
  jedes Mal vorausgewählt. Jede Mod wird nur installiert, wenn es eine Version für deine Minecraft-Version und
  deinen Loader gibt (samt Pflicht-Abhängigkeiten); alles andere wird übersprungen und in einer kurzen
  Zusammenfassung genannt. Presets lassen sich als kleine Datei mit Freunden teilen.
- **Fertige TRS-Presets:** FPS-Boost (das bisherige Performance-Paket, jetzt auch mit Fixes für Forge 1.12.2/1.8.9),
  Voice Chat (Simple Voice Chat), Replay (Flashback, sonst ReplayMod) und Nvidium für NVIDIA-Karten ab GTX 16xx.
- **Mehr FPS beim Start.** Abgestimmte Java-Einstellungen je Java-Version und Speicher (ZGC oder G1, Speicher gleich
  reserviert), auf Wunsch höhere Prozesspriorität, und die leistungsstarke Grafikkarte wird nur noch für das Java
  des Launchers eingetragen – Abschalten nimmt es wieder zurück.
- **Clips & Aufnahme (wie ShadowPlay/Medal).** Unter Einstellungen → Clips einschalten: Solange ein Spiel aus dem
  Launcher läuft, speichert **F9** die letzten 15–120 Sekunden als MP4, **F10** startet und stoppt eine normale
  Aufnahme. Aufgenommen wird nur das Spielfenster, mit Systemton (Mikrofon wählbar, standardmäßig aus), über die
  Grafikkarte (NVIDIA, AMD, Intel) oder notfalls per Software. Die Aufnahme-Komponente (FFmpeg) wird beim
  Einschalten einmal geladen. Die neue Seite **Clips** spielt Clips ab, benennt sie um, löscht sie und zeigt sie
  im Ordner – mit laufenden Aufnahmen und Speicherplatz; ein Speicher-Limit schiebt die ältesten Clips in den
  Papierkorb. Alles bleibt auf deinem PC. Standardmäßig aus.
- **TRS Client: Clip-Tasten und Aufnahme-Anzeige.** F9/F10 (in der Minecraft-Steuerung änderbar) mit kleinem
  HUD-Element – roter Punkt und Zeit während der Aufnahme, „Clip gespeichert (30 s)“ – im HUD-Editor verschiebbar.
  Ohne TRS Launcher zeigen die Tasten nur einen Hinweis.
- **TRS-Umhänge laden wieder.** Die Umhang-Seite schlug fehl, sobald einer der neuen HD-Umhänge (bis 512×256) in
  der Liste war.
- **Modpack-Downloads geben nicht mehr so schnell auf.** Better MC und andere Packs geben manchmal eine um ein Byte
  falsche Dateigröße an – der Launcher vertraut jetzt der Prüfsumme der Datei und installiert sie. Abgebrochene
  Verbindungen, Zeitüberschreitungen und ausgelastete Server werden rund eine Minute lang geduldig wiederholt; nur
  Dateien, die es wirklich nicht gibt, schlagen sofort fehl.
- **Changelog.** Jedes Update bringt jetzt Hinweise auf Englisch und Deutsch mit, die einmal nach dem Update
  erscheinen.
- **Herausgeber.** Der Launcher nennt in Windows jetzt „Theredstonee“ als Herausgeber (App-Liste und
  Dateieigenschaften).
- **CurseForge ist da.** „Entdecken“ hat jetzt einen Umschalter Modrinth ⇄ CurseForge: Mods, Ressourcenpakete, Shader
  und Datenpakete auf CurseForge mit denselben Filtern suchen, Projektseiten ansehen, Versionen wählen und installieren
  – Abhängigkeiten kommen mit, und Updates werden genauso gefunden wie bei Modrinth-Inhalten. CurseForge-Modpacks
  (auch als heruntergeladene .zip) werden zur neuen Instanz. Erlaubt ein Autor Downloads nur direkt auf CurseForge,
  umgeht der Launcher das nicht: Er zeigt die Dateien mit einem Knopf zur CurseForge-Seite und übernimmt sie
  automatisch aus deinem Download-Ordner.
- **TRS Client: Umhang-Einstellungen und Farben.** Die Umhang-Physik hat jetzt eigene Einstellungen wie
  WaveyCapes – Stil (glatt oder blockig), Wind (aus, Wellen, Böen), Bewegung (Vanilla, schwingend, ruhig wie in
  „Dungeons“), Schwerkraft, Anhebung beim Laufen, Steifheit und Detailstufe – mit einer drehenden Live-Vorschau
  deines Spielers und einem Knopf zum Zurücksetzen; gespeichert in deinen Profilen. Das neue Modul „Farben“
  ändert Sättigung (0–200 %), Kontrast, Helligkeit, Dynamik und Farbtemperatur des Spielbilds sofort, HUD und
  Menüs behalten ihre Farben.
- **TRS Client: neue Kategorie „Leistung“ für mehr FPS.** *FPS-Boost* stellt mit einem Klick alles auf Niedrig,
  Mittel oder Hoch und zeigt die FPS vorher und nachher; ein Leistungs-Check findet FPS-Bremsen in deinen
  Grafikeinstellungen (VSync, Simulationsdistanz, Sichtweite, Grafik „Fabelhaft“, Onboard-Grafik trotz Grafikkarte …)
  und behebt sie per Klick – jede Änderung lässt sich rückgängig machen, auch nach einem Neustart. *Dynamische FPS*
  begrenzt die Bildrate im Hintergrund, minimiert und bei AFK und macht das Spiel leiser; sobald du zurück bist,
  gibt es sofort volle FPS. *Entity-Culling* lässt Mobs hinter Wänden sowie weit entfernte Mobs, Truhen, Schilder,
  Items am Boden, Item-Rahmen und Namensschilder weg; *Partikel* setzt eine Obergrenze und schaltet Explosionen,
  Regen-Spritzer oder Rauch ab; *Welt-Details* blendet Himmel, Sterne, Nebel, Regen/Schnee und Textur-Animationen aus.
  Mods wie Sodium, OptiFine, EntityCulling oder Dynamic FPS werden erkannt – ihren Teil übernehmen sie („übernimmt …“).
  Alles wird in deinen Profilen gespeichert.
- **Neue Adresse für die TRS-Dienste.** Launcher und TRS Client sprechen jetzt mit trs-launcher.theredstonee.de,
  wo künftig auch die TRS-Website liegt. Die alte Adresse funktioniert weiter – Umhänge, Freunde und dein
  Online-Status laufen ohne dein Zutun weiter.
- **Website-Anmeldung.** TRS-Admins können sich mit dem Launcher auf der Website anmelden: Die Website zeigt einen
  Code, den gibst du unter Admin, Einstellungen → Datenschutz oder über Strg+K („Website-Anmeldung bestätigen“)
  ein und bestätigst. Der Launcher fragt vorher deutlich nach – nur bestätigen, wenn du gerade selbst auf der
  Website bist, und den Code niemals weitergeben.
- **Linux-Unterstützung.** Der Launcher läuft jetzt unter Linux – Arch, Ubuntu/Debian, Fedora und andere – als
  AppImage (aktualisiert sich selbst), .deb, .rpm, im AUR (`trs-launcher-bin`) und bereit für Flatpak. Java, alle
  Modloader und alte Versionen wie 1.8.9 funktionieren wie unter Windows; Anmeldeschlüssel liegen im Schlüsselbund
  des Systems. Instanzen aus Prism Launcher, MultiMC, der Modrinth App (auch als Flatpak) und `~/.minecraft` lassen
  sich importieren. Reine Windows-Funktionen (Firewall-Freigabe, Clips) sind dort vorerst ausgeblendet.
- **Starke Grafikkarte auch unter Linux.** Auf Laptops mit zwei Grafikchips läuft das Spiel auf dem stärkeren
  (PRIME-Offload).
- **Jedes Update hat sein eigenes Banner.** Update-Karte, Neuigkeiten und der Beitrag zeigen ein Banner mit dem
  Namen des Updates, einer eigenen Farbe und einem Pixel-Art-Bild – immer im gleichen Redstone-Look. Auch die
  älteren Updates haben ihre Namen und Bilder bekommen.

## 0.4.3 – 2026-09-24 – The Friends Update | Das Freunde-Update

<!-- banner: accent=#ff7ab8 motif=/news/0.4.3/banner.png -->
<!-- shots:
/news/0.4.3/tasks.png | The tasks panel in the title bar while a modpack installs | Die Aufgabenleiste in der Titelleiste, während ein Modpack installiert wird
-->

### English

- **8 languages.** The launcher speaks English (default), German and Spanish, plus French, Polish, Portuguese
  (Brazil), Turkish and Dutch as beta. On first start you pick your language, with a suggestion based on your
  system.
- **TRS capes, badge and friends.** Wear TRS capes (also animated and HD up to 512×256), redeem codes, upload your
  own cape, see the TRS badge, add friends and join their server with one click. Everything only after you agree,
  and you can delete all TRS data at any time.
- **TRS Client 0.3.0.** Translated into the same 8 languages, shows TRS capes and badges in game and lets every cape
  move like cloth.
- **Background installs.** Installs keep running when you leave the page; the tasks panel in the title bar shows
  everything that is installing or running, with pause and cancel.

- **Calmer redstone.** The animated redstone background is slower and more varied, with long cables and the classic
  circuits.
- **Smaller fixes.** Deleting an instance needs just one confirmation, and the big play button no longer shows
  "Play" and "Running" at the same time.

### Deutsch

- **8 Sprachen.** Der Launcher spricht Englisch (Standard), Deutsch und Spanisch, dazu Französisch, Polnisch,
  Portugiesisch (Brasilien), Türkisch und Niederländisch als Beta. Beim ersten Start wählst du deine Sprache, mit
  einem Vorschlag passend zu deinem System.
- **TRS-Umhänge, Abzeichen und Freunde.** Trage TRS-Umhänge (auch animiert und in HD bis 512×256), löse Codes ein,
  lade einen eigenen Umhang hoch, zeig das TRS-Abzeichen, füge Freunde hinzu und tritt ihrem Server mit einem Klick
  bei. Alles erst nach deiner Zustimmung, und du kannst alle TRS-Daten jederzeit löschen.
- **TRS Client 0.3.0.** In dieselben 8 Sprachen übersetzt, zeigt TRS-Umhänge und -Abzeichen im Spiel und lässt jeden
  Umhang wie Stoff schwingen.
- **Installationen im Hintergrund.** Installationen laufen weiter, wenn du die Seite verlässt; die Aufgabenleiste in
  der Titelleiste zeigt alles, was installiert wird oder läuft, mit Pause und Abbrechen.

- **Ruhigerer Redstone.** Der animierte Redstone-Hintergrund ist langsamer und abwechslungsreicher, mit langen Kabeln
  und den klassischen Schaltungen.
- **Kleinere Korrekturen.** Eine Instanz löschst du mit einer einzigen Bestätigung, und der große Spielen-Knopf zeigt
  nicht mehr gleichzeitig „Spielen“ und „Läuft“.

## 0.4.2 – 2026-09-23 – Quiet Updates | Leise Updates

<!-- banner: accent=#a67bff motif=/news/0.4.2/banner.png -->

### English

- **Updates without waiting.** New launcher versions download in the background while you play. When
  one is ready, a button in the title bar restarts into it – while it installs you see a loading screen instead of
  a frozen window.
- **TRS Client updates on its own.** The in-game client now has its own signed update channel, so it can get fixes
  without a new launcher.
- **Redstone everywhere.** The live redstone circuit now runs quietly behind every page, not just the start page.

### Deutsch

- **Updates ohne Warten.** Neue Launcher-Versionen laden im Hintergrund, während du spielst. Ist eine fertig,
  startest du sie mit einem Knopf in der Titelleiste neu – während der Installation siehst du einen Ladebildschirm
  statt eines eingefrorenen Fensters.
- **Der TRS Client aktualisiert sich selbst.** Der Client im Spiel hat jetzt einen eigenen, signierten Update-Kanal
  und bekommt Korrekturen auch ohne neuen Launcher.
- **Redstone überall.** Die lebendige Redstone-Schaltung läuft jetzt ruhig hinter jeder Seite, nicht nur auf der
  Startseite.

## 0.4.1 – 2026-09-23 – Redstone Title | Redstone-Titelbild

<!-- banner: accent=#ff8a3d motif=/news/0.4.1/banner.png -->
<!-- shots:
/news/0.4.1/title-screen.png | The TRS Client title screen | Der Titelbildschirm des TRS Clients
-->

### English

- **A redstone title screen.** The TRS Client greets you with a title screen in the launcher’s redstone look –
  glowing dust, lamps and a torch – in every Minecraft version.
- **The TRS menu in the same style.** Buttons, panels and switches of the TRS menu match the launcher.
- **Smoother menus on 1.20 to 1.21.1.** TRS screens are drawn in one go and stay fluid.

### Deutsch

- **Ein Titelbildschirm aus Redstone.** Der TRS Client begrüßt dich mit einem Titelbildschirm im Redstone-Look des
  Launchers – glühender Staub, Lampen und eine Fackel – in jeder Minecraft-Version.
- **Das TRS-Menü im selben Stil.** Knöpfe, Flächen und Schalter des TRS-Menüs passen zum Launcher.
- **Flüssigere Menüs auf 1.20 bis 1.21.1.** TRS-Fenster werden in einem Rutsch gezeichnet und bleiben flüssig.

## 0.4.0 – 2026-09-23 – The Redstone Update | Das Redstone-Update

<!-- banner: accent=#ff5a4d motif=/news/0.4.0/banner.png -->
<!-- shots:
/news/0.4.0/start.png | The new start page with the redstone circuit | Die neue Startseite mit der Redstone-Schaltung
/news/0.4.0/running.png | While the game runs, the lamp glows | Während das Spiel läuft, leuchtet die Lampe
-->

### English

- **A start page that lives.** A real redstone circuit runs across the top: clocks, pistons, lamps and flickering
  torches. The main line leads to the play button and charges up while your game starts – when it runs, the lamp
  glows.
- **News as a magazine.** One lead story with a big picture, the rest next to it.
- **Your account in the title bar.** Switch accounts right from the top of the window.
- **Skins without waiting.** Edit skins locally; the launcher sends them to Mojang in the background and bundles
  quick changes into one.
- **Calmer notifications.** Identical messages are merged and only a few are shown at once.

### Deutsch

- **Eine Startseite, die lebt.** Oben läuft eine echte Redstone-Schaltung: Takte, Kolben, Lampen und flackernde
  Fackeln. Die Hauptleitung führt zum Spielen-Knopf und lädt sich beim Spielstart auf – läuft das Spiel, leuchtet
  die Lampe.
- **Neuigkeiten als Magazin.** Eine Titelgeschichte mit großem Bild, der Rest daneben.
- **Dein Konto in der Titelleiste.** Wechsle das Konto direkt oben im Fenster.
- **Skins ohne Warten.** Bearbeite Skins lokal; der Launcher schickt sie im Hintergrund an Mojang und fasst schnelle
  Änderungen zusammen.
- **Ruhigere Meldungen.** Gleiche Meldungen werden zusammengefasst, und es sind nur wenige gleichzeitig zu sehen.

## 0.3.1 – 2026-09-23 – Fabric Fix | Fabric-Fix

<!-- banner: accent=#b8c0d0 motif=/news/0.3.1/banner.png -->

### English

- **Fabric starts again.** Fixes a crash when starting Fabric instances from 1.15 to 1.21.8.
- **Off means off.** A TRS Client you switched off for an instance stays off.

### Deutsch

- **Fabric startet wieder.** Behebt einen Absturz beim Start von Fabric-Instanzen von 1.15 bis 1.21.8.
- **Aus heißt aus.** Ein TRS Client, den du für eine Instanz ausgeschaltet hast, bleibt aus.

## 0.3.0 – 2026-09-22 – The HUD Update | Das HUD-Update

<!-- banner: accent=#4fd1e0 motif=/news/0.3.0/banner.png -->
<!-- shots:
/news/0.3.0/trs-menu.png | The TRS menu in game | Das TRS-Menü im Spiel
/news/0.3.0/hud-editor.png | Moving the HUD with the HUD editor | Das HUD mit dem HUD-Editor verschieben
/news/0.3.0/skins.png | Skins & capes with the 3D preview | Skins & Umhänge mit der 3D-Vorschau
/news/0.3.0/gallery.png | The screenshot gallery | Die Screenshot-Galerie
-->

### English

- **A new TRS menu.** Right Shift opens the TRS menu: modules in categories (HUD, PvP, chat, world), search,
  settings for every module and HUD profiles you can switch between.
- **HUD editor.** Drag every display where you want it, scroll to resize, right-click to reset.
- **More for PvP and exploring.** Custom crosshair, hit colour, CPS and keystrokes, waypoints with beams, a minimap
  and chat tools that copy lines without colour codes.
- **Skins & capes page.** Collect skins, try them on in a 3D preview and put them on your account.
- **Screenshot gallery.** All screenshots from all instances in one place.
- **Share instances.** Export an instance as a .mrpack and import pack files.
- **Ctrl+K.** A command palette that finds instances, pages and actions.
- **Fresh look.** A slim icon sidebar, banners for your instances and a start page with a quick start.

### Deutsch

- **Ein neues TRS-Menü.** Die rechte Umschalttaste öffnet das TRS-Menü: Module in Kategorien (HUD, PvP, Chat, Welt),
  Suche, Einstellungen für jedes Modul und HUD-Profile zum Umschalten.
- **HUD-Editor.** Zieh jede Anzeige dorthin, wo du sie willst, Mausrad für die Größe, Rechtsklick setzt zurück.
- **Mehr für PvP und Erkundung.** Eigenes Fadenkreuz, Trefferfarbe, CPS und Tastenanzeige, Wegpunkte mit Strahl,
  eine Minikarte und Chat-Werkzeuge, die Zeilen ohne Farbcodes kopieren.
- **Seite für Skins & Umhänge.** Sammle Skins, probiere sie in einer 3D-Vorschau an und setze sie auf dein Konto.
- **Screenshot-Galerie.** Alle Screenshots aller Instanzen an einem Ort.
- **Instanzen teilen.** Exportiere eine Instanz als .mrpack und importiere Pack-Dateien.
- **Strg+K.** Eine Befehlspalette, die Instanzen, Seiten und Aktionen findet.
- **Frischer Look.** Eine schmale Symbolleiste, Banner für deine Instanzen und eine Startseite mit Schnellstart.

## 0.2.2 – 2026-09-22 – Quiet Firewall | Leise Firewall

<!-- banner: accent=#ffb13d motif=/news/0.2.2/banner.png -->

### English

- **Firewall without a blue window.** The launcher adds its firewall rules directly through Windows instead of a
  PowerShell window.

### Deutsch

- **Firewall ohne blaues Fenster.** Der Launcher legt seine Firewall-Regeln direkt über Windows an statt über ein
  PowerShell-Fenster.

## 0.2.1 – 2026-09-22 – The Library Update | Das Bibliotheks-Update

<!-- banner: accent=#c9853f motif=/news/0.2.1/banner.png -->
<!-- shots:
/news/0.2.1/library.png | The library with your own groups | Die Bibliothek mit eigenen Gruppen
-->

### English

- **A new library.** Square cards, sorting, filters and your own groups.
- **One content list.** Mods, resource packs, shaders and data packs in one table with filter chips, selection and
  bulk actions.
- **A new discover page.** Browse mods, modpacks, resource packs and shaders with big pictures and filters.
- **Settings as a window.** Global and per-instance settings in clear sections that save by themselves, with Java
  per Minecraft version, storage management and start hooks.
- **The TRS Client for (almost) every version.** Fabric from 1.14.4, Forge from 1.7.10 and NeoForge from 1.20.2 up
  to 26.3 – with a new title screen, more HUD modules and PvP features.
- **Clearer update errors.** If a launcher update fails, you see why and can try again.

### Deutsch

- **Eine neue Bibliothek.** Quadratische Karten, Sortieren, Filter und eigene Gruppen.
- **Eine Liste für alle Inhalte.** Mods, Ressourcenpakete, Shader und Datenpakete in einer Tabelle mit Filtern,
  Auswahl und Sammelaktionen.
- **Eine neue Entdecken-Seite.** Stöbere durch Mods, Modpacks, Ressourcen- und Shaderpacks mit großen Bildern und Filtern.
- **Einstellungen als Fenster.** Globale und Instanz-Einstellungen in klaren Bereichen, die sich selbst speichern,
  mit Java pro Minecraft-Version, Speicherverwaltung und Start-Hooks.
- **Der TRS Client für (fast) jede Version.** Fabric ab 1.14.4, Forge ab 1.7.10 und NeoForge ab 1.20.2 bis 26.3 –
  mit neuem Titelbildschirm, mehr HUD-Modulen und PvP-Funktionen.
- **Klarere Update-Fehler.** Schlägt ein Launcher-Update fehl, siehst du warum und kannst es erneut versuchen.

## 0.2.0 – 2026-09-22 – The Project Update | Das Projekt-Update

<!-- banner: accent=#f0c24b motif=/news/0.2.0/banner.png -->
<!-- shots:
/news/0.2.0/content.png | Managing the content of an instance | Die Inhalte einer Instanz verwalten
-->

### English

- **A page for every project.** Every mod and modpack gets its own page with description, gallery, versions and
  dependencies.
- **Images for your instances.** Give every instance its own picture.
- **History and version switching.** See what changed in an instance, switch mods to another version and spot
  downgrades at a glance.
- **More TRS Client.** Now also for NeoForge 1.21.1 and Forge 1.20.1.

### Deutsch

- **Eine Seite für jedes Projekt.** Jede Mod und jedes Modpack hat eine eigene Seite mit Beschreibung, Galerie,
  Versionen und Abhängigkeiten.
- **Bilder für deine Instanzen.** Gib jeder Instanz ihr eigenes Bild.
- **Verlauf und Versionswechsel.** Sieh, was sich in einer Instanz geändert hat, wechsle Mods auf eine andere
  Version und erkenne Downgrades auf einen Blick.
- **Mehr TRS Client.** Jetzt auch für NeoForge 1.21.1 und Forge 1.20.1.

## 0.1.0 – 2026-09-22 – The First Block | Der erste Block

<!-- banner: accent=#6fcf4a motif=/news/0.1.0/banner.png -->
<!-- shots:
/news/0.1.0/start.png | The very first start page | Die allererste Startseite
-->

### English

- **The first TRS Launcher.** Install and start every Minecraft version – Vanilla, Fabric, Quilt, Forge and
  NeoForge – with several Microsoft accounts.
- **Mods and modpacks.** Search, install and update content per instance.
- **Bring what you have.** Import instances from the official launcher, Prism, MultiMC and the Modrinth App, or
  from any folder.
- **Servers, screenshots and updates.** A server list with live status, screenshots per instance and a launcher
  that updates itself.
- **Help when it crashes.** Games run on their own with log files; after a crash the launcher explains what went
  wrong.
- **The TRS Client.** Our own client mod ships with the launcher and is added automatically.

### Deutsch

- **Der erste TRS Launcher.** Installiere und starte jede Minecraft-Version – Vanilla, Fabric, Quilt, Forge und
  NeoForge – mit mehreren Microsoft-Konten.
- **Mods und Modpacks.** Inhalte pro Instanz suchen, installieren und aktualisieren.
- **Nimm mit, was du hast.** Importiere Instanzen aus dem offiziellen Launcher, Prism, MultiMC und der Modrinth App
  oder aus einem beliebigen Ordner.
- **Server, Screenshots und Updates.** Eine Serverliste mit Live-Status, Screenshots pro Instanz und ein Launcher,
  der sich selbst aktualisiert.
- **Hilfe bei Abstürzen.** Spiele laufen eigenständig mit Log-Dateien; nach einem Absturz erklärt der Launcher, was
  schiefging.
- **Der TRS Client.** Unsere eigene Client-Mod kommt mit dem Launcher und wird automatisch hinzugefügt.
