// Datenschutz: Teil für Verantwortlichen und Website. Der Launcher-Teil kommt aus PRIVACY*.md des Repos
// (app/content/launcher-privacy.*.md – beim Ändern dort bitte hierher kopieren).

export const CONTROLLER = {
  name: 'Ohev Tamerin',
  representative: 'Ron Tamerin',
  address: 'Seestraße 19, 83727 Schliersee, Deutschland',
  email: 'ron@theredstonee.de',
  phone: '+49 (0) 8026 387 25 98',
}

const c = CONTROLLER

export const WEBSITE_PRIVACY: Record<'en' | 'de' | 'es', { title: string, updated: string, intro: string, body: string, launcher: string }> = {
  en: {
    title: 'Privacy policy',
    updated: 'Last updated: 27 September 2026',
    intro:
      'This policy covers the website trs-launcher.theredstonee.de, the TRS Launcher, the TRS Client mod and the TRS services. In short: no tracking, no analytics, no advertising – only what is needed for what you use.',
    body: `## Controller

${c.name}, represented by ${c.representative}
${c.address}
E-mail: ${c.email} · Phone: ${c.phone}

All details are in the [imprint](https://theredstonee.de/imprint/).

## This website

**Hosting.** The website and the TRS services run in one app on a server in **Germany**. It is reachable only through a **Cloudflare Tunnel**; Cloudflare (Cloudflare, Inc. / Cloudflare Germany GmbH) terminates the HTTPS connection and processes your IP address and the requests on our behalf under its data processing addendum. Transfers to the USA are covered by the EU-US Data Privacy Framework and standard contractual clauses. Legal basis: Art. 6(1)(f) GDPR (a secure, working website).

**Server logs.** Our logs contain only technical data (method, path without query, status, duration, request id) – **no IP addresses**. Rate limits count requests per IP address **in memory only**; the counters are never written to disk and disappear after a few minutes.

**Cookies.** The website sets no tracking or advertising cookies and uses no analytics. There are only four technically necessary cookies (§ 25(2) TDDDG):

| Cookie | Purpose | Duration |
|---|---|---|
| \`trs_lang\` | Remembers the language you picked | 1 year |
| \`trs_oauth\` | Protects the sign-in with Microsoft against forgery (random value, only on /auth/microsoft, httpOnly) | max. 10 minutes |
| \`trs_llogin\` | Ties a sign-in with the TRS Launcher to this browser (random value, only on /v1/web/launcher-login, httpOnly) | max. 3 minutes |
| \`trs_session\` | Keeps you signed in after the sign-in with Microsoft or the TRS Launcher (httpOnly, Secure, SameSite=Strict) | 8 hours |

**Fonts and scripts** are served from this server – no external CDNs, no Google Fonts.

**GitHub.** Downloads link to GitHub Releases, and pictures in update posts are loaded from \`raw.githubusercontent.com\`. When you download a file or open a post with pictures, your browser connects to GitHub, Inc. (USA), which receives your IP address; see GitHub's privacy statement. Our server asks the GitHub API for the newest version and reads the changelog – without any data about you.

**Team area.** Team members sign in with Microsoft like everyone else (see “Website sign-in with Microsoft” below). What they see in the team area depends on the permissions of their role – for example, only roles with the right permission see the content of reports or player files. Team members review **chat reports** – they only see the content that was reported and its context (see “Reports and moderation” below); reported pictures are loaded from this server only. They also manage sanctions, appeals and internal notes (see “Sanctions and appeals” below); every action is written to an audit log (kept 2 years).

**Shared modpacks (\`/p/…\` pages).** A player can share a modpack with a code; its page shows the pack's name, description, a summary of its contents and **the name and head of the player who shared it**, and is never indexed by search engines. Downloading the pack is only possible in the TRS Launcher with a TRS account. Signed in, you can report a pack; the report is handled like other reports. Legal basis: Art. 6(1)(f) GDPR (showing a pack the player chose to share, a safe service). Details: “Sharing modpacks” below.

**Shared screenshots (\`/s/…\` pages).** When a player shares a screenshot as a link, anyone with the link can open this page for 30 days. It shows only the picture, the upload date and the expiry date – **no name, no Minecraft UUID** – and is never indexed by search engines. Chat apps such as Discord load the picture when someone posts the link, to show a preview. With **“Report”** you can tell our team about a picture without an account: we store only the reason you picked, never your IP address (it is counted for rate limiting in memory only). Legal basis: Art. 6(1)(f) GDPR (a safe service). Details: “Shared screenshots” below.

**News posts (\`/blog\`).** Besides the automatic update posts, the team writes news. A news post shows the **name and head of the team member** who wrote it (or “TRS Team”); its pictures are served from this server. Legal basis: Art. 6(1)(f) GDPR (informing about the project).

**Links** to Discord, GitHub and other sites are plain links; nothing is loaded from them until you click.

## Website sign-in with Microsoft

You can sign in to this website with the Microsoft account that owns Minecraft: Java Edition – for example to apply for the team or, as a team member, to open the team area. The sign-in uses Microsoft's standard OAuth procedure: you enter your password only at Microsoft; we never see it.

**What we receive.** Microsoft, Xbox Live and Minecraft confirm your Minecraft **UUID** and **name**. For this our server exchanges short-lived tokens with Microsoft (login.microsoftonline.com), Xbox Live (user.auth.xboxlive.com, xsts.auth.xboxlive.com) and Minecraft (api.minecraftservices.com). The tokens exist **only in memory for the few seconds of the sign-in and are discarded right after** – we store no Microsoft, Xbox or Minecraft tokens, no e-mail address and no password.

**What we store.** Your TRS account (UUID, name, first and last sign-in – created now if you have not used TRS before) and a website session: a hash of the session token, a CSRF token and the expiry (8 hours, at most 5 sessions per account). Signing out deletes the session at once. Sign-ins of team members are recorded in the audit log (2 years).

**Legal basis.** Art. 6(1)(b) GDPR (you want to use the sign-in); for security measures such as rate limits and the audit log Art. 6(1)(f). Microsoft processes the sign-in under its own privacy statement (Microsoft Corporation, USA; EU-US Data Privacy Framework).

## Website sign-in with the TRS Launcher

Instead of Microsoft you can confirm the sign-in in your **TRS Launcher**. The website shows a short code and opens the launcher (link \`trs-launcher://web-login/…\`); the launcher shows the code, the website, a rough browser description and your account and asks you to confirm. Nothing is confirmed without your click.

**What we store.** For the up to two minutes of the request: a hash of the link value and of a random browser value (cookie \`trs_llogin\`), the code, a rough browser description such as “Firefox · Windows” (never the full browser identification, never your IP address), the return page and – after you confirm – your Minecraft UUID. The request is deleted when you are signed in, when it is declined, or shortly after it expires. The session afterwards is the same as after the sign-in with Microsoft (see above). Sign-ins of team members are recorded in the audit log (2 years).

**Legal basis.** Art. 6(1)(b) GDPR (you want to use the sign-in); rate limits and the audit log Art. 6(1)(f).

## Team applications

If you apply for a position in the TRS team, we store your **application**: the position, your Minecraft name and UUID (from the sign-in), your **Discord name**, your **age group** (never your date of birth), your answers to the questions of the position, the language of the form, the status and our answer to you.

**Who sees it.** Only team members whose role may see applications (for example the recruiting team and admins). They can add internal notes and votes that you don't see. Team members who may open player files also see your TRS sanction history. We use your Discord name only to contact you about the application.

**Your view.** Under “My applications” (and later in the launcher) you see the status and our answer; you can withdraw an open application at any time.

**Storage period.** Rejected or withdrawn applications are **deleted 6 months after the decision**. Accepted applications are kept while you are in the team and **deleted 6 months after you leave**. Deleting your TRS account deletes all your applications at once.

**Legal basis.** Art. 6(1)(b) GDPR (steps you asked for before a voluntary team membership) and Art. 6(1)(f) (a fair decision and protection against abuse). If you are under 16, please talk to your parents before you apply.

## Team page

The team page (/team) shows team members that the team **added by hand** – nobody appears there automatically. For each of them it shows the Minecraft name, the Minecraft skin (loaded from Mojang's texture server and shown as a 3D figure), the TRS cape if the person shows it to others, the group (role) and – only if entered – a position title, a Discord name and up to three links. To show faces and figures without asking Mojang on every visit, we store the address of the last seen Minecraft skin with the TRS account. If you are on the team page and want to be removed, tell a team member or write to us (e-mail above); leaving the team does not remove you automatically, the team does. Deleting your TRS account removes you from the page at once.

**Legal basis.** Art. 6(1)(f) GDPR (showing who runs TRS and how to reach them) and your agreement as a team member.

## Circuit library

**Public library.** The circuit pages (/circuits) and the TRS Client load circuits from our server without an account. The server only counts requests per IP address in memory (rate limit). The TRS Client checks for new circuits once per game start – only if you allowed the TRS online features.

**Submitting a circuit.** When you submit a circuit (signed in, on this website or in the TRS Client), we store the circuit (only blocks and their states – no chest contents or other block data), the name, description, category and language you entered, the file type, your Minecraft UUID and name, the time, the status and our answer. The uploaded file itself is not stored: it is converted and discarded right away. Only team members whose role may manage the library see submissions.

**Your name is shown.** If we accept your circuit, it is published in the library on this website and in the TRS Client **with your Minecraft name (and your UUID for the head picture) as the creator**. You confirm this with the checkbox when you submit.

**Storage period.** Decided submissions (accepted or rejected) are **deleted 90 days after the decision**; open ones stay until they are decided. A published circuit stays in the library until the team removes it. Deleting your TRS account deletes all your submissions and removes your name from the circuits you made (they stay in the library without a creator). You can ask us at any time to remove a circuit of yours (e-mail above).

**Reports.** Signed-in users can report a circuit; the report is handled like other reports (see “Reports and moderation”).

**Legal basis.** Art. 6(1)(b) GDPR (you want to publish your circuit) and Art. 6(1)(f) (reviewing content and preventing abuse, e.g. at most 5 submissions a day and upload bans).`,
    launcher: '## The TRS Launcher',
  },
  de: {
    title: 'Datenschutzerklärung',
    updated: 'Stand: 27. September 2026',
    intro:
      'Diese Erklärung gilt für die Website trs-launcher.theredstonee.de, den TRS Launcher, die TRS-Client-Mod und die TRS-Dienste. Kurz gesagt: kein Tracking, keine Analyse, keine Werbung – nur, was für das nötig ist, was du nutzt.',
    body: `## Verantwortlicher

${c.name}, vertreten durch ${c.representative}
${c.address}
E-Mail: ${c.email} · Telefon: ${c.phone}

Alle Angaben stehen im [Impressum](https://theredstonee.de/imprint/).

## Diese Website

**Hosting.** Website und TRS-Dienste laufen in einer Anwendung auf einem Server in **Deutschland**. Erreichbar ist er nur über einen **Cloudflare Tunnel**; Cloudflare (Cloudflare, Inc. / Cloudflare Germany GmbH) beendet die HTTPS-Verbindung und verarbeitet dabei in unserem Auftrag deine IP-Adresse und die Anfragen gemäß dem Data Processing Addendum von Cloudflare. Übermittlungen in die USA sind durch das EU-US Data Privacy Framework und Standardvertragsklauseln abgedeckt. Rechtsgrundlage: Art. 6 Abs. 1 lit. f DSGVO (eine sichere, funktionierende Website).

**Server-Logs.** Unsere Logs enthalten nur technische Daten (Methode, Pfad ohne Query, Status, Dauer, Request-ID) – **keine IP-Adressen**. Ratenbegrenzungen zählen Anfragen pro IP-Adresse **nur im Arbeitsspeicher**; die Zähler werden nie auf die Festplatte geschrieben und verfallen nach wenigen Minuten.

**Cookies.** Die Website setzt keine Tracking- oder Werbe-Cookies und nutzt keine Analyse-Dienste. Es gibt nur vier technisch notwendige Cookies (§ 25 Abs. 2 TDDDG):

| Cookie | Zweck | Dauer |
|---|---|---|
| \`trs_lang\` | Merkt sich die gewählte Sprache | 1 Jahr |
| \`trs_oauth\` | Schützt die Anmeldung mit Microsoft vor Fälschung (Zufallswert, nur auf /auth/microsoft, httpOnly) | höchstens 10 Minuten |
| \`trs_llogin\` | Bindet eine Anmeldung mit dem TRS Launcher an diesen Browser (Zufallswert, nur auf /v1/web/launcher-login, httpOnly) | höchstens 3 Minuten |
| \`trs_session\` | Hält dich nach der Anmeldung mit Microsoft oder dem TRS Launcher angemeldet (httpOnly, Secure, SameSite=Strict) | 8 Stunden |

**Schriften und Skripte** kommen von diesem Server – keine externen CDNs, keine Google Fonts.

**GitHub.** Downloads verweisen auf GitHub Releases, und Bilder in Update-Beiträgen werden von \`raw.githubusercontent.com\` geladen. Lädst du eine Datei herunter oder öffnest einen Beitrag mit Bildern, verbindet sich dein Browser mit GitHub, Inc. (USA), das dabei deine IP-Adresse erhält; siehe die Datenschutzerklärung von GitHub. Unser Server fragt die GitHub-API nach der neuesten Version und liest den Changelog – ohne Daten über dich.

**Team-Bereich.** Team-Mitglieder melden sich wie alle anderen mit Microsoft an (siehe „Anmeldung mit Microsoft“ unten). Was sie im Team-Bereich sehen, hängt von den Rechten ihrer Rolle ab – zum Beispiel sehen nur Rollen mit dem passenden Recht die Inhalte von Meldungen oder Spieler-Akten. Team-Mitglieder prüfen **Chat-Meldungen** – sie sehen nur den gemeldeten Inhalt samt Kontext (siehe „Meldungen und Moderation“ unten); gemeldete Bilder werden nur von diesem Server geladen. Außerdem verwalten sie Strafen, Einsprüche und interne Notizen (siehe „Strafen und Einsprüche“ unten); jede Aktion steht in einem Audit-Log (2 Jahre).

**Geteilte Modpacks (Seiten \`/p/…\`).** Ein Spieler kann ein Modpack per Code teilen; dessen Seite zeigt Name, Beschreibung, eine Inhaltsübersicht und **Namen und Kopf des Spielers, der es geteilt hat**, und wird von Suchmaschinen nie indexiert. Herunterladen geht nur im TRS Launcher mit TRS-Account. Angemeldet kannst du ein Pack melden; die Meldung wird wie andere Meldungen behandelt. Rechtsgrundlage: Art. 6 Abs. 1 lit. f DSGVO (ein Pack zeigen, das der Spieler teilen wollte; ein sicherer Dienst). Einzelheiten: „Modpacks teilen“ unten.

**Geteilte Screenshots (Seiten \`/s/…\`).** Teilt ein Spieler einen Screenshot als Link, kann jeder mit dem Link diese Seite 30 Tage lang öffnen. Sie zeigt nur das Bild, das Datum des Hochladens und das Ablaufdatum – **keinen Namen, keine Minecraft-UUID** – und wird von Suchmaschinen nie indexiert. Chat-Apps wie Discord laden das Bild, wenn jemand den Link postet, um eine Vorschau zu zeigen. Mit **„Melden“** kannst du unserem Team ohne Konto ein Bild melden: Wir speichern nur den gewählten Grund, nie deine IP-Adresse (sie wird nur im Arbeitsspeicher für die Ratenbegrenzung gezählt). Rechtsgrundlage: Art. 6 Abs. 1 lit. f DSGVO (ein sicherer Dienst). Einzelheiten: „Geteilte Screenshots“ unten.

**News-Beiträge (\`/blog\`).** Neben den automatischen Update-Beiträgen schreibt das Team News. Ein News-Beitrag zeigt **Namen und Kopf des Team-Mitglieds**, das ihn geschrieben hat (oder „TRS-Team“); seine Bilder kommen von diesem Server. Rechtsgrundlage: Art. 6 Abs. 1 lit. f DSGVO (Information über das Projekt).

**Links** zu Discord, GitHub und anderen Seiten sind einfache Links; von dort wird erst etwas geladen, wenn du klickst.

## Anmeldung mit Microsoft

Du kannst dich auf dieser Website mit dem Microsoft-Konto anmelden, dem Minecraft: Java Edition gehört – zum Beispiel, um dich für das Team zu bewerben oder als Team-Mitglied den Team-Bereich zu öffnen. Die Anmeldung nutzt das übliche OAuth-Verfahren von Microsoft: Dein Passwort gibst du nur bei Microsoft ein; wir sehen es nie.

**Was wir erhalten.** Microsoft, Xbox Live und Minecraft bestätigen deine Minecraft-**UUID** und deinen **Namen**. Dafür tauscht unser Server kurzlebige Tokens mit Microsoft (login.microsoftonline.com), Xbox Live (user.auth.xboxlive.com, xsts.auth.xboxlive.com) und Minecraft (api.minecraftservices.com). Die Tokens liegen **nur für die wenigen Sekunden der Anmeldung im Arbeitsspeicher und werden danach verworfen** – wir speichern keine Microsoft-, Xbox- oder Minecraft-Tokens, keine E-Mail-Adresse und kein Passwort.

**Was wir speichern.** Dein TRS-Konto (UUID, Name, erste und letzte Anmeldung – wird jetzt angelegt, falls du TRS noch nicht genutzt hast) und eine Website-Sitzung: einen Hash des Sitzungs-Tokens, ein CSRF-Token und die Ablaufzeit (8 Stunden, höchstens 5 Sitzungen je Konto). Beim Abmelden wird die Sitzung sofort gelöscht. Anmeldungen von Team-Mitgliedern stehen im Audit-Log (2 Jahre).

**Rechtsgrundlage.** Art. 6 Abs. 1 lit. b DSGVO (du möchtest die Anmeldung nutzen); für Sicherheitsmaßnahmen wie Ratenbegrenzung und Audit-Log Art. 6 Abs. 1 lit. f. Microsoft verarbeitet die Anmeldung nach seiner eigenen Datenschutzerklärung (Microsoft Corporation, USA; EU-US Data Privacy Framework).

## Anmeldung mit dem TRS Launcher

Statt mit Microsoft kannst du die Anmeldung in deinem **TRS Launcher** bestätigen. Die Website zeigt einen kurzen Code und öffnet den Launcher (Link \`trs-launcher://web-login/…\`); der Launcher zeigt Code, Website, eine grobe Browser-Angabe und dein Konto und fragt nach. Ohne deinen Klick wird nichts bestätigt.

**Was wir speichern.** Für die höchstens zwei Minuten der Anfrage: einen Hash des Link-Werts und eines zufälligen Browser-Werts (Cookie \`trs_llogin\`), den Code, eine grobe Browser-Angabe wie „Firefox · Windows“ (nie die vollständige Browser-Kennung, nie deine IP-Adresse), die Rücksprung-Seite und – nach deiner Bestätigung – deine Minecraft-UUID. Die Anfrage wird gelöscht, sobald du angemeldet bist, sie abgelehnt wurde oder kurz nach ihrem Ablauf. Die Sitzung danach ist dieselbe wie nach der Anmeldung mit Microsoft (siehe oben). Anmeldungen von Team-Mitgliedern stehen im Audit-Log (2 Jahre).

**Rechtsgrundlage.** Art. 6 Abs. 1 lit. b DSGVO (du möchtest die Anmeldung nutzen); Ratenbegrenzung und Audit-Log Art. 6 Abs. 1 lit. f.

## Bewerbungen für das Team

Wenn du dich auf eine Stelle im TRS-Team bewirbst, speichern wir deine **Bewerbung**: die Stelle, deinen Minecraft-Namen und deine UUID (aus der Anmeldung), deinen **Discord-Namen**, deine **Altersgruppe** (nie dein Geburtsdatum), deine Antworten auf die Fragen der Stelle, die Sprache des Formulars, den Status und unsere Antwort an dich.

**Wer sie sieht.** Nur Team-Mitglieder, deren Rolle Bewerbungen sehen darf (zum Beispiel das Bewerbungs-Team und Admins). Sie können interne Notizen und Stimmen ergänzen, die du nicht siehst. Team-Mitglieder, die Spieler-Akten öffnen dürfen, sehen auch deinen TRS-Strafverlauf. Deinen Discord-Namen nutzen wir nur, um dich wegen der Bewerbung zu kontaktieren.

**Deine Ansicht.** Unter „Meine Bewerbungen“ (und später im Launcher) siehst du Status und unsere Antwort; eine offene Bewerbung kannst du jederzeit zurückziehen.

**Speicherdauer.** Abgelehnte oder zurückgezogene Bewerbungen werden **6 Monate nach der Entscheidung gelöscht**. Angenommene Bewerbungen bleiben, solange du im Team bist, und werden **6 Monate nach deinem Austritt gelöscht**. Löschst du dein TRS-Konto, werden alle deine Bewerbungen sofort gelöscht.

**Rechtsgrundlage.** Art. 6 Abs. 1 lit. b DSGVO (von dir gewünschte Schritte vor einer freiwilligen Team-Mitgliedschaft) und Art. 6 Abs. 1 lit. f (faire Entscheidung und Schutz vor Missbrauch). Bist du unter 16, sprich bitte vor der Bewerbung mit deinen Eltern.

## Team-Seite

Die Team-Seite (/team) zeigt Team-Mitglieder, die das Team **von Hand eingetragen** hat – niemand erscheint dort automatisch. Zu jeder Person zeigt sie den Minecraft-Namen, den Minecraft-Skin (vom Textur-Server von Mojang geladen und als 3D-Figur gezeigt), den TRS-Umhang, wenn die Person ihn anderen zeigt, die Gruppe (Rolle) und – nur wenn eingetragen – einen Positions-Titel, einen Discord-Namen und bis zu drei Links. Damit Gesichter und Figuren nicht bei jedem Aufruf bei Mojang abgefragt werden müssen, speichern wir zum TRS-Konto die Adresse des zuletzt gesehenen Minecraft-Skins. Stehst du auf der Team-Seite und möchtest entfernt werden, sag es einem Team-Mitglied oder schreib uns (E-Mail oben); wer das Team verlässt, wird vom Team entfernt, nicht automatisch. Löschst du dein TRS-Konto, verschwindest du sofort von der Seite.

**Rechtsgrundlage.** Art. 6 Abs. 1 lit. f DSGVO (zeigen, wer TRS betreibt und wie man es erreicht) und deine Zustimmung als Team-Mitglied.

## Schaltungs-Bibliothek

**Öffentliche Bibliothek.** Die Schaltungs-Seiten (/circuits) und der TRS Client laden Schaltungen ohne Konto von unserem Server. Der Server zählt Anfragen je IP-Adresse nur im Arbeitsspeicher (Rate-Limit). Der TRS Client prüft einmal je Spielstart auf neue Schaltungen – nur, wenn du die TRS-Online-Funktionen erlaubt hast.

**Schaltung einreichen.** Reichst du eine Schaltung ein (angemeldet, auf dieser Website oder im TRS Client), speichern wir die Schaltung (nur Blöcke und ihre Zustände – keine Kisteninhalte oder anderen Blockdaten), den eingegebenen Namen, die Beschreibung, Kategorie und Sprache, den Dateityp, deine Minecraft-UUID und deinen Namen, den Zeitpunkt, den Status und unsere Antwort. Die hochgeladene Datei selbst speichern wir nicht: sie wird umgewandelt und sofort verworfen. Einreichungen sehen nur Team-Mitglieder, deren Rolle die Bibliothek verwalten darf.

**Dein Name wird angezeigt.** Nehmen wir deine Schaltung an, erscheint sie in der Bibliothek auf dieser Website und im TRS Client **mit deinem Minecraft-Namen (und deiner UUID für das Kopfbild) als Ersteller**. Das bestätigst du beim Einreichen mit dem Häkchen.

**Speicherdauer.** Entschiedene Einreichungen (angenommen oder abgelehnt) werden **90 Tage nach der Entscheidung gelöscht**; offene bleiben bis zur Entscheidung. Eine veröffentlichte Schaltung bleibt in der Bibliothek, bis das Team sie entfernt. Löschst du dein TRS-Konto, werden alle deine Einreichungen gelöscht und dein Name wird aus deinen Schaltungen entfernt (sie bleiben ohne Ersteller in der Bibliothek). Du kannst jederzeit verlangen, dass wir eine Schaltung von dir entfernen (E-Mail oben).

**Meldungen.** Angemeldete Nutzer können eine Schaltung melden; die Meldung wird wie andere Meldungen bearbeitet (siehe „Meldungen und Moderation“).

**Rechtsgrundlage.** Art. 6 Abs. 1 lit. b DSGVO (du möchtest deine Schaltung veröffentlichen) und Art. 6 Abs. 1 lit. f (Prüfung der Inhalte und Schutz vor Missbrauch, z. B. höchstens 5 Einreichungen am Tag und Upload-Sperren).`,
    launcher: '## Der TRS Launcher',
  },
  es: {
    title: 'Política de privacidad',
    updated: 'Última actualización: 27 de septiembre de 2026',
    intro:
      'Esta política cubre el sitio web trs-launcher.theredstonee.de, el TRS Launcher, el mod TRS Client y los servicios TRS. En resumen: sin rastreo, sin analíticas, sin publicidad – solo lo necesario para lo que usas. La versión alemana es la vinculante.',
    body: `## Responsable

${c.name}, representado por ${c.representative}
${c.address}
Correo: ${c.email} · Teléfono: ${c.phone}

Todos los datos están en el [aviso legal](https://theredstonee.de/imprint/).

## Este sitio web

**Alojamiento.** El sitio web y los servicios TRS funcionan en una sola aplicación en un servidor en **Alemania**, accesible solo a través de un **Cloudflare Tunnel**. Cloudflare (Cloudflare, Inc. / Cloudflare Germany GmbH) termina la conexión HTTPS y trata tu dirección IP y las solicitudes por encargo nuestro. Las transferencias a EE. UU. están cubiertas por el EU-US Data Privacy Framework y cláusulas contractuales tipo. Base jurídica: art. 6.1.f RGPD.

**Registros del servidor.** Solo datos técnicos (método, ruta sin query, estado, duración, id de solicitud) – **sin direcciones IP**. Los límites de frecuencia cuentan solicitudes por IP **solo en memoria**.

**Cookies.** Sin cookies de rastreo ni publicidad y sin analíticas. Solo cuatro cookies técnicamente necesarias:

| Cookie | Finalidad | Duración |
|---|---|---|
| \`trs_lang\` | Recuerda el idioma elegido | 1 año |
| \`trs_oauth\` | Protege el inicio de sesión con Microsoft contra falsificaciones (valor aleatorio, solo en /auth/microsoft, httpOnly) | máx. 10 minutos |
| \`trs_llogin\` | Vincula un inicio de sesión con el TRS Launcher a este navegador (valor aleatorio, solo en /v1/web/launcher-login, httpOnly) | máx. 3 minutos |
| \`trs_session\` | Mantiene tu sesión tras iniciar sesión con Microsoft o el TRS Launcher (httpOnly, Secure, SameSite=Strict) | 8 horas |

**Fuentes y scripts** se sirven desde este servidor – sin CDN externos ni Google Fonts.

**GitHub.** Las descargas enlazan a GitHub Releases y las imágenes de las entradas se cargan desde \`raw.githubusercontent.com\`; tu navegador se conecta entonces a GitHub, Inc. (EE. UU.), que recibe tu dirección IP.

**Área del equipo.** Los miembros del equipo inician sesión con Microsoft como todos (ver «Inicio de sesión con Microsoft» más abajo). Lo que ven en el área del equipo depende de los permisos de su rol; por ejemplo, solo los roles con el permiso adecuado ven el contenido de las denuncias o las fichas de jugador. Los miembros del equipo revisan las **denuncias del chat**: solo ven el contenido denunciado y su contexto (ver «Denuncias y moderación» más abajo); las imágenes denunciadas se cargan solo desde este servidor. También gestionan sanciones, apelaciones y notas internas (ver «Sanciones y apelaciones» más abajo); cada acción queda en un registro de auditoría (2 años).

**Modpacks compartidos (páginas \`/p/…\`).** Un jugador puede compartir un modpack con un código; su página muestra el nombre, la descripción, un resumen del contenido y **el nombre y la cabeza del jugador que lo compartió**, y los buscadores nunca la indexan. Descargar el pack solo es posible en TRS Launcher con una cuenta TRS. Con sesión iniciada puedes denunciar un pack; la denuncia se trata como las demás. Base jurídica: art. 6.1.f RGPD (mostrar un pack que el jugador quiso compartir, un servicio seguro). Detalles: «Compartir modpacks» más abajo.

**Capturas compartidas (páginas \`/s/…\`).** Cuando un jugador comparte una captura como enlace, cualquiera con el enlace puede abrir esta página durante 30 días. Solo muestra la imagen, la fecha de subida y la fecha de caducidad – **sin nombre ni UUID de Minecraft** – y los buscadores nunca la indexan. Apps de chat como Discord cargan la imagen cuando alguien publica el enlace, para mostrar una vista previa. Con **«Denunciar»** puedes avisar a nuestro equipo sin cuenta: solo guardamos el motivo elegido, nunca tu dirección IP (solo se cuenta en memoria para limitar solicitudes). Base jurídica: art. 6.1.f RGPD (un servicio seguro). Detalles: «Capturas compartidas» más abajo.

## Inicio de sesión con Microsoft

Puedes iniciar sesión en este sitio con la cuenta de Microsoft que tiene Minecraft: Java Edition, por ejemplo para postularte al equipo o, como miembro del equipo, para abrir el área del equipo. Se usa el procedimiento OAuth habitual de Microsoft: introduces tu contraseña solo en Microsoft; nosotros nunca la vemos.

**Qué recibimos.** Microsoft, Xbox Live y Minecraft confirman tu **UUID** y tu **nombre** de Minecraft. Para ello nuestro servidor intercambia tokens de corta duración con Microsoft (login.microsoftonline.com), Xbox Live (user.auth.xboxlive.com, xsts.auth.xboxlive.com) y Minecraft (api.minecraftservices.com). Los tokens existen **solo en memoria durante los segundos del inicio de sesión y se descartan justo después**: no guardamos tokens de Microsoft, Xbox ni Minecraft, ni correo electrónico ni contraseña.

**Qué guardamos.** Tu cuenta TRS (UUID, nombre, primer y último inicio de sesión; se crea ahora si aún no usabas TRS) y una sesión web: un hash del token de sesión, un token CSRF y la caducidad (8 horas, como máximo 5 sesiones por cuenta). Al cerrar sesión se borra al instante. Los inicios de sesión de miembros del equipo quedan en el registro de auditoría (2 años).

**Base jurídica.** Art. 6.1.b RGPD (quieres usar el inicio de sesión); para medidas de seguridad como límites de frecuencia y el registro de auditoría, art. 6.1.f. Microsoft trata el inicio de sesión según su propia política de privacidad (Microsoft Corporation, EE. UU.; EU-US Data Privacy Framework).

## Inicio de sesión con el TRS Launcher

En lugar de Microsoft puedes confirmar el inicio de sesión en tu **TRS Launcher**. La web muestra un código corto y abre el launcher (enlace \`trs-launcher://web-login/…\`); el launcher muestra el código, la web, una descripción aproximada del navegador y tu cuenta, y te pide confirmar. Sin tu clic no se confirma nada.

**Qué guardamos.** Durante los dos minutos como máximo de la solicitud: un hash del valor del enlace y de un valor aleatorio del navegador (cookie \`trs_llogin\`), el código, una descripción aproximada del navegador como «Firefox · Windows» (nunca la identificación completa del navegador ni tu dirección IP), la página de retorno y, tras tu confirmación, tu UUID de Minecraft. La solicitud se borra al iniciar sesión, al rechazarla o poco después de caducar. La sesión posterior es la misma que tras iniciar sesión con Microsoft (ver arriba). Los inicios de sesión de miembros del equipo quedan en el registro de auditoría (2 años).

**Entradas de noticias (\`/blog\`).** Además de las entradas automáticas de actualización, el equipo escribe noticias. Una noticia muestra **el nombre y la cabeza del miembro del equipo** que la escribió (o «Equipo TRS»); sus imágenes se sirven desde este servidor.

**Base jurídica.** Art. 6.1.b RGPD (quieres usar el inicio de sesión); límites de frecuencia, registro de auditoría y noticias, art. 6.1.f.

## Solicitudes para el equipo

Si te postulas a un puesto en el equipo TRS, guardamos tu **solicitud**: el puesto, tu nombre y UUID de Minecraft (del inicio de sesión), tu **nombre de Discord**, tu **grupo de edad** (nunca tu fecha de nacimiento), tus respuestas a las preguntas del puesto, el idioma del formulario, el estado y nuestra respuesta.

**Quién la ve.** Solo los miembros del equipo cuyo rol puede ver solicitudes (por ejemplo, el equipo de selección y los administradores). Pueden añadir notas internas y votos que tú no ves. Quienes pueden abrir fichas de jugador ven también tu historial de sanciones de TRS. Usamos tu nombre de Discord solo para contactarte sobre la solicitud.

**Tu vista.** En «Mis solicitudes» (y más adelante en el launcher) ves el estado y nuestra respuesta; puedes retirar una solicitud abierta en cualquier momento.

**Plazo de conservación.** Las solicitudes rechazadas o retiradas se **borran 6 meses después de la decisión**. Las aceptadas se conservan mientras estés en el equipo y se **borran 6 meses después de que salgas**. Si borras tu cuenta TRS, se borran al instante todas tus solicitudes.

**Base jurídica.** Art. 6.1.b RGPD (pasos que pides antes de una pertenencia voluntaria al equipo) y art. 6.1.f (una decisión justa y protección contra abusos). Si tienes menos de 16 años, habla con tus padres antes de postularte.

## Página del equipo

La página del equipo (/team) muestra a los miembros que el equipo **añadió a mano**; nadie aparece allí automáticamente. De cada persona muestra el nombre de Minecraft, el skin de Minecraft (cargado desde el servidor de texturas de Mojang y mostrado como figura 3D), la capa TRS si la persona la muestra a otros, el grupo (rol) y, solo si se ha indicado, un título del puesto, un nombre de Discord y hasta tres enlaces. Para no preguntar a Mojang en cada visita, guardamos con la cuenta TRS la dirección del último skin de Minecraft visto. Si estás en la página del equipo y quieres que te quitemos, díselo a un miembro del equipo o escríbenos (correo arriba); al dejar el equipo no se te quita automáticamente, lo hace el equipo. Si borras tu cuenta TRS, desapareces de la página al instante.

**Base jurídica.** Art. 6.1.f RGPD (mostrar quién gestiona TRS y cómo contactar) y tu acuerdo como miembro del equipo.

## Biblioteca de circuitos

**Biblioteca pública.** Las páginas de circuitos (/circuits) y el TRS Client cargan los circuitos de nuestro servidor sin cuenta. El servidor solo cuenta las peticiones por dirección IP en memoria (límite de peticiones). El TRS Client busca circuitos nuevos una vez por inicio del juego, solo si has permitido las funciones en línea de TRS.

**Enviar un circuito.** Cuando envías un circuito (con sesión iniciada, en esta web o en el TRS Client), guardamos el circuito (solo bloques y sus estados, sin contenido de cofres ni otros datos de bloques), el nombre, la descripción, la categoría y el idioma que indicaste, el tipo de archivo, tu UUID y tu nombre de Minecraft, la fecha, el estado y nuestra respuesta. El archivo subido no se guarda: se convierte y se descarta al instante. Solo ven los envíos los miembros del equipo cuyo rol puede gestionar la biblioteca.

**Se muestra tu nombre.** Si aceptamos tu circuito, se publica en la biblioteca de esta web y en el TRS Client **con tu nombre de Minecraft (y tu UUID para la imagen de la cabeza) como creador**. Lo confirmas con la casilla al enviarlo.

**Plazo de conservación.** Los envíos decididos (aceptados o rechazados) se **borran 90 días después de la decisión**; los abiertos se conservan hasta que se decidan. Un circuito publicado sigue en la biblioteca hasta que el equipo lo quite. Si borras tu cuenta TRS, se borran todos tus envíos y tu nombre se quita de tus circuitos (siguen en la biblioteca sin creador). Puedes pedirnos en cualquier momento que quitemos un circuito tuyo (correo arriba).

**Denuncias.** Los usuarios con sesión iniciada pueden denunciar un circuito; la denuncia se trata como las demás (ver «Denuncias y moderación»).

**Base jurídica.** Art. 6.1.b RGPD (quieres publicar tu circuito) y art. 6.1.f (revisar el contenido y evitar abusos, p. ej. un máximo de 5 envíos al día y bloqueos de subida).`,
    launcher: '## El TRS Launcher',
  },
}

/** Aufsichtsbehörde für nicht-öffentliche Stellen in Bayern. */
export const AUTHORITY = {
  en: 'The competent supervisory authority is the Bavarian State Office for Data Protection Supervision (BayLDA), Promenade 18, 91522 Ansbach, Germany – https://www.lda.bayern.de',
  de: 'Zuständige Aufsichtsbehörde ist das Bayerische Landesamt für Datenschutzaufsicht (BayLDA), Promenade 18, 91522 Ansbach – https://www.lda.bayern.de',
  es: 'La autoridad de control competente es el Bayerisches Landesamt für Datenschutzaufsicht (BayLDA), Promenade 18, 91522 Ansbach, Alemania – https://www.lda.bayern.de',
}
