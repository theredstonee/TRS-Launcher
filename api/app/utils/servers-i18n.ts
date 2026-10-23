// Texte der Seite „Für Server“ (/servers): TRS Server Rules – Plugin/Mod, mit dem Server TRS-Client-Module sperren oder
// erzwingen. Englisch, Deutsch, Spanisch. Der Team-Bypass (`serverrules.bypass`) wird hier bewusst offen erklärt.

import type { Lang } from './messages'

export type ServerPlatformId = 'bukkit' | 'velocity' | 'bungee' | 'sponge' | 'mods'

const en = {
  seo: {
    title: 'TRS Server Rules – Control TRS Client Features on Your Server',
    description:
      'Free plugin for Spigot, Paper, Folia, Velocity, BungeeCord, Sponge, Fabric, NeoForge and Forge: lock or force TRS Client features per world.',
  },
  kicker: 'For server owners',
  title: 'TRS Server Rules',
  lead:
    'A small, free plugin and server mod that tells the TRS Client what is allowed on your server: switch single features off, force them on, or limit a setting – globally, per world or per backend server.',
  download: 'Download on GitHub',
  modrinth: 'Modrinth',
  docs: 'Documentation',
  releaseNote: 'All platforms are in one release – pick the file for your server software. Open source under GPL-3.0.',
  features: {
    title: 'What it does',
    items: [
      { icon: 'lock', title: 'Lock features off', text: 'For example the minimap, fullbright or zoom in a PvP arena. The player sees “Locked by the server” in the TRS menu.' },
      { icon: 'check', title: 'Force features on', text: 'Make a feature always active, e.g. toggle sprint on a parkour server.' },
      { icon: 'filter', title: 'Limit settings', text: 'Set a fixed value or a range, e.g. zoom factor at most 4× or freecam only with fair play (no camera through walls).' },
      { icon: 'world', title: 'Per world and per server', text: 'Rules per world on Bukkit, Sponge and server mods, per backend server on Velocity and BungeeCord. Specific entries override the global ones.' },
      { icon: 'key', title: 'Bypass for your staff', text: 'Players with the permission trsrules.bypass get no rules at all – handy for admins and builders.' },
      { icon: 'warn', title: 'Players without TRS Client', text: 'Optional: if a player does not answer as a TRS Client within the timeout, do nothing, show a note or kick.' },
    ],
  },
  how: {
    title: 'How it works',
    steps: [
      'The TRS Client says hello to the server over a plugin channel after joining.',
      'The plugin answers with the rules for the player\'s world (or backend server) – also when there are none.',
      'On a world or server change and after /trsrules reload the rules are sent again and replace the old ones.',
      'When the player leaves, the client forgets all rules. Nothing is stored.',
    ],
    honest: 'TRS Server Rules only controls the TRS Client. It is not an anti-cheat: other clients ignore it, and a modified client can pretend to be a TRS Client.',
  },
  platforms: {
    title: 'Platforms',
    lead: 'One jar per platform. Rules are the same everywhere; only the place of the config differs.',
    file: 'File',
    config: 'Config',
    items: [
      { id: 'bukkit', name: 'Spigot, Paper, Purpur & Folia', versions: 'Minecraft 1.8.8 to the latest version, one jar for all', file: 'trs-server-rules-bukkit-<version>.jar', config: 'plugins/TRSServerRules/config.yml' },
      { id: 'velocity', name: 'Velocity', versions: 'Velocity 3.x – rules per backend server', file: 'trs-server-rules-velocity-<version>.jar', config: 'plugins/trs-server-rules/config.yml' },
      { id: 'bungee', name: 'BungeeCord & Waterfall', versions: 'Rules per backend server', file: 'trs-server-rules-bungee-<version>.jar', config: 'plugins/TRSServerRules/config.yml' },
      { id: 'sponge', name: 'Sponge', versions: 'Sponge API 8 and newer', file: 'trs-server-rules-sponge-<version>.jar', config: 'config/trsserverrules/config.yml' },
      { id: 'mods', name: 'Fabric, NeoForge & Forge (server mod)', versions: 'Fabric 1.20.1, 1.21.1, 1.21.4, 1.21.11 · NeoForge 1.21.1, 1.21.11 · Forge 1.20.1 – only needed on the server', file: 'trs-server-rules-<loader>-<mc>-<version>.jar', config: 'config/trs-server-rules.yml' },
    ] as { id: ServerPlatformId, name: string, versions: string, file: string, config: string }[],
    proxy: 'On a proxy, the proxy\'s rules take precedence over a plugin on the backend server.',
  },
  config: {
    title: 'Configuration',
    lead: 'Everything commented out means nothing is locked. Module IDs and setting names are listed in the documentation.',
    commandsTitle: 'Commands',
    commands: [
      { cmd: '/trsrules reload', text: 'Reload the config and send the new rules to everyone.' },
      { cmd: '/trsrules status <player>', text: 'Shows whether a player uses the TRS Client and which rules apply.' },
    ],
    permission: 'Permission trsrules.admin (server mods: OP level 3).',
  },
  team: {
    title: 'Openly documented: TRS team bypass',
    text:
      'Members of the TRS team with the team permission serverrules.bypass can use a locked feature anyway – but only after confirming an in-game prompt (“This server locks Minimap. Use it anyway as TRS team?”), and only until they leave the server.',
    points: [
      'It exists so the team can test features and help players on any server.',
      'The server is not told about it and cannot switch it off.',
      'It is written down here, in the documentation, in the README and as a comment at the top of every config file – it is not hidden.',
      'Your own bypass for your staff is the server permission trsrules.bypass – that one is fully under your control.',
    ],
  },
  cta: { title: 'Questions or a platform missing?', text: 'Ask on Discord or open an issue – the plugin is open source like the rest of TRS.' },
}

export type ServersTexts = typeof en

const de: ServersTexts = {
  seo: {
    title: 'TRS Server Rules – TRS-Client-Funktionen auf dem Server steuern',
    description:
      'Kostenloses Plugin für Spigot, Paper, Folia, Velocity, BungeeCord, Sponge, Fabric, NeoForge und Forge: TRS-Client-Funktionen je Welt sperren oder erzwingen.',
  },
  kicker: 'Für Server-Betreiber',
  title: 'TRS Server Rules',
  lead:
    'Ein kleines, kostenloses Plugin und Server-Mod, das dem TRS Client sagt, was auf deinem Server erlaubt ist: einzelne Funktionen sperren, erzwingen oder eine Einstellung begrenzen – global, je Welt oder je Unterserver.',
  download: 'Auf GitHub herunterladen',
  modrinth: 'Modrinth',
  docs: 'Dokumentation',
  releaseNote: 'Alle Plattformen stecken in einem Release – nimm die Datei für deine Server-Software. Open Source unter GPL-3.0.',
  features: {
    title: 'Was es kann',
    items: [
      { icon: 'lock', title: 'Funktionen sperren', text: 'Zum Beispiel Minimap, Fullbright oder Zoom in einer PvP-Arena. Im TRS-Menü steht dann „Vom Server gesperrt“.' },
      { icon: 'check', title: 'Funktionen erzwingen', text: 'Eine Funktion ist immer an, z. B. Dauer-Sprinten auf einem Parkour-Server.' },
      { icon: 'filter', title: 'Einstellungen begrenzen', text: 'Fester Wert oder Bereich, z. B. Zoom höchstens 4× oder Freecam nur mit Fair Play (keine Kamera durch Wände).' },
      { icon: 'world', title: 'Je Welt und je Server', text: 'Regeln je Welt bei Bukkit, Sponge und Server-Mods, je Unterserver bei Velocity und BungeeCord. Genauere Einträge gehen vor globale.' },
      { icon: 'key', title: 'Ausnahme für dein Team', text: 'Spieler mit der Permission trsrules.bypass bekommen gar keine Regeln – praktisch für Admins und Builder.' },
      { icon: 'warn', title: 'Spieler ohne TRS Client', text: 'Optional: Antwortet ein Spieler nicht rechtzeitig als TRS Client, passiert nichts, er bekommt einen Hinweis oder wird gekickt.' },
    ],
  },
  how: {
    title: 'So funktioniert es',
    steps: [
      'Der TRS Client meldet sich nach dem Betreten über einen Plugin-Kanal beim Server.',
      'Das Plugin antwortet mit den Regeln für die Welt (bzw. den Unterserver) des Spielers – auch wenn es keine gibt.',
      'Bei Welt- oder Serverwechsel und nach /trsrules reload kommen die Regeln neu und ersetzen die alten.',
      'Verlässt der Spieler den Server, vergisst der Client alle Regeln. Gespeichert wird nichts.',
    ],
    honest: 'TRS Server Rules steuert nur den TRS Client. Es ist kein Anti-Cheat: Andere Clients ignorieren es, und ein veränderter Client kann sich als TRS Client ausgeben.',
  },
  platforms: {
    title: 'Plattformen',
    lead: 'Eine Jar je Plattform. Die Regeln sind überall gleich, nur der Ort der Config unterscheidet sich.',
    file: 'Datei',
    config: 'Config',
    items: [
      { id: 'bukkit', name: 'Spigot, Paper, Purpur & Folia', versions: 'Minecraft 1.8.8 bis zur neuesten Version, eine Jar für alle', file: 'trs-server-rules-bukkit-<version>.jar', config: 'plugins/TRSServerRules/config.yml' },
      { id: 'velocity', name: 'Velocity', versions: 'Velocity 3.x – Regeln je Unterserver', file: 'trs-server-rules-velocity-<version>.jar', config: 'plugins/trs-server-rules/config.yml' },
      { id: 'bungee', name: 'BungeeCord & Waterfall', versions: 'Regeln je Unterserver', file: 'trs-server-rules-bungee-<version>.jar', config: 'plugins/TRSServerRules/config.yml' },
      { id: 'sponge', name: 'Sponge', versions: 'Sponge API 8 und neuer', file: 'trs-server-rules-sponge-<version>.jar', config: 'config/trsserverrules/config.yml' },
      { id: 'mods', name: 'Fabric, NeoForge & Forge (Server-Mod)', versions: 'Fabric 1.20.1, 1.21.1, 1.21.4, 1.21.11 · NeoForge 1.21.1, 1.21.11 · Forge 1.20.1 – nur auf dem Server nötig', file: 'trs-server-rules-<loader>-<mc>-<version>.jar', config: 'config/trs-server-rules.yml' },
    ],
    proxy: 'Hinter einem Proxy gehen die Regeln des Proxys vor einem Plugin auf dem Unterserver.',
  },
  config: {
    title: 'Konfiguration',
    lead: 'Alles auskommentiert heißt: nichts gesperrt. Modul-IDs und Einstellungsnamen stehen in der Dokumentation.',
    commandsTitle: 'Befehle',
    commands: [
      { cmd: '/trsrules reload', text: 'Config neu laden und die neuen Regeln an alle schicken.' },
      { cmd: '/trsrules status <spieler>', text: 'Zeigt, ob ein Spieler den TRS Client nutzt und welche Regeln für ihn gelten.' },
    ],
    permission: 'Permission trsrules.admin (Server-Mods: OP-Level 3).',
  },
  team: {
    title: 'Offen dokumentiert: Ausnahme für das TRS-Team',
    text:
      'Mitglieder des TRS-Teams mit dem Team-Recht serverrules.bypass können eine gesperrte Funktion trotzdem benutzen – aber nur nach einer Nachfrage im Spiel („Dieser Server sperrt Minimap. Als TRS-Team trotzdem benutzen?“) und nur, bis sie den Server verlassen.',
    points: [
      'Es gibt sie, damit das Team Funktionen auf jedem Server testen und Spielern helfen kann.',
      'Der Server erfährt davon nichts und kann es nicht abschalten.',
      'Es steht hier, in der Dokumentation, in der README und als Kommentar oben in jeder Config – nichts davon ist versteckt.',
      'Die Ausnahme für dein eigenes Team ist die Server-Permission trsrules.bypass – die hast du komplett selbst in der Hand.',
    ],
  },
  cta: { title: 'Fragen oder fehlt eine Plattform?', text: 'Frag auf Discord oder öffne ein Issue – das Plugin ist Open Source wie der Rest von TRS.' },
}

const es: ServersTexts = {
  seo: {
    title: 'TRS Server Rules – controla el TRS Client en tu servidor',
    description:
      'Plugin gratuito para Spigot, Paper, Folia, Velocity, BungeeCord, Sponge, Fabric, NeoForge y Forge: bloquea o fuerza funciones del TRS Client por mundo.',
  },
  kicker: 'Para dueños de servidores',
  title: 'TRS Server Rules',
  lead:
    'Un plugin y mod de servidor pequeño y gratuito que le dice al TRS Client qué está permitido en tu servidor: desactiva funciones, fuérzalas o limita un ajuste, de forma global, por mundo o por servidor del proxy.',
  download: 'Descargar en GitHub',
  modrinth: 'Modrinth',
  docs: 'Documentación',
  releaseNote: 'Todas las plataformas están en una sola versión: elige el archivo para tu software de servidor. Código abierto bajo GPL-3.0.',
  features: {
    title: 'Qué hace',
    items: [
      { icon: 'lock', title: 'Bloquear funciones', text: 'Por ejemplo el minimapa, fullbright o el zoom en una arena PvP. En el menú TRS aparece «Bloqueado por el servidor».' },
      { icon: 'check', title: 'Forzar funciones', text: 'Una función siempre activa, p. ej. sprint permanente en un servidor de parkour.' },
      { icon: 'filter', title: 'Limitar ajustes', text: 'Un valor fijo o un rango, p. ej. zoom como máximo 4× o freecam solo con juego limpio (sin cámara a través de paredes).' },
      { icon: 'world', title: 'Por mundo y por servidor', text: 'Reglas por mundo en Bukkit, Sponge y mods de servidor, por servidor en Velocity y BungeeCord. Las entradas concretas tienen prioridad sobre las globales.' },
      { icon: 'key', title: 'Excepción para tu equipo', text: 'Los jugadores con el permiso trsrules.bypass no reciben reglas: útil para administradores y constructores.' },
      { icon: 'warn', title: 'Jugadores sin TRS Client', text: 'Opcional: si un jugador no responde como TRS Client a tiempo, no pasa nada, recibe un aviso o es expulsado.' },
    ],
  },
  how: {
    title: 'Cómo funciona',
    steps: [
      'Al entrar, el TRS Client saluda al servidor por un canal de plugin.',
      'El plugin responde con las reglas del mundo (o servidor) del jugador, también cuando no hay ninguna.',
      'Al cambiar de mundo o de servidor y tras /trsrules reload, las reglas se envían de nuevo y sustituyen a las anteriores.',
      'Cuando el jugador se va, el cliente olvida todas las reglas. No se guarda nada.',
    ],
    honest: 'TRS Server Rules solo controla el TRS Client. No es un anticheat: otros clientes lo ignoran y un cliente modificado puede hacerse pasar por TRS Client.',
  },
  platforms: {
    title: 'Plataformas',
    lead: 'Un jar por plataforma. Las reglas son iguales en todas; solo cambia dónde está la configuración.',
    file: 'Archivo',
    config: 'Configuración',
    items: [
      { id: 'bukkit', name: 'Spigot, Paper, Purpur y Folia', versions: 'Minecraft 1.8.8 hasta la versión más reciente, un jar para todas', file: 'trs-server-rules-bukkit-<version>.jar', config: 'plugins/TRSServerRules/config.yml' },
      { id: 'velocity', name: 'Velocity', versions: 'Velocity 3.x: reglas por servidor', file: 'trs-server-rules-velocity-<version>.jar', config: 'plugins/trs-server-rules/config.yml' },
      { id: 'bungee', name: 'BungeeCord y Waterfall', versions: 'Reglas por servidor', file: 'trs-server-rules-bungee-<version>.jar', config: 'plugins/TRSServerRules/config.yml' },
      { id: 'sponge', name: 'Sponge', versions: 'Sponge API 8 y posteriores', file: 'trs-server-rules-sponge-<version>.jar', config: 'config/trsserverrules/config.yml' },
      { id: 'mods', name: 'Fabric, NeoForge y Forge (mod de servidor)', versions: 'Fabric 1.20.1, 1.21.1, 1.21.4, 1.21.11 · NeoForge 1.21.1, 1.21.11 · Forge 1.20.1: solo hace falta en el servidor', file: 'trs-server-rules-<loader>-<mc>-<version>.jar', config: 'config/trs-server-rules.yml' },
    ],
    proxy: 'Con un proxy, las reglas del proxy tienen prioridad sobre un plugin en el servidor de destino.',
  },
  config: {
    title: 'Configuración',
    lead: 'Si todo está comentado, no se bloquea nada. Los IDs de módulos y los nombres de ajustes están en la documentación.',
    commandsTitle: 'Comandos',
    commands: [
      { cmd: '/trsrules reload', text: 'Recarga la configuración y envía las nuevas reglas a todos.' },
      { cmd: '/trsrules status <jugador>', text: 'Muestra si un jugador usa el TRS Client y qué reglas se le aplican.' },
    ],
    permission: 'Permiso trsrules.admin (mods de servidor: nivel de OP 3).',
  },
  team: {
    title: 'Documentado abiertamente: excepción del equipo TRS',
    text:
      'Los miembros del equipo TRS con el permiso de equipo serverrules.bypass pueden usar igualmente una función bloqueada, pero solo tras confirmar un aviso en el juego («Este servidor bloquea Minimapa. ¿Usarlo igualmente como equipo TRS?») y solo hasta que salen del servidor.',
    points: [
      'Existe para que el equipo pueda probar funciones y ayudar a jugadores en cualquier servidor.',
      'El servidor no se entera y no puede desactivarlo.',
      'Está escrito aquí, en la documentación, en el README y como comentario al principio de cada configuración: no está oculto.',
      'La excepción para tu propio equipo es el permiso de servidor trsrules.bypass, que controlas por completo.',
    ],
  },
  cta: { title: '¿Preguntas o falta una plataforma?', text: 'Pregunta en Discord o abre una incidencia: el plugin es de código abierto como el resto de TRS.' },
}

export const serversTexts: Record<Lang, ServersTexts> = { en, de, es }

/** Beispiel-Config (Kommentare englisch wie die mitgelieferte config.yml – gleich in allen Sprachen). */
export const SERVER_RULES_CONFIG_EXAMPLE = `# TRS Server Rules – control TRS Client features on your server.
# Note: members of the TRS team with the "serverrules.bypass" permission can override these rules
# after an in-game confirmation. This is documented on purpose.
modules:            # global
  minimap: false    # false = forced off, true = forced on
  fullbright: false
settings:
  zoom.factor: {max: 4}
  cinematic.freecamFairPlay: true
worlds:             # Bukkit/Sponge/mods: per world; Velocity/Bungee: "servers:" per backend server
  pvp_arena:
    modules: {zoom: false, freelook: false}
message: ""         # optional text shown once to TRS Client players
missing-client:     # players whose client does not answer with a TRS Client hello
  action: ignore    # ignore | notify | kick
  timeout-seconds: 10
  notify-message: "This server works best with the TRS Client."
  kick-message: "Please join with the TRS Client."
bypass-permission: trsrules.bypass   # players with this server permission get no rules
`
