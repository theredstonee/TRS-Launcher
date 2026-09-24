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
    updated: 'Last updated: 24 September 2026',
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

**Cookies.** The website sets no tracking or advertising cookies and uses no analytics. There are only two technically necessary cookies (§ 25(2) TDDDG):

| Cookie | Purpose | Duration |
|---|---|---|
| \`trs_lang\` | Remembers the language you picked | 1 year |
| \`trs_admin\` | Keeps team admins signed in to the admin area (httpOnly, only after sign-in) | 8 hours |

**Fonts and scripts** are served from this server – no external CDNs, no Google Fonts.

**GitHub.** Downloads link to GitHub Releases, and pictures in update posts are loaded from \`raw.githubusercontent.com\`. When you download a file or open a post with pictures, your browser connects to GitHub, Inc. (USA), which receives your IP address; see GitHub's privacy statement. Our server asks the GitHub API for the newest version and reads the changelog – without any data about you.

**Admin sign-in.** Team admins sign in by confirming a short code in the TRS Launcher. We store only a hash of the code, the admin's Minecraft UUID and a hash of the session token, each with an expiry time (code: 5 minutes, session: 8 hours); expired entries are deleted automatically.

**Links** to Discord, GitHub and other sites are plain links; nothing is loaded from them until you click.`,
    launcher: '## The TRS Launcher',
  },
  de: {
    title: 'Datenschutzerklärung',
    updated: 'Stand: 24. September 2026',
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

**Cookies.** Die Website setzt keine Tracking- oder Werbe-Cookies und nutzt keine Analyse-Dienste. Es gibt nur zwei technisch notwendige Cookies (§ 25 Abs. 2 TDDDG):

| Cookie | Zweck | Dauer |
|---|---|---|
| \`trs_lang\` | Merkt sich die gewählte Sprache | 1 Jahr |
| \`trs_admin\` | Hält Team-Admins im Admin-Bereich angemeldet (httpOnly, nur nach Anmeldung) | 8 Stunden |

**Schriften und Skripte** kommen von diesem Server – keine externen CDNs, keine Google Fonts.

**GitHub.** Downloads verweisen auf GitHub Releases, und Bilder in Update-Beiträgen werden von \`raw.githubusercontent.com\` geladen. Lädst du eine Datei herunter oder öffnest einen Beitrag mit Bildern, verbindet sich dein Browser mit GitHub, Inc. (USA), das dabei deine IP-Adresse erhält; siehe die Datenschutzerklärung von GitHub. Unser Server fragt die GitHub-API nach der neuesten Version und liest den Changelog – ohne Daten über dich.

**Admin-Anmeldung.** Team-Admins melden sich an, indem sie einen kurzen Code im TRS Launcher bestätigen. Gespeichert werden nur ein Hash des Codes, die Minecraft-UUID des Admins und ein Hash des Sitzungs-Tokens, jeweils mit Ablaufzeit (Code: 5 Minuten, Sitzung: 8 Stunden); abgelaufene Einträge werden automatisch gelöscht.

**Links** zu Discord, GitHub und anderen Seiten sind einfache Links; von dort wird erst etwas geladen, wenn du klickst.`,
    launcher: '## Der TRS Launcher',
  },
  es: {
    title: 'Política de privacidad',
    updated: 'Última actualización: 24 de septiembre de 2026',
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

**Cookies.** Sin cookies de rastreo ni publicidad y sin analíticas. Solo dos cookies técnicamente necesarias:

| Cookie | Finalidad | Duración |
|---|---|---|
| \`trs_lang\` | Recuerda el idioma elegido | 1 año |
| \`trs_admin\` | Mantiene la sesión de los administradores del equipo (httpOnly, solo tras iniciar sesión) | 8 horas |

**Fuentes y scripts** se sirven desde este servidor – sin CDN externos ni Google Fonts.

**GitHub.** Las descargas enlazan a GitHub Releases y las imágenes de las entradas se cargan desde \`raw.githubusercontent.com\`; tu navegador se conecta entonces a GitHub, Inc. (EE. UU.), que recibe tu dirección IP.

**Inicio de sesión de administración.** Solo se guardan un hash del código, la UUID de Minecraft del administrador y un hash del token de sesión, con caducidad (código: 5 minutos, sesión: 8 horas).`,
    launcher: '## El TRS Launcher',
  },
}

/** Aufsichtsbehörde für nicht-öffentliche Stellen in Bayern. */
export const AUTHORITY = {
  en: 'The competent supervisory authority is the Bavarian State Office for Data Protection Supervision (BayLDA), Promenade 18, 91522 Ansbach, Germany – https://www.lda.bayern.de',
  de: 'Zuständige Aufsichtsbehörde ist das Bayerische Landesamt für Datenschutzaufsicht (BayLDA), Promenade 18, 91522 Ansbach – https://www.lda.bayern.de',
  es: 'La autoridad de control competente es el Bayerisches Landesamt für Datenschutzaufsicht (BayLDA), Promenade 18, 91522 Ansbach, Alemania – https://www.lda.bayern.de',
}
