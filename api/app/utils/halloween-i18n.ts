// Texte für Halloween 2026 „Nacht der langen Schatten“ auf der Website (EN/DE/ES).

import type { Lang } from '~/utils/messages'

export interface HalloweenTexts {
  eyebrow: string
  until: string
  lead: string
  panelTitle: string
  hunt: string
  huntCount: string
  hintLabel: string
  huntDone: string
  trickTitle: string
  trickLead: string
  knock: string
  knocking: string
  usedToday: string
  nextAt: string
  sweet: string
  sour: string
  sourText: string
  sweetText: string
  bubble: string
  kinds: { cape: string, emote: string, sticker: string }
  rewardLine: string
  loginTitle: string
  loginText: string
  login: string
  found: string
  foundOf: string
  foundAll: string
  loginToCount: string
  loginToCountLink: string
  inactive: string
  error: string
  flickerTitle: string
  flickerHint: string
  flickerOff: string
  flickerKeep: string
  flickerSwitch: string
  /** Eigener Schalter „Event-Design anzeigen“ (§32.6) + Hinweis, dass Kürbis-Suche usw. bleiben. */
  lookSwitch: string
  lookSwitchHint: string
  wingsTitle: string
  wingsLead: string
  wingsPick: string
  wingsConfirm: string
  wingsCancel: string
  wingsChosen: string
  wingsPending: string
  wingNames: Record<string, string>
  badges: Record<string, string>
  pumpkinLabel: string
  whispers: string[]
  pool: string[]
}

const en: HalloweenTexts = {
  eyebrow: 'Halloween 2026 · Night of the Long Shadows',
  until: 'until 2 November',
  lead: 'Find the 13 hidden pumpkins in the launcher, on the website and in the client, knock every evening – trick or treat – and earn the badge that only exists this year.',
  panelTitle: 'Halloween 2026',
  hunt: 'Pumpkin hunt',
  huntCount: '{n} of 13 found',
  hintLabel: 'Next hint',
  huntDone: 'All 13 found! Pumpkin Hunter 2026 badge and cape are yours.',
  trickTitle: 'Trick or treat?',
  trickLead: 'Knock once a day. Usually there is a treat – sometimes just a trick, and the pumpkin sticks its tongue out at you.',
  knock: 'Knock',
  knocking: 'Knock, knock …',
  usedToday: 'You already knocked today.',
  nextAt: 'Next knock from {time}',
  sweet: 'Treat',
  sour: 'Trick',
  sourText: 'Nothing today. The pumpkin giggles. Try again tomorrow after midnight.',
  sweetText: 'It is yours now – also after Halloween.',
  bubble: 'Trick!',
  kinds: { cape: 'Cape', emote: 'Emote', sticker: 'Chat sticker' },
  rewardLine: 'Moon Night cape – random colour, only one per player · 4 emotes · 6 chat stickers · a trick about 1 in 4',
  loginTitle: 'Join the hunt',
  loginText: 'Log in so your pumpkin finds count and you can knock for trick or treat.',
  login: 'Log in',
  found: 'Pumpkin found!',
  foundOf: '{n} of 13 – the next hint is waiting.',
  foundAll: 'All 13! Pumpkin Hunter 2026 badge and cape unlocked.',
  loginToCount: 'Log in so your find counts.',
  loginToCountLink: 'Log in',
  inactive: 'The Halloween event is over – thanks for hunting!',
  error: 'Something went wrong. Please try again.',
  flickerTitle: 'Flicker & grain',
  flickerHint: 'For Halloween the background crackles like an old film and the page flickers when lightning strikes. If that bothers you, switch it off here. You can find the switch later at the bottom of every page.',
  flickerOff: 'Switch off',
  flickerKeep: 'Keep',
  flickerSwitch: 'Flicker & grain',
  lookSwitch: 'Show event design',
  lookSwitchHint: 'Turns off the Halloween look and effects. The pumpkin hunt, trick or treat and rewards stay.',
  wingsTitle: 'Your hunter wings',
  wingsLead: 'You found all 13 pumpkins. Choose ONE pair of wings – the choice is final.',
  wingsPick: 'Choose',
  wingsConfirm: 'Yes, take these wings',
  wingsCancel: 'Back',
  wingsChosen: 'Your wings: {name}',
  wingsPending: 'The wings are almost ready – check back soon.',
  wingNames: { bat_wings: 'Bat Wings', demon_wings: 'Demon Wings', raven_wings: 'Raven Wings', ghost_wings: 'Ghost Wings' },
  badges: { halloween2026: 'Halloween 2026', pumpkinhunter2026: 'Pumpkin Hunter 2026' },
  pumpkinLabel: 'A hidden pumpkin',
  whispers: ['… don’t turn around', 'I see you', 'thirteen …', 'you are not alone', 'do you hear that?', 'come closer …', 'it is behind the moon'],
  pool: ['Moon Night cape – random colour, only one per player', '4 emotes', '6 chat stickers', 'Trick ≈ 1 in 4'],
}

const de: HalloweenTexts = {
  eyebrow: 'Halloween 2026 · Nacht der langen Schatten',
  until: 'bis 2. November',
  lead: 'Finde die 13 verborgenen Kürbisse in Launcher, Website und Client, klopf jeden Abend an – Süßes oder Saures – und hol dir das Abzeichen, das es nur dieses Jahr gibt.',
  panelTitle: 'Halloween 2026',
  hunt: 'Kürbis-Suche',
  huntCount: '{n} von 13 gefunden',
  hintLabel: 'Nächster Hinweis',
  huntDone: 'Alle 13 gefunden! Abzeichen und Umhang „Kürbisjäger 2026“ gehören dir.',
  trickTitle: 'Süßes oder Saures?',
  trickLead: 'Einmal am Tag anklopfen. Meistens gibt’s Süßes – manchmal aber auch nur Saures, und der Kürbis streckt dir die Zunge raus.',
  knock: 'Anklopfen',
  knocking: 'Klopf, klopf …',
  usedToday: 'Heute hast du schon angeklopft.',
  nextAt: 'Nächstes Anklopfen ab {time}',
  sweet: 'Süßes',
  sour: 'Saures',
  sourText: 'Heute leider nichts. Der Kürbis kichert. Nächster Versuch morgen ab Mitternacht.',
  sweetText: 'Gehört jetzt dir – auch nach Halloween.',
  bubble: 'Saures!',
  kinds: { cape: 'Umhang', emote: 'Emote', sticker: 'Chat-Sticker' },
  rewardLine: 'Mondnacht-Umhang – zufällige Farbe, jeder nur einen · 4 Emotes · 6 Chat-Sticker · Saures ≈ 1 von 4',
  loginTitle: 'Mach mit bei der Suche',
  loginText: 'Melde dich an, damit deine Kürbis-Funde zählen und du für Süßes oder Saures anklopfen kannst.',
  login: 'Anmelden',
  found: 'Kürbis gefunden!',
  foundOf: '{n} von 13 – der nächste Hinweis wartet.',
  foundAll: 'Alle 13! Abzeichen „Kürbisjäger 2026“ und Umhang freigeschaltet.',
  loginToCount: 'Melde dich an, damit dein Fund zählt.',
  loginToCountLink: 'Anmelden',
  inactive: 'Das Halloween-Event ist vorbei – danke fürs Suchen!',
  error: 'Da ist etwas schiefgegangen. Bitte versuch es noch einmal.',
  flickerTitle: 'Flackern & Rauschen',
  flickerHint: 'Zu Halloween rauscht der Hintergrund wie ein alter Film, und bei Blitzen flackert die Seite kurz auf. Wenn dich das stört, schalte es hier aus. Später findest du den Schalter unten auf jeder Seite.',
  flickerOff: 'Ausschalten',
  flickerKeep: 'Anlassen',
  flickerSwitch: 'Flackern & Rauschen',
  lookSwitch: 'Event-Design anzeigen',
  lookSwitchHint: 'Schaltet den Halloween-Look und die Effekte aus. Kürbis-Suche, Süßes oder Saures und Belohnungen bleiben.',
  wingsTitle: 'Deine Jäger-Flügel',
  wingsLead: 'Du hast alle 13 Kürbisse gefunden. Wähle EIN Paar Flügel – die Wahl ist endgültig.',
  wingsPick: 'Wählen',
  wingsConfirm: 'Ja, diese Flügel nehmen',
  wingsCancel: 'Zurück',
  wingsChosen: 'Deine Flügel: {name}',
  wingsPending: 'Die Flügel sind fast fertig – schau bald wieder vorbei.',
  wingNames: { bat_wings: 'Fledermausflügel', demon_wings: 'Dämonenflügel', raven_wings: 'Rabenflügel', ghost_wings: 'Geisterflügel' },
  badges: { halloween2026: 'Halloween 2026', pumpkinhunter2026: 'Kürbisjäger 2026' },
  pumpkinLabel: 'Ein versteckter Kürbis',
  whispers: ['… dreh dich nicht um', 'ich sehe dich', 'dreizehn …', 'du bist nicht allein', 'hörst du das?', 'komm näher …', 'es ist hinter dem Mond'],
  pool: ['Mondnacht-Umhang – zufällige Farbe, jeder nur einen', '4 Emotes', '6 Chat-Sticker', 'Saures ≈ 1 von 4'],
}

const es: HalloweenTexts = {
  eyebrow: 'Halloween 2026 · La noche de las sombras largas',
  until: 'hasta el 2 de noviembre',
  lead: 'Encuentra las 13 calabazas escondidas en el launcher, la web y el cliente, llama cada noche – truco o trato – y consigue la insignia que solo existe este año.',
  panelTitle: 'Halloween 2026',
  hunt: 'Búsqueda de calabazas',
  huntCount: '{n} de 13 encontradas',
  hintLabel: 'Siguiente pista',
  huntDone: '¡Las 13 encontradas! La insignia y la capa Cazador de calabazas 2026 son tuyas.',
  trickTitle: '¿Truco o trato?',
  trickLead: 'Llama una vez al día. Casi siempre hay trato – a veces solo truco, y la calabaza te saca la lengua.',
  knock: 'Llamar',
  knocking: 'Toc, toc …',
  usedToday: 'Hoy ya has llamado.',
  nextAt: 'Puedes volver a llamar desde las {time}',
  sweet: 'Trato',
  sour: 'Truco',
  sourText: 'Hoy no hay nada. La calabaza se ríe. Vuelve a intentarlo mañana después de medianoche.',
  sweetText: 'Ahora es tuyo – también después de Halloween.',
  bubble: '¡Truco!',
  kinds: { cape: 'Capa', emote: 'Emote', sticker: 'Sticker de chat' },
  rewardLine: 'Capa Noche de luna – color aleatorio, solo una por jugador · 4 emotes · 6 stickers · truco ≈ 1 de cada 4',
  loginTitle: 'Únete a la búsqueda',
  loginText: 'Inicia sesión para que cuenten tus calabazas y puedas llamar para truco o trato.',
  login: 'Iniciar sesión',
  found: '¡Calabaza encontrada!',
  foundOf: '{n} de 13 – la siguiente pista te espera.',
  foundAll: '¡Las 13! Insignia y capa Cazador de calabazas 2026 desbloqueadas.',
  loginToCount: 'Inicia sesión para que cuente tu hallazgo.',
  loginToCountLink: 'Iniciar sesión',
  inactive: 'El evento de Halloween ha terminado – ¡gracias por buscar!',
  error: 'Algo ha salido mal. Inténtalo de nuevo.',
  flickerTitle: 'Parpadeo y grano',
  flickerHint: 'En Halloween el fondo cruje como una película antigua y la página parpadea con los rayos. Si te molesta, apágalo aquí. Más tarde encontrarás el interruptor al pie de cada página.',
  flickerOff: 'Apagar',
  flickerKeep: 'Dejar',
  flickerSwitch: 'Parpadeo y grano',
  lookSwitch: 'Mostrar diseño del evento',
  lookSwitchHint: 'Desactiva el aspecto y los efectos de Halloween. La búsqueda de calabazas, truco o trato y las recompensas se mantienen.',
  wingsTitle: 'Tus alas de cazador',
  wingsLead: 'Has encontrado las 13 calabazas. Elige UN par de alas – la elección es definitiva.',
  wingsPick: 'Elegir',
  wingsConfirm: 'Sí, quiero estas alas',
  wingsCancel: 'Volver',
  wingsChosen: 'Tus alas: {name}',
  wingsPending: 'Las alas están casi listas – vuelve pronto.',
  wingNames: { bat_wings: 'Alas de murciélago', demon_wings: 'Alas de demonio', raven_wings: 'Alas de cuervo', ghost_wings: 'Alas fantasmales' },
  badges: { halloween2026: 'Halloween 2026', pumpkinhunter2026: 'Cazador de calabazas 2026' },
  pumpkinLabel: 'Una calabaza escondida',
  whispers: ['… no te des la vuelta', 'te veo', 'trece …', 'no estás solo', '¿lo oyes?', 'acércate …', 'está detrás de la luna'],
  pool: ['Capa Noche de luna – color aleatorio, solo una por jugador', '4 emotes', '6 stickers de chat', 'Truco ≈ 1 de cada 4'],
}

export const HALLOWEEN_TEXTS: Record<Lang, HalloweenTexts> = { en, de, es }
