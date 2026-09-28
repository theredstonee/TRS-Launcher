/**
 * Erfolge (§31): fester Katalog. IDs nie ändern oder wiederverwenden – nur anhängen. Geheime Erfolge haben neutrale IDs
 * (`secret_NN`), weil der öffentliche Katalog sie ohne Text zeigt.
 *
 * Belohnungen nur an Erfolgen, deren Fortschritt der Server selbst zählt (`VERIFIED_METRICS`) – ein Test prüft das.
 */

export type AchievementCategory = 'playtime' | 'launcher' | 'community' | 'secret'
export type Rarity = 'common' | 'uncommon' | 'rare' | 'epic' | 'legendary'
export type GoalUnit = 'count' | 'minutes' | 'days'

export interface Texts {
  en: string
  de: string
  es: string
}

export interface Reward {
  kind: 'cape' | 'cosmetic'
  id: string
}

/**
 * Messgrößen. Server-seitig gezählt: alles außer den Launcher-Meldungen (`REPORTED_METRICS`).
 * `*_flag` = 0/1.
 */
export const METRICS = [
  // ---- vom Server gezählt
  'play_minutes',
  'best_streak_days',
  'best_session_minutes',
  'friends',
  'chat_messages',
  'issues_opened',
  'bug_from_game_flag',
  'ideas_done',
  'bugs_fixed',
  'upvotes_received',
  'votes_cast',
  'circuits_approved',
  'packs_shared',
  'pack_installs',
  'worlds_hosted',
  'screenshots_shared',
  'cape_worn_flag',
  'cosmetic_equipped_flag',
  'duck_flag',
  'codes_redeemed',
  /** Anzahl freigeschalteter geheimer Erfolge (Meta-Erfolg `all_secrets`). */
  'secrets_unlocked',
  // ---- vom Launcher gemeldet (§31.4)
  'launches',
  'night_launch_flag',
  'mods_installed',
  'modpacks_installed',
  'clips_recorded',
  'crash_fixed_flag',
  'launcher_import_flag',
] as const

export type Metric = (typeof METRICS)[number]

export const REPORTED_METRICS: ReadonlySet<Metric> = new Set<Metric>([
  'launches',
  'night_launch_flag',
  'mods_installed',
  'modpacks_installed',
  'clips_recorded',
  'crash_fixed_flag',
  'launcher_import_flag',
])

export interface AchievementDef {
  id: string
  category: AchievementCategory
  metric: Metric
  /** Zielwert; ohne `goal` reicht 1 (ein Ereignis). */
  goal?: number
  unit?: GoalUnit
  points: number
  rarity: Rarity
  icon: string
  secret?: boolean
  reward?: Reward
  title: Texts
  description: Texts
}

export const ACHIEVEMENTS: readonly AchievementDef[] = [
  // ------------------------------------------------------------------ Spielzeit & Starts
  {
    id: 'first_launch', category: 'playtime', metric: 'launches', points: 5, rarity: 'common', icon: 'rocket',
    title: { en: 'Lift-off', de: 'Abheben', es: 'Despegue' },
    description: { en: 'Start a game from the TRS Launcher', de: 'Starte ein Spiel aus dem TRS Launcher', es: 'Inicia un juego desde el TRS Launcher' },
  },
  {
    id: 'launches_50', category: 'playtime', metric: 'launches', goal: 50, unit: 'count', points: 15, rarity: 'uncommon', icon: 'repeat',
    title: { en: 'Regular', de: 'Stammgast', es: 'Habitual' },
    description: { en: 'Start games 50 times', de: 'Starte 50-mal ein Spiel', es: 'Inicia juegos 50 veces' },
  },
  {
    id: 'launches_500', category: 'playtime', metric: 'launches', goal: 500, unit: 'count', points: 40, rarity: 'rare', icon: 'repeat',
    title: { en: 'Can\'t stop', de: 'Nicht zu bremsen', es: 'Imparable' },
    description: { en: 'Start games 500 times', de: 'Starte 500-mal ein Spiel', es: 'Inicia juegos 500 veces' },
  },
  {
    id: 'play_1h', category: 'playtime', metric: 'play_minutes', goal: 60, unit: 'minutes', points: 10, rarity: 'common', icon: 'clock',
    title: { en: 'Warming up', de: 'Warm geworden', es: 'Calentando' },
    description: { en: 'Play for 1 hour', de: 'Spiele 1 Stunde', es: 'Juega 1 hora' },
  },
  {
    id: 'play_10h', category: 'playtime', metric: 'play_minutes', goal: 600, unit: 'minutes', points: 20, rarity: 'uncommon', icon: 'clock',
    title: { en: 'Settling in', de: 'Eingelebt', es: 'Como en casa' },
    description: { en: 'Play for 10 hours', de: 'Spiele 10 Stunden', es: 'Juega 10 horas' },
  },
  {
    id: 'play_50h', category: 'playtime', metric: 'play_minutes', goal: 3000, unit: 'minutes', points: 40, rarity: 'rare', icon: 'hourglass',
    title: { en: 'Dedicated', de: 'Mit Hingabe', es: 'Dedicación' },
    description: { en: 'Play for 50 hours', de: 'Spiele 50 Stunden', es: 'Juega 50 horas' },
  },
  {
    id: 'play_100h', category: 'playtime', metric: 'play_minutes', goal: 6000, unit: 'minutes', points: 60, rarity: 'epic', icon: 'hourglass',
    reward: { kind: 'cape', id: 'veteran' },
    title: { en: 'Veteran', de: 'Veteran', es: 'Veterano' },
    description: { en: 'Play for 100 hours', de: 'Spiele 100 Stunden', es: 'Juega 100 horas' },
  },
  {
    id: 'play_500h', category: 'playtime', metric: 'play_minutes', goal: 30000, unit: 'minutes', points: 100, rarity: 'legendary', icon: 'trophy',
    title: { en: 'Living legend', de: 'Lebende Legende', es: 'Leyenda viva' },
    description: { en: 'Play for 500 hours', de: 'Spiele 500 Stunden', es: 'Juega 500 horas' },
  },
  {
    id: 'streak_7', category: 'playtime', metric: 'best_streak_days', goal: 7, unit: 'days', points: 25, rarity: 'uncommon', icon: 'calendar',
    title: { en: 'Week streak', de: 'Wochen-Serie', es: 'Racha semanal' },
    description: { en: 'Be online 7 days in a row', de: 'Sei 7 Tage in Folge online', es: 'Conéctate 7 días seguidos' },
  },
  {
    id: 'streak_30', category: 'playtime', metric: 'best_streak_days', goal: 30, unit: 'days', points: 60, rarity: 'epic', icon: 'flame',
    title: { en: 'On fire', de: 'Nicht zu stoppen', es: 'En llamas' },
    description: { en: 'Be online 30 days in a row', de: 'Sei 30 Tage in Folge online', es: 'Conéctate 30 días seguidos' },
  },
  // ------------------------------------------------------------------ Launcher-Funktionen
  {
    id: 'first_mod', category: 'launcher', metric: 'mods_installed', points: 5, rarity: 'common', icon: 'puzzle',
    title: { en: 'Tinkerer', de: 'Bastler', es: 'Manitas' },
    description: { en: 'Install your first mod', de: 'Installiere deine erste Mod', es: 'Instala tu primer mod' },
  },
  {
    id: 'mods_50', category: 'launcher', metric: 'mods_installed', goal: 50, unit: 'count', points: 20, rarity: 'uncommon', icon: 'boxes',
    title: { en: 'Mod collector', de: 'Mod-Sammler', es: 'Coleccionista de mods' },
    description: { en: 'Install 50 mods', de: 'Installiere 50 Mods', es: 'Instala 50 mods' },
  },
  {
    id: 'first_modpack', category: 'launcher', metric: 'modpacks_installed', points: 10, rarity: 'common', icon: 'package',
    title: { en: 'Packed and ready', de: 'Gut verpackt', es: 'Listo para jugar' },
    description: { en: 'Install your first modpack', de: 'Installiere dein erstes Modpack', es: 'Instala tu primer modpack' },
  },
  {
    id: 'first_pack_shared', category: 'launcher', metric: 'packs_shared', points: 15, rarity: 'uncommon', icon: 'share',
    title: { en: 'Sharing is caring', de: 'Teilen macht Freude', es: 'Compartir es vivir' },
    description: { en: 'Share a modpack as a link', de: 'Teile ein Modpack als Link', es: 'Comparte un modpack con un enlace' },
  },
  {
    id: 'pack_installs_10', category: 'launcher', metric: 'pack_installs', goal: 10, unit: 'count', points: 40, rarity: 'rare', icon: 'download',
    title: { en: 'Trendsetter', de: 'Trendsetter', es: 'Marcando tendencia' },
    description: { en: 'Your shared modpacks are installed 10 times by others', de: 'Andere installieren deine geteilten Modpacks 10-mal', es: 'Otros instalan tus modpacks compartidos 10 veces' },
  },
  {
    id: 'first_clip', category: 'launcher', metric: 'clips_recorded', points: 10, rarity: 'common', icon: 'film',
    title: { en: 'Caught on tape', de: 'Im Kasten', es: 'Grabado' },
    description: { en: 'Save your first clip', de: 'Speichere deinen ersten Clip', es: 'Guarda tu primer clip' },
  },
  {
    id: 'clips_25', category: 'launcher', metric: 'clips_recorded', goal: 25, unit: 'count', points: 20, rarity: 'uncommon', icon: 'clapperboard',
    title: { en: 'Director', de: 'Regie', es: 'Director' },
    description: { en: 'Save 25 clips', de: 'Speichere 25 Clips', es: 'Guarda 25 clips' },
  },
  {
    id: 'first_world_hosted', category: 'launcher', metric: 'worlds_hosted', points: 20, rarity: 'uncommon', icon: 'globe',
    title: { en: 'Open house', de: 'Tag der offenen Tür', es: 'Puertas abiertas' },
    description: { en: 'Open a world for your friends', de: 'Öffne eine Welt für deine Freunde', es: 'Abre un mundo para tus amigos' },
  },
  {
    id: 'cape_worn', category: 'launcher', metric: 'cape_worn_flag', points: 5, rarity: 'common', icon: 'cape',
    title: { en: 'Caped', de: 'Umhang an', es: 'Con capa' },
    description: { en: 'Put on a TRS cape', de: 'Lege einen TRS-Umhang an', es: 'Ponte una capa TRS' },
  },
  {
    id: 'cosmetic_equipped', category: 'launcher', metric: 'cosmetic_equipped_flag', points: 5, rarity: 'common', icon: 'hat',
    title: { en: 'Dressed up', de: 'Herausgeputzt', es: 'De punta en blanco' },
    description: { en: 'Equip a cosmetic', de: 'Rüste eine Kosmetik aus', es: 'Equipa un cosmético' },
  },
  {
    id: 'screenshot_shared', category: 'launcher', metric: 'screenshots_shared', points: 10, rarity: 'common', icon: 'camera',
    title: { en: 'Say cheese', de: 'Bitte lächeln', es: 'Di patata' },
    description: { en: 'Share a screenshot as a link', de: 'Teile einen Screenshot als Link', es: 'Comparte una captura con un enlace' },
  },
  {
    id: 'crash_fixed', category: 'launcher', metric: 'crash_fixed_flag', points: 10, rarity: 'common', icon: 'wrench',
    title: { en: 'Back on track', de: 'Wieder in der Spur', es: 'De vuelta al juego' },
    description: { en: 'Let the crash helper fix a crash', de: 'Lass den Absturz-Helfer einen Absturz beheben', es: 'Deja que el asistente de fallos arregle un fallo' },
  },
  {
    id: 'launcher_import', category: 'launcher', metric: 'launcher_import_flag', points: 10, rarity: 'common', icon: 'truck',
    title: { en: 'Moving in', de: 'Umgezogen', es: 'Mudanza' },
    description: { en: 'Import instances from another launcher', de: 'Importiere Instanzen aus einem anderen Launcher', es: 'Importa instancias de otro launcher' },
  },
  // ------------------------------------------------------------------ Community
  {
    id: 'first_friend', category: 'community', metric: 'friends', points: 10, rarity: 'common', icon: 'user-plus',
    title: { en: 'Better together', de: 'Gemeinsam besser', es: 'Mejor juntos' },
    description: { en: 'Make your first friend', de: 'Finde deinen ersten Freund', es: 'Haz tu primer amigo' },
  },
  {
    id: 'friends_10', category: 'community', metric: 'friends', goal: 10, unit: 'count', points: 30, rarity: 'rare', icon: 'users',
    reward: { kind: 'cosmetic', id: 'emote-party' },
    title: { en: 'Squad', de: 'Die Truppe', es: 'La pandilla' },
    description: { en: 'Have 10 friends', de: 'Habe 10 Freunde', es: 'Ten 10 amigos' },
  },
  {
    id: 'first_message', category: 'community', metric: 'chat_messages', points: 5, rarity: 'common', icon: 'message',
    title: { en: 'Hello there', de: 'Hallo!', es: '¡Hola!' },
    description: { en: 'Send your first chat message', de: 'Schreibe deine erste Chat-Nachricht', es: 'Envía tu primer mensaje de chat' },
  },
  {
    id: 'messages_500', category: 'community', metric: 'chat_messages', goal: 500, unit: 'count', points: 30, rarity: 'rare', icon: 'messages',
    title: { en: 'Chatterbox', de: 'Plaudertasche', es: 'Parlanchín' },
    description: { en: 'Send 500 chat messages', de: 'Schreibe 500 Chat-Nachrichten', es: 'Envía 500 mensajes de chat' },
  },
  {
    id: 'first_issue', category: 'community', metric: 'issues_opened', points: 10, rarity: 'common', icon: 'bug',
    title: { en: 'Your say', de: 'Mitreden', es: 'Tu opinión' },
    description: { en: 'Open an issue or idea', de: 'Erstelle ein Issue oder eine Idee', es: 'Abre un issue o una idea' },
  },
  {
    id: 'votes_10', category: 'community', metric: 'votes_cast', goal: 10, unit: 'count', points: 10, rarity: 'common', icon: 'vote',
    title: { en: 'Voice of the people', de: 'Stimme des Volkes', es: 'La voz del pueblo' },
    description: { en: 'Vote on 10 issues', de: 'Stimme bei 10 Issues ab', es: 'Vota en 10 issues' },
  },
  {
    id: 'upvotes_10', category: 'community', metric: 'upvotes_received', goal: 10, unit: 'count', points: 30, rarity: 'rare', icon: 'thumbs-up',
    title: { en: 'Crowd favourite', de: 'Publikumsliebling', es: 'Favorito del público' },
    description: { en: 'Receive 10 up-votes on your issues', de: 'Erhalte 10 positive Stimmen für deine Issues', es: 'Recibe 10 votos positivos en tus issues' },
  },
  {
    id: 'bug_squashed', category: 'community', metric: 'bugs_fixed', points: 25, rarity: 'uncommon', icon: 'bug-off',
    title: { en: 'Bug hunter', de: 'Käferjäger', es: 'Cazabichos' },
    description: { en: 'A bug you reported gets fixed', de: 'Ein von dir gemeldeter Fehler wird behoben', es: 'Se arregla un error que reportaste' },
  },
  {
    id: 'idea_implemented', category: 'community', metric: 'ideas_done', points: 50, rarity: 'epic', icon: 'lightbulb',
    reward: { kind: 'cape', id: 'ideengeber' },
    title: { en: 'Bright idea', de: 'Geistesblitz', es: 'Idea brillante' },
    description: { en: 'An idea you opened gets implemented', de: 'Eine Idee von dir wird umgesetzt', es: 'Se implementa una idea tuya' },
  },
  {
    id: 'circuit_approved', category: 'community', metric: 'circuits_approved', points: 40, rarity: 'rare', icon: 'circuit',
    title: { en: 'Redstone engineer', de: 'Redstone-Ingenieur', es: 'Ingeniero de redstone' },
    description: { en: 'Get a circuit into the circuit library', de: 'Bringe eine Schaltung in die Schaltungs-Bibliothek', es: 'Consigue un circuito en la biblioteca de circuitos' },
  },
  // ------------------------------------------------------------------ Geheim
  {
    // Quietscheente besitzen (versteckte Kosmetik, nur per Code).
    id: 'secret_01', category: 'secret', secret: true, metric: 'duck_flag', points: 25, rarity: 'rare', icon: 'duck',
    title: { en: 'Quack!', de: 'Quak!', es: '¡Cuac!' },
    description: { en: 'Find the rubber duck', de: 'Finde die Quietscheente', es: 'Encuentra el patito de goma' },
  },
  {
    // Start zwischen 3 und 4 Uhr Ortszeit (Launcher-Meldung `launch` mit `hour: 3`).
    id: 'secret_02', category: 'secret', secret: true, metric: 'night_launch_flag', points: 15, rarity: 'uncommon', icon: 'moon',
    title: { en: 'Night owl', de: 'Nachteule', es: 'Búho nocturno' },
    description: { en: 'Start a game between 3 and 4 a.m.', de: 'Starte ein Spiel zwischen 3 und 4 Uhr nachts', es: 'Inicia un juego entre las 3 y las 4 de la madrugada' },
  },
  {
    // Fehler aus dem Spiel heraus gemeldet (Issue mit Quelle `client`).
    id: 'secret_03', category: 'secret', secret: true, metric: 'bug_from_game_flag', points: 15, rarity: 'uncommon', icon: 'bug',
    title: { en: 'Field report', de: 'Bericht von der Front', es: 'Informe de campo' },
    description: { en: 'Report a bug from inside the game', de: 'Melde einen Fehler direkt aus dem Spiel', es: 'Reporta un error desde dentro del juego' },
  },
  {
    // Einen Code eingelöst.
    id: 'secret_04', category: 'secret', secret: true, metric: 'codes_redeemed', points: 10, rarity: 'uncommon', icon: 'key',
    title: { en: 'Secret code', de: 'Geheimcode', es: 'Código secreto' },
    description: { en: 'Redeem a code', de: 'Löse einen Code ein', es: 'Canjea un código' },
  },
  {
    // Eine Sitzung von 6 Stunden am Stück (Herzschläge ohne Lücke).
    id: 'secret_05', category: 'secret', secret: true, metric: 'best_session_minutes', goal: 360, unit: 'minutes', points: 30, rarity: 'epic', icon: 'timer',
    title: { en: 'Marathon', de: 'Marathon', es: 'Maratón' },
    description: { en: 'Play 6 hours in one session', de: 'Spiele 6 Stunden am Stück', es: 'Juega 6 horas seguidas' },
  },
  {
    // Meta-Erfolg: sichtbar (nicht geheim), zählt die freigeschalteten geheimen Erfolge. Immer als LETZTER Eintrag
    // geprüft (siehe achievements.ts), das Ziel wächst mit neuen geheimen Erfolgen mit.
    id: 'all_secrets', category: 'secret', metric: 'secrets_unlocked', goal: 5, unit: 'count', points: 50, rarity: 'legendary', icon: 'crown',
    reward: { kind: 'cosmetic', id: 'secret-crown' },
    title: { en: 'Explorer', de: 'Entdecker', es: 'Explorador' },
    description: { en: 'Unlock every secret achievement', de: 'Schalte alle geheimen Erfolge frei', es: 'Desbloquea todos los logros secretos' },
  },
]

/** Reihenfolge = Position im Katalog. */
export const ACHIEVEMENT_ORDER: ReadonlyMap<string, number> = new Map(ACHIEVEMENTS.map((a, i) => [a.id, i]))
export const ACHIEVEMENT_BY_ID: ReadonlyMap<string, AchievementDef> = new Map(ACHIEVEMENTS.map((a) => [a.id, a]))

/** Höchstes Ziel je Messgröße (Zähler aus Meldungen laufen nicht weiter). */
export function maxGoal(metric: Metric): number {
  let max = 1
  for (const a of ACHIEVEMENTS) if (a.metric === metric) max = Math.max(max, a.goal ?? 1)
  return max
}
