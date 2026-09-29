// Themen-Seiten auf Deutsch. Aufbau und Regeln: shared/landing.ts (gleiche Abschnitte wie Englisch).

import type { LandingTexts } from './landing'

export const landingDe: LandingTexts = {
  common: {
    topics: 'Themen',
    faqTitle: 'Fragen und Antworten',
    related: 'Mehr über den TRS Launcher',
    download: 'Kostenlos herunterladen',
    features: 'Alle Funktionen',
    allQuestions: 'Alle Fragen',
    note: 'Kostenlos · Open Source (GPL-3.0) · Windows & Linux',
    learnMore: 'Mehr erfahren',
    onThisPage: 'Auf dieser Seite',
  },
  pages: {
    'minecraft-launcher': {
      seo: {
        title: 'Kostenloser Minecraft Launcher für Windows & Linux – TRS',
        description:
          'Der TRS Launcher ist ein kostenloser Open-Source-Launcher für Minecraft: Java Edition: jede Version, Microsoft-Login, Mods, Clips und Freunde. Windows & Linux.',
      },
      name: 'Minecraft Launcher',
      teaser: 'Der kostenlose Open-Source-Launcher für Minecraft: Java Edition – jede Version, Microsoft-Login, Clips und Freunde.',
      kicker: 'Launcher für Minecraft: Java Edition',
      title: 'Der kostenlose Minecraft Launcher für Windows und Linux',
      lead: 'Der TRS Launcher – der Redstone Launcher von TheRedstonee – installiert und startet jede Version von Minecraft: Java Edition. Er ist kostenlos, Open Source unter GPL-3.0 und bringt alles mit, was du zum Spielen brauchst: Instanzen, Modloader, deine Microsoft-Konten, Clips, Freunde und eine eigene Client-Mod, den TRS Client.',
      sections: [
        {
          id: 'instances',
          title: 'Jede Minecraft-Version in ihrer eigenen Instanz',
          text: [
            'Eine Instanz ist eine eigene Minecraft-Installation mit eigener Version, eigenen Mods, Welten und Einstellungen. Leg so viele an, wie du willst – eine für die neueste Version, eine für 1.8.9-PvP, eine für ein großes Modpack –, und sie kommen sich nie in die Quere. Die Bibliothek zeigt sie als Karten mit Sortierung, Filtern und eigenen Gruppen.',
            'Jede Version von 1.7.10 bis zur neuesten steht bereit, Snapshots inklusive, mit Vanilla, Fabric, Quilt, Forge oder NeoForge. Das passende Java lädt der Launcher für jede Version selbst – du musst Java nie von Hand installieren oder auswählen.',
          ],
          points: [
            'Vanilla, Fabric, Quilt, Forge und NeoForge mit einem Klick',
            'Java wird für jede Minecraft-Version automatisch geladen',
            'Jede Instanz hat Reiter für Inhalte, Dateien, Welten, Screenshots, Verlauf und Logs',
          ],
          shot: { file: '0.2.1/library.png', alt: 'Die Instanz-Bibliothek des TRS Launchers mit Minecraft-Instanzen in eigenen Gruppen', caption: 'Die Bibliothek mit eigenen Gruppen' },
        },
        {
          id: 'accounts',
          title: 'Microsoft-Login und mehrere Konten',
          text: [
            'Du meldest dich mit deinem Microsoft-Konto auf der Anmeldeseite von Microsoft selbst an – der Launcher sieht dein Passwort nie. Füge mehrere Konten hinzu und wechsle direkt in der Titelleiste zwischen ihnen. Mit dem TRS Client wechselst du das Konto sogar im Spiel, ganz ohne Neustart.',
            'Skins und Umhänge haben eine eigene Seite mit 3D-Vorschau: Skins sammeln, anprobieren und auf dein Konto legen. Mit den freiwilligen TRS-Diensten folgt dir deine Skin-Sammlung auf jeden PC.',
          ],
        },
        {
          id: 'import',
          title: 'Instanzen aus anderen Launchern mitnehmen',
          text: [
            'Du spielst schon mit einem anderen Launcher? „Aus anderem Launcher importieren“ findet die Launcher auf deinem PC und zeigt für jede Instanz, was mitkommt: Welten, Mods, Ressourcen- und Shaderpakete, Einstellungen und deine Serverliste. Minecraft-Version und Modloader werden für dich erkannt, und importierte Mods bleiben aktualisierbar.',
            'Anmeldedaten anderer Launcher werden nie gelesen oder kopiert, und ihre Dateien werden nur gelesen – deine alte Einrichtung bleibt genau so, wie sie war.',
          ],
        },
        {
          id: 'together',
          title: 'Clips, Freunde, Chat und Welt-Hosting',
          text: [
            'Drück im Spiel F9, um die letzten Momente als Video-Clip zu speichern, oder F10, um eine ganze Runde aufzunehmen. Aufgenommen wird nur das Spielfenster, und Clips bleiben auf deinem PC; in der Clip-Galerie spielst du sie ab, schneidest sie zu und teilst sie. Clips gibt es unter Windows.',
            'Mit den freiwilligen TRS-Online-Funktionen bekommst du eine Freundesliste, Direktnachrichten und Gruppen-Chats – im Launcher und im Spiel. Hoste deine Einzelspielerwelt für bis zu 10 Freunde ohne Portfreigabe: Sie sehen sie unter Sozial → Welten und treten mit einem Klick oder per Beitrittscode bei.',
          ],
          shot: { file: '0.8.0/worlds.png', alt: 'Sozial → Welten im TRS Launcher mit der offenen Minecraft-Welt eines Freundes zum Beitreten', caption: 'Welten von Freunden direkt aus dem Launcher beitreten' },
        },
        {
          id: 'crash-helper',
          title: 'Ein Absturz-Helfer, der erklärt, was schiefging',
          text: [
            'Stürzt Minecraft ab, liest der Launcher den Absturzbericht und das Log und erklärt in klaren Worten, was passiert ist: welche Mods sich streiten, welche Abhängigkeit fehlt oder ob Arbeitsspeicher, Java-Version oder Grafiktreiber das Problem sind. Knöpfe wie „Mod deaktivieren“, „Abhängigkeit installieren“ oder „RAM erhöhen“ beheben es mit einem Klick, und jede Änderung wird vorher bestätigt.',
            'Die Auswertung läuft nur auf deinem PC. Der Logs-Reiter hat Suche, Filter und aufklappbare Stacktraces, und wenn du Hilfe brauchst, teilst du ein Log als Link.',
          ],
          shot: { file: '0.10.0/crash-helper.png', alt: 'Der Absturz-Helfer des TRS Launchers erklärt einen Minecraft-Absturz und bietet einen Knopf zum Beheben', caption: 'Die Ursache in klaren Worten und ein Knopf zum Beheben' },
        },
        {
          id: 'extras',
          title: 'Erfolge, automatische Updates und acht Sprachen',
          text: [
            'Erfolge belohnen Spielzeit, das Ausprobieren von Launcher-Funktionen und die Community; manche bringen einen Umhang oder ein Emote. Neue Launcher-Versionen laden im Hintergrund, während du spielst, und installieren sich mit einem Klick, und der TRS Client bekommt Fehlerbehebungen über einen eigenen Update-Kanal.',
            'Der Launcher spricht Englisch, Deutsch und Spanisch, dazu Französisch, Polnisch, Portugiesisch (Brasilien), Türkisch und Niederländisch als Beta.',
          ],
          shot: { file: '0.14.0/achievements.png', alt: 'Erfolge im TRS Launcher mit Punkten, Seltenheit und Belohnungen', caption: 'Erfolge mit Punkten, Seltenheit und Belohnungen' },
        },
        {
          id: 'privacy',
          title: 'Privat und Open Source',
          text: [
            'Der TRS Launcher hat keine Telemetrie, keine Analyse und keine Werbung. Online-Funktionen wie Umhänge, Freunde und Chat gehen erst an, wenn du zustimmst, und du kannst alle TRS-Daten jederzeit löschen. Der komplette Quellcode liegt unter GPL-3.0 auf GitHub – jeder kann nachsehen, wie er funktioniert.',
            'Unter Windows 10 und 11 installiert er sich für deinen Benutzer ohne Administratorrechte. Unter Linux läuft er als AppImage, .deb, .rpm oder aus dem AUR.',
          ],
          link: { to: '/download', label: 'Für Windows oder Linux herunterladen' },
        },
      ],
      faq: [
        { q: 'Ist der TRS Launcher ein kostenloser Minecraft Launcher?', a: 'Ja. Der TRS Launcher ist kostenlos und Open Source unter GPL-3.0. Du brauchst nur dein eigenes Konto für Minecraft: Java Edition.' },
        { q: 'Ist er für die Java Edition oder die Bedrock Edition?', a: 'Der TRS Launcher ist für Minecraft: Java Edition gemacht. Die Bedrock Edition wird nicht unterstützt.' },
        { q: 'Auf welchen Betriebssystemen läuft er?', a: 'Auf Windows 10 und 11 (64 Bit) und aktuellen 64-Bit-Linux-Distributionen wie Arch, Ubuntu, Debian und Fedora.' },
        { q: 'Kann ich mehr als ein Microsoft-Konto nutzen?', a: 'Ja. Füge beliebig viele Konten hinzu und wechsle in der Titelleiste zwischen ihnen – mit dem TRS Client sogar im Spiel.' },
        { q: 'Kann ich meine Instanzen aus einem anderen Launcher mitnehmen?', a: 'Ja. Welten, Mods, Ressourcen- und Shaderpakete, Einstellungen und Serverlisten kommen mit, Version und Modloader werden für dich erkannt. Anmeldedaten anderer Launcher werden nie angefasst.' },
      ],
      cta: { title: 'Hol dir den kostenlosen Minecraft Launcher', text: 'Lade den TRS Launcher für Windows oder Linux herunter – der TRS Client ist dabei.' },
    },

    'redstone-launcher': {
      seo: {
        title: 'Redstone Launcher für Minecraft – TRS Launcher & Redstone-Tools',
        description:
          'Der TRS Launcher ist der Redstone-Minecraft-Launcher: Redstone-Design plus TRS Client mit Signalstärke, Redstone-Overlay, Takt-Messer und Schaltungs-Bibliothek.',
      },
      name: 'Redstone Launcher',
      teaser: 'Redstone-Design von der Startseite bis ins Spielmenü, dazu Redstone-Werkzeuge und eine Schaltungs-Bibliothek im TRS Client.',
      kicker: 'Der Redstone Launcher',
      title: 'Der Minecraft Launcher, gemacht für Redstone',
      lead: 'Der TRS Launcher von TheRedstonee ist aus zwei Gründen der Redstone Launcher: Er ist im Redstone-Look gebaut, von der Startseite bis zu den Menüs im Spiel, und der TRS Client darin bringt Werkzeuge mit, die dir helfen, Redstone-Schaltungen zu bauen, zu verstehen und Fehler darin zu finden.',
      sections: [
        {
          id: 'design',
          title: 'Ein Launcher im Redstone-Look',
          text: [
            'Öffne den Launcher, und über die Startseite läuft eine echte Redstone-Schaltung: Takte, Kolben, Lampen und flackernde Fackeln. Die Hauptleitung führt zum Spielen-Knopf und lädt sich auf, während dein Spiel startet – sobald Minecraft läuft, leuchtet die Lampe. Die Schaltung läuft leise hinter jeder Seite, und in den Einstellungen kannst du die Animationen reduzieren, wenn du magst.',
            'Der TRS Client führt den Look im Spiel fort: ein Redstone-Titelbildschirm mit deinem eigenen Skin auf einer langsam drehenden Redstone-Drehscheibe, Steinknöpfe, die aufleuchten, Serverkarten mit Redstone-Ping-Balken und Ladebildschirme mit einer Reihe Redstone-Lampen. Jedes Menü kann zurück zum klassischen Look.',
          ],
          shot: { file: '0.4.0/running.png', alt: 'Die Startseite des TRS Launchers mit Redstone-Schaltung und leuchtender Lampe, während Minecraft läuft', caption: 'Während das Spiel läuft, leuchtet die Lampe' },
        },
        {
          id: 'signal',
          title: 'Signalstärke, Verzögerung und Ausgabe sehen',
          text: [
            'Schau Redstone-Staub, einen Verstärker, einen Komparator oder einen Kolben an, und der TRS Client zeigt die Signalstärke von 0 bis 15, die Verzögerung des Verstärkers, den Modus des Komparators und seine Ausgabe. Kein Rätselraten mehr, warum eine Leitung nach 15 Blöcken dunkel wird.',
            'Das Redstone-Overlay (F6) geht noch einen Schritt weiter: Es schreibt die Signalstärke als Zahl über jeden Redstone-Staub in deiner Nähe – ideal für lange Leitungen, Item-Sortierer und kompakte Bauten.',
          ],
          shot: { file: '0.5.0/redstone-overlay.png', alt: 'Redstone-Signalstärke als Zahl über jedem Redstone-Staub im TRS Client', caption: 'Das Redstone-Overlay zeigt das Signal jedes Staubs' },
        },
        {
          id: 'clock',
          title: 'Redstone-Takte in Ticks messen',
          text: [
            'Der Takt-Messer misst Frequenz, Periode und Pulslänge eines Redstone-Takts in Ticks und zeichnet das Signal auf einem kleinen Oszilloskop. Damit prüfst du einen Takt, stimmst eine Verstärker-Schleife ab oder findest heraus, warum eine Farm zu oft auslöst.',
          ],
          points: [
            'Frequenz, Periode und Pulslänge in Spiel-Ticks',
            'Ein kleines Oszilloskop zeigt das Signal über die Zeit',
            'Das Modul-Paket „Redstone“ richtet den Client mit einem Klick fürs Bauen ein',
          ],
        },
        {
          id: 'circuits',
          title: 'Eine Schaltungs-Bibliothek mit Geisterblöcken',
          text: [
            'Die Schaltungs-Bibliothek im TRS Client sammelt fertige Redstone-Schaltungen: Logikgatter, eine Verstärker-Kette, einen Fackelturm, Takte, Speicher wie RS-Latch, T-Flipflops und D-Latch, Puls-Schaltungen, eine 2×2-Kolbentür, eine versteckte Treppe, Farm-Grundlagen wie Item-Filter, automatischen Ofen und Item-Aufzug sowie Anzeigen. Zu jeder gibt es eine Erklärung, Schwierigkeit, Größe, eine Materialliste, die dein Inventar prüft, die nötige Minecraft-Version und ob sie auf Servern zuverlässig läuft – dazu eine 3D-Vorschau, die du Schicht für Schicht ansehen kannst.',
            'Wähle eine Schaltung und setze sie als Vorlage in deine Welt. Geisterblöcke zeigen, was wohin gehört: grün, wenn ein Block stimmt, rot, wenn er falsch ist, und grau, solange er fehlt – mit Fortschrittsbalken und Ansicht Schicht für Schicht. Das ist reine Anzeige: Nichts wird für dich gebaut und nichts an den Server geschickt. Vorlagen gehen ab Minecraft 1.8.9 (nicht auf 1.7.10 und 1.13.2).',
          ],
          shot: { file: '0.10.0/circuit-ghost.png', alt: 'Eine Redstone-Schaltung als Geisterblöcke in einer Minecraft-Welt mit dem TRS Client', caption: 'Eine Schaltung Block für Block nach Vorlage bauen' },
        },
        {
          id: 'share-circuits',
          title: 'Neue Schaltungen ohne Mod-Update',
          text: [
            'Die Schaltungen kommen vom TRS-Server, neue erscheinen also ohne Mod-Update, und eine lokale Kopie funktioniert auch offline. Etwas Cleveres gebaut? Markiere es in deiner Welt (bis 16×16×16 Blöcke), gib ihm Namen, Kategorie und eine kurze Beschreibung und reiche es ein – das Team prüft jede Einreichung.',
            'Alle veröffentlichten Schaltungen stehen auch auf dieser Website, jede mit eigener Seite und einem Download für den Konstruktionsblock.',
          ],
          shot: { file: '0.10.0/circuits.png', alt: 'Die Schaltungs-Bibliothek des TRS Client mit fertigen Redstone-Schaltungen, Erklärung und Material', caption: 'Fertige Redstone-Schaltungen mit Erklärung und Material' },
          link: { to: '/circuits', label: 'Zur Schaltungs-Bibliothek' },
        },
        {
          id: 'maker',
          title: 'Von einem Redstone-Fan gemacht',
          text: [
            'Der TRS Launcher wird von TheRedstonee entwickelt, einem Redstone-Fan, der die Werkzeuge baut, die er selbst benutzen will. Deshalb ist Redstone im TRS Client ein vollwertiges Thema neben Performance und PvP, deshalb sieht der Launcher so aus, wie er aussieht – und deshalb wird er oft einfach „der Redstone Launcher“ genannt.',
          ],
        },
      ],
      faq: [
        { q: 'Warum heißt der TRS Launcher Redstone Launcher?', a: 'Weil Redstone sein Kern ist: Launcher und TRS Client sind im Redstone-Look gestaltet, und der TRS Client hat Redstone-Werkzeuge wie Signalstärke, Redstone-Overlay, Takt-Messer und eine Schaltungs-Bibliothek. Entwickelt wird er von TheRedstonee.' },
        { q: 'Welche Redstone-Werkzeuge hat der TRS Client?', a: 'Signalstärke für Staub, Verstärker, Komparatoren und Kolben, ein Redstone-Overlay mit dem Signal über jedem Staub (F6), einen Takt-Messer in Ticks und eine Schaltungs-Bibliothek mit Vorlagen aus Geisterblöcken.' },
        { q: 'Bauen die Geisterblöcke die Schaltung für mich?', a: 'Nein. Vorlagen sind reine Anzeige: Sie zeigen, was wohin gehört, und jeden Block setzt du selbst. An den Server wird nichts geschickt.' },
        { q: 'Kann ich eigene Redstone-Schaltungen teilen?', a: 'Ja. Markiere eine Schaltung in deiner Welt (bis 16×16×16 Blöcke), gib Namen, Kategorie und Beschreibung an und reiche sie mit deiner TRS-Anmeldung ein. Nach der Prüfung erscheint sie in der Bibliothek und auf der Website.' },
        { q: 'Gibt es das Redstone-Design nur im Launcher?', a: 'Nein. Der TRS Client bringt Redstone-Titelbildschirm und -Menüs in jeder unterstützten Minecraft-Version ins Spiel. Jedes Menü kann zurück zum klassischen Look.' },
      ],
      cta: { title: 'Bessere Schaltungen bauen', text: 'Lade den TRS Launcher kostenlos herunter – der TRS Client mit allen Redstone-Werkzeugen ist dabei.' },
    },

    modpacks: {
      seo: {
        title: 'Minecraft Modpacks: Fabric, Forge, NeoForge & Quilt | TRS',
        description:
          'Minecraft-Mods und Modpacks von Modrinth und CurseForge im TRS Launcher: Fabric, Forge, NeoForge und Quilt, Packs per Code teilen, eigene Änderungen bleiben.',
      },
      name: 'Mods & Modpacks',
      teaser: 'Fabric, Forge, NeoForge und Quilt mit Modrinth und CurseForge eingebaut – Modpacks installieren, aktualisieren und teilen.',
      kicker: 'Mods & Modpacks',
      title: 'Minecraft Modpacks für Fabric, Forge, NeoForge und Quilt',
      lead: 'Der TRS Launcher ist ein Minecraft-Mod-Launcher mit eingebautem Modrinth und CurseForge. Wähle einen Modloader, installiere einzelne Mods oder ein komplettes Modpack mit einem Klick, teile dein eigenes Pack per Code und halte es aktuell – ohne die App zu verlassen.',
      sections: [
        {
          id: 'loaders',
          title: 'Jeder Modloader, fertig eingerichtet',
          text: [
            'Leg eine Instanz mit Vanilla, Fabric, Quilt, Forge oder NeoForge für jede Minecraft-Version von 1.7.10 bis zur neuesten an. Der Launcher installiert den Loader, seine Bibliotheken und das richtige Java für dich – keine Installer-Dateien starten, keine Ordner kopieren.',
            'Jede Instanz hat eigene Mods, Ressourcenpakete, Shader, Datenpakete und Welten. Ein großes Technik-Modpack und ein schlankes Fabric-Setup leben so nebeneinander, ohne sich in die Quere zu kommen.',
          ],
        },
        {
          id: 'discover',
          title: 'Modrinth und CurseForge an einem Ort',
          text: [
            'Die Entdecken-Seite durchsucht Mods, Modpacks, Ressourcenpakete, Shader und Datenpakete auf Modrinth und CurseForge – ein Schalter wechselt die Plattform, die Filter bleiben gleich. Jedes Projekt hat eine eigene Seite mit Beschreibung, Galerie, Versionen und Abhängigkeiten.',
            'Installierst du eine Mod, kommen ihre nötigen Abhängigkeiten mit. Updates werden für Inhalte beider Plattformen gefunden, und du kannst eine Mod auf eine andere Version umstellen, während der Verlauf der Instanz zeigt, was sich geändert hat. Erlaubt ein Autor Downloads nur auf CurseForge selbst, zeigt der Launcher die Dateien mit einem Knopf zu ihrer Seite und holt sie aus deinem Download-Ordner.',
          ],
          shot: { file: '0.2.0/content.png', alt: 'Mods, Ressourcenpakete und Shader einer Minecraft-Instanz in einer Inhaltsliste im TRS Launcher', caption: 'Alles, was eine Instanz enthält, in einer Liste' },
        },
        {
          id: 'install',
          title: 'Modpacks mit einem Klick installieren',
          text: [
            'Modpacks von Modrinth und CurseForge – aus „Entdecken“ oder als heruntergeladene .mrpack- oder .zip-Datei – werden zu einer neuen Instanz. Große Packs laden große Dateien zuerst und mehrere gleichzeitig, und die neue Instanz zeigt ihren Fortschritt, bis alles da ist.',
            'Beim Installieren entscheidest du einmal, ob der TRS Client dazukommt. Bringt das Pack schon Mods mit, die sich mit ihm überschneiden, etwa eine eigene Minimap oder ein eigenes HUD, sagt dir der Launcher welche und wählt „Ohne TRS Client“ vor. Deine Wahl wird gespeichert und lässt sich in den Instanz-Einstellungen ändern.',
          ],
          shot: { file: '0.10.0/modpack-choice.png', alt: 'Ein Minecraft-Modpack im TRS Launcher mit oder ohne TRS Client installieren', caption: 'Modpack mit oder ohne TRS Client installieren' },
        },
        {
          id: 'compat',
          title: 'Mods, die zusammenpassen',
          text: [
            'Manche Mods funktionieren nur mit bestimmten Versionen anderer Mods. Der Launcher liest diese Regeln aus den Mod-Dateien: Presets wählen Versionen, die zusammenpassen, Updates, die eine andere Mod kaputt machen würden, werden zurückgehalten, und vor jedem Start prüft er, ob einer Mod eine nötige Mod fehlt. Stürzt das Spiel trotzdem ab, nennt der Absturz-Helfer die beteiligten Mods und bietet eine Lösung an.',
          ],
          points: [
            'Nötige Abhängigkeiten werden automatisch installiert',
            'Instanzen mit einem bekannten unverträglichen Paar bekommen einen „Beheben“-Knopf',
            'Eigene Presets: eine Auswahl aus Mods, Ressourcenpaketen und Shadern für jede Instanz',
          ],
        },
        {
          id: 'share',
          title: 'Dein Modpack per Code teilen',
          text: [
            'Das perfekte Pack gebaut? Teile es aus der Instanz: Der Launcher lädt deine Mod-Liste und die Einstellungen hoch, die du auswählst, und gibt dir einen Code (TRS-XXXX-XXXX) und einen Link – oder schickt das Pack direkt an Freunde. Du bestimmst, ob der Code 1, 7 oder 30 Tage gilt oder gar nicht abläuft. Andere installieren es mit „Modpack per Code“, und die Link-Seite zeigt vor dem Installieren, was drin ist.',
            'Lade eine neue Version hoch und behalte den Code: Alle, die das Pack installiert haben, sehen „Update“, und Dateien, die sie selbst geändert haben, bleiben, wie sie sind. Mod-Dateien, die nicht von Modrinth stammen, brauchen beim Teilen eine Bestätigung und zeigen beim Installieren eine Warnung.',
          ],
          shot: { file: '0.12.0/share-result.png', alt: 'Ein Minecraft-Modpack im TRS Launcher per Code, Link oder direkt an Freunde teilen', caption: 'Modpack teilen: Code, Link oder direkt an Freunde' },
        },
        {
          id: 'export',
          title: 'Exportieren und sichern',
          text: [
            'Exportiere jede Instanz als .mrpack-Modpack, sichere eine Welt als ZIP oder wähle einzelne Dateien – alles im Teilen-Reiter der Instanz. Exportierte Packs lassen den TRS Client selbst und seine privaten Dateien weg.',
          ],
          shot: { file: '0.12.0/pack-update.png', alt: 'Ein geteiltes Minecraft-Modpack im TRS Launcher aktualisieren, eigene Änderungen bleiben', caption: 'Neue Version? Ein Klick, eigene Änderungen bleiben' },
        },
      ],
      faq: [
        { q: 'Welche Modloader unterstützt der TRS Launcher?', a: 'Fabric, Quilt, Forge und NeoForge – dazu Vanilla – für jede Minecraft-Version von 1.7.10 bis zur neuesten, sofern es den Loader für diese Version gibt.' },
        { q: 'Kann ich CurseForge-Modpacks installieren?', a: 'Ja. Installiere sie aus „Entdecken“ oder öffne eine heruntergeladene CurseForge-.zip; das Pack wird zu einer neuen Instanz. Modrinth-.mrpack-Dateien gehen genauso.' },
        { q: 'Wie teile ich ein Modpack mit Freunden?', a: 'Öffne die Instanz, wähle Teilen → Modpack teilen und such aus, was mitkommt. Du bekommst einen TRS-Code und einen Link oder schickst das Pack direkt an Freunde. Sie installieren es mit Bibliothek → „Modpack per Code“.' },
        { q: 'Bleiben meine eigenen Änderungen bei einem Modpack-Update erhalten?', a: 'Ja. Lädt die Person, die das Pack geteilt hat, eine neue Version hoch, siehst du „Update“, und Dateien, die du selbst geändert hast, bleiben, wie sie sind.' },
        { q: 'Kann ich Shader nutzen?', a: 'Ja. Installiere Shaderpakete von Modrinth oder CurseForge oder wähle beim FPS-Boost-Preset die Stufe „Shader leicht“ oder „Shader schön“, die Iris und einen Shader für Fabric, Quilt und NeoForge mitbringt. Mit K schaltest du Shader im Spiel an und aus.' },
      ],
      cta: { title: 'Starte dein nächstes Modpack', text: 'Lade den TRS Launcher kostenlos herunter und installiere dein erstes Modpack in einer Minute.' },
    },

    'fps-boost-pvp-client': {
      seo: {
        title: 'Minecraft Performance Launcher mit FPS-Boost & PvP Client | TRS',
        description:
          'Der TRS Launcher ist ein kostenloser Minecraft Performance Launcher: FPS-Boost, PvP-HUD mit Keystrokes und CPS, Zoom und Minimap – ab 1.7.10 und 1.8.9.',
      },
      name: 'FPS-Boost & PvP Client',
      teaser: 'Der TRS Client: FPS-Boost, PvP-HUD mit Keystrokes und CPS, Zoom, Minimap und Emotes – von 1.7.10 bis zur neuesten Version.',
      kicker: 'TRS Client',
      title: 'Der kostenlose Minecraft Performance Launcher mit FPS-Boost und PvP-Client',
      lead: 'Der TRS Client ist die Client-Mod, die beim TRS Launcher dabei ist. Er bringt FPS-Boost, ein aufgeräumtes PvP-HUD, Zoom, Freelook, eine Minimap, Emotes und Umhänge nach Minecraft – von 1.7.10 und 1.8.9 bis zur neuesten Version – und bleibt auf Servern fair.',
      sections: [
        {
          id: 'versions',
          title: 'Ein Client für alte und neue Versionen',
          text: [
            'Der Launcher legt den TRS Client automatisch in deine Instanzen: Forge ab 1.7.10, Fabric und Quilt ab 1.14.4 und NeoForge, jeweils bis zur neuesten Version. Ob du 1.8.9-PvP spielst oder die neueste Version – du bekommst dasselbe Menü (Rechts-Shift), dieselben Module und denselben HUD-Editor. Für jede Instanz lässt er sich abschalten.',
            'Modul-Pakete richten den Client mit einem Klick für deinen Spielstil ein – PvP, Redstone, Komfort oder Minimal –, und mit den TRS-Diensten folgen dir Module, HUD-Layouts und Tasten auf jeden PC.',
          ],
          shot: { file: '0.3.0/hud-editor.png', alt: 'HUD-Elemente in Minecraft mit dem HUD-Editor des TRS Client verschieben', caption: 'Jedes HUD-Element dahin, wo du es willst' },
        },
        {
          id: 'fps',
          title: 'Ein Performance Launcher mit FPS-Boost, den du messen kannst',
          text: [
            'Neue Instanzen starten ohne Bildraten-Deckel, mit VSync aus und abgestimmten Java-Einstellungen. Unter Fabric bringt der TRS Client kostenlose Performance-Mods wie Lithium, FerriteCore, ImmediatelyFast und ModernFix mit, wo es sie für deine Version gibt, und das FPS-Boost-Preset des Launchers ergänzt Sodium und Co. in drei Stufen: Max FPS, Shader leicht und Shader schön.',
            'Im Spiel stellt FPS-Boost mit einem Klick alles auf Niedrig, Mittel oder Hoch und zeigt die FPS vorher und nachher. Ein Leistungs-Check findet FPS-Bremsen in deinen Grafikeinstellungen und behebt sie mit einem Klick, Dynamic FPS senkt die Bildrate im Hintergrund, und Entity Culling überspringt, was du nicht sehen kannst. Jede Änderung lässt sich rückgängig machen.',
          ],
          points: [
            'Grafikmodus „Schön“ oder „Max FPS“',
            'Die starke Grafikkarte im Laptop, unter Windows und Linux',
            'Andere Performance-Mods werden erkannt und behalten ihren Teil der Arbeit',
          ],
        },
        {
          id: 'pvp',
          title: 'PvP-HUD: Keystrokes, CPS und Zähler',
          text: [
            'Für PvP zeigt der TRS Client, was im Kampf zählt: Keystrokes, einen CPS-Zähler, Reichweiten-, Combo- und Tempo-Anzeige, einen Item-Zähler für Pfeile, Totems, Tränke, goldene Äpfel und Enderperlen sowie Treffer-Feedback mit Hitmarker. Dazu ein eigenes Fadenkreuz, Trefferfarbe, 1.7-Animationen, niedriges Feuer und eine Schild-Position, die dir die Sicht frei hält.',
            'Der Chat bekommt Zeitstempel, gestapelte Wiederholungen und Erwähnungen, die Ping-Anzeige zeigt deinen echten Ping mit Jitter, und der Streamer-Modus versteckt Namen und Serveradressen.',
          ],
          shot: { file: '0.9.0/pvp-hud.png', alt: 'Das PvP-HUD des TRS Client mit Item-Zähler, Hitmarker und Warnungen', caption: 'Zähler-HUD, Hitmarker und Warnungen' },
        },
        {
          id: 'fair-play',
          title: 'Fair Play auf Servern',
          text: [
            'Alles im TRS Client ist Anzeige oder Komfort: keine Reichweiten- oder Hitbox-Änderung, kein Auto-Klicken, und Treffer-Feedback ändert deine Angriffe nie. Das Einzige, was der Client von sich aus sendet, ist Auto-GG – und das bleibt aus, bis du es einschaltest. Die Minimap hat einen Fair-Play-Schalter – keine Höhlensicht, Kreaturen und Spieler nur, wenn du sie sehen könntest –, und Server, die Karten-Mods um Fair Play bitten, werden automatisch beachtet.',
            'Manche Server verbieten einzelne Funktionen wie Freelook. Die kannst du abschalten, und Freelook schaltet sich auf eingetragenen Servern selbst ab.',
          ],
        },
        {
          id: 'maps',
          title: 'Zoom, Freelook, Minimap und Weltkarte',
          text: [
            'Weicher Zoom (V), Freelook (linke Alt-Taste) sowie Dauer-Sprinten und -Schleichen sind eingebaut. Die Minimap gleitet flüssig, nimmt ihre Farben aus deinem Ressourcenpaket, zeigt Mob-Köpfe und setzt ferne Wegpunkte an ihren Rand; drinnen schaut sie in Gebäude, statt das Dach zu zeigen. Mit M öffnest du die Weltkarte im Vollbild mit stufenlosem Zoom, Wegpunkt-Liste, anderen Dimensionen und PNG-Export.',
          ],
          shot: { file: '0.13.0/minimap.png', alt: 'Die Minimap des TRS Client mit Wegpunkten am Rand und echten Mob-Köpfen', caption: 'Minimap mit Wegpunkten am Rand und echten Mob-Köpfen' },
        },
        {
          id: 'extras',
          title: 'Emotes, Umhänge, Screenshots und Notizen',
          text: [
            'Öffne das Emote-Rad und winke, tanze oder juble – andere TRS-Spieler sehen es. TRS-Umhänge schwingen mit Umhang-Physik wie Stoff; viele sind kostenlos, manche animiert oder in HD. Nach F2 kannst du deinen Screenshot in einer kleinen Vorschau bearbeiten, kopieren oder verschicken, und der Screenshot-Editor schneidet zu, zeichnet Pfeile und Text und verpixelt Namen. Jede Welt und jeder Server bekommt außerdem ein eigenes Notizbuch mit Checklisten und anklickbaren Koordinaten.',
          ],
          shot: { file: '0.5.0/emote-wheel.png', alt: 'Das Emote-Rad des TRS Client in Minecraft', caption: 'Das Emote-Rad' },
          link: { to: '/cosmetics', label: 'Alle TRS-Umhänge ansehen' },
        },
      ],
      faq: [
        { q: 'Ist der TRS Launcher ein Performance Launcher?', a: 'Ja. Der TRS Launcher richtet Java und Arbeitsspeicher für dich ein, startet Vanilla-Instanzen mit der TRS-Optimierung (Fabric, TRS Client und Performance-Mods im Hintergrund) und hebt die Bildraten-Grenzen neuer Instanzen auf. Das klappt für jede Version und jeden Modloader, nicht nur für eine Client-Version.' },
        { q: 'Ist der TRS Client kostenlos?', a: 'Ja. Der TRS Client ist kostenlos beim TRS Launcher dabei, und der ist Open Source unter GPL-3.0.' },
        { q: 'Welche Minecraft-Versionen unterstützt der TRS Client?', a: 'Forge ab 1.7.10 (inklusive 1.8.9), Fabric und Quilt ab 1.14.4 und NeoForge, jeweils bis zur neuesten Version. Einige Funktionen brauchen neuere Versionen, zum Beispiel die Schaltungs-Vorlagen ab 1.8.9.' },
        { q: 'Bringt der TRS Client wirklich mehr FPS?', a: 'Er entfernt typische FPS-Bremsen wie den Standard-Bildraten-Deckel und VSync, bringt unter Fabric Performance-Mods mit und lässt dich zwischen „Schön“ und „Max FPS“ wählen. Wie viel du gewinnst, hängt von PC und Version ab – FPS-Boost zeigt die FPS vorher und nachher, so kannst du es prüfen.' },
        { q: 'Ist der TRS Client auf PvP-Servern erlaubt?', a: 'Er zeigt nur Infos, die dein Spiel ohnehin hat, und ändert weder Reichweite noch Hitboxen oder Klicks. Funktionen, die manche Server nicht erlauben, etwa Freelook, lassen sich abschalten. Prüfe immer die Regeln deines Servers.' },
        { q: 'Hat er Keystrokes und einen CPS-Zähler?', a: 'Ja – Keystrokes, CPS, Reichweite, Combo, Tempo, Item-Zähler, Ping und mehr. Jedes Element verschiebst und vergrößerst du im HUD-Editor.' },
      ],
      cta: { title: 'Mehr FPS, aufgeräumtes HUD', text: 'Lade den TRS Launcher kostenlos herunter – der TRS Client kommt automatisch in deine Instanzen.' },
    },
  },
}
