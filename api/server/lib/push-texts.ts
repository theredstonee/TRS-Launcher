/**
 * Texte der Push-Benachrichtigungen (§33) in Englisch, Deutsch und Spanisch. Andere Sprachen → Englisch.
 * Platzhalter `{name}` usw. werden mit (gekürzten) Werten gefüllt.
 */

export const PUSH_LANGS = ['en', 'de', 'es'] as const
export type PushLang = (typeof PUSH_LANGS)[number]

/** `de-AT` → `de`, unbekannt → `en`. */
export function pushLang(locale: string | null | undefined): PushLang {
  const l = (locale ?? '').toLowerCase().split(/[-_]/)[0]
  return (PUSH_LANGS as readonly string[]).includes(l ?? '') ? (l as PushLang) : 'en'
}

type Texts = Record<PushLang, string>

export const PUSH_TEXTS = {
  chatNew: { en: 'New message', de: 'Neue Nachricht', es: 'Nuevo mensaje' },
  chatNewGroup: { en: 'New message in a group', de: 'Neue Nachricht in einer Gruppe', es: 'Nuevo mensaje en un grupo' },
  chatPicture: { en: 'Sent a picture', de: 'Hat ein Bild geschickt', es: 'Ha enviado una imagen' },
  chatServerInvite: { en: 'Sent a server invite', de: 'Hat eine Server-Einladung geschickt', es: 'Ha enviado una invitación a un servidor' },
  chatWorldInvite: { en: 'Sent a world invite', de: 'Hat eine Welt-Einladung geschickt', es: 'Ha enviado una invitación a un mundo' },
  chatWaypoint: { en: 'Shared a waypoint', de: 'Hat einen Wegpunkt geteilt', es: 'Ha compartido un punto de ruta' },
  friendRequestTitle: { en: 'Friend request', de: 'Freundschaftsanfrage', es: 'Solicitud de amistad' },
  friendRequestBody: { en: '{name} wants to be your friend.', de: '{name} möchte mit dir befreundet sein.', es: '{name} quiere ser tu amigo.' },
  friendAddedTitle: { en: 'New friend', de: 'Neuer Freund', es: 'Nuevo amigo' },
  friendAddedBody: {
    en: '{name} accepted your friend request.',
    de: '{name} hat deine Freundschaftsanfrage angenommen.',
    es: '{name} ha aceptado tu solicitud de amistad.',
  },
  friendOnlineTitle: { en: '{name} is online', de: '{name} ist online', es: '{name} está en línea' },
  friendOnlinePlaying: { en: 'Playing Minecraft {version}', de: 'Spielt Minecraft {version}', es: 'Jugando a Minecraft {version}' },
  friendOnlineLauncher: { en: 'In the TRS Launcher', de: 'Im TRS Launcher', es: 'En el TRS Launcher' },
  capeOfferTitle: { en: 'Cape offer', de: 'Umhang-Angebot', es: 'Oferta de capa' },
  capeOfferBody: {
    en: '{name} wants to share the cape "{cape}" with you.',
    de: '{name} möchte den Umhang „{cape}“ mit dir teilen.',
    es: '{name} quiere compartir contigo la capa «{cape}».',
  },
  worldInviteTitle: { en: 'World invite', de: 'Welt-Einladung', es: 'Invitación a un mundo' },
  worldInviteBody: { en: '{name} invites you to "{world}".', de: '{name} lädt dich in „{world}“ ein.', es: '{name} te invita a «{world}».' },
  joinRequestTitle: { en: 'Join request', de: 'Beitrittsanfrage', es: 'Solicitud para unirse' },
  joinRequestBody: { en: '{name} wants to join your world.', de: '{name} möchte deiner Welt beitreten.', es: '{name} quiere unirse a tu mundo.' },
  joinAcceptedTitle: { en: 'You are in', de: 'Du bist drin', es: 'Ya estás dentro' },
  joinAcceptedBody: { en: 'You can join "{world}" now.', de: 'Du kannst „{world}“ jetzt betreten.', es: 'Ya puedes entrar en «{world}».' },
  kickedTitle: { en: 'Removed from a world', de: 'Aus einer Welt entfernt', es: 'Expulsado de un mundo' },
  kickedBody: { en: 'The host removed you from the world.', de: 'Der Host hat dich aus der Welt entfernt.', es: 'El anfitrión te ha sacado del mundo.' },
  packTitle: { en: 'Modpack received', de: 'Modpack erhalten', es: 'Modpack recibido' },
  packBody: { en: '{name} sent you "{pack}".', de: '{name} hat dir „{pack}“ geschickt.', es: '{name} te ha enviado «{pack}».' },
  moderationTitle: { en: 'Moderation', de: 'Moderation', es: 'Moderación' },
  sanctionWarn: { en: 'You received a warning.', de: 'Du hast eine Verwarnung erhalten.', es: 'Has recibido una advertencia.' },
  sanctionOther: {
    en: 'A restriction was placed on your account.',
    de: 'Dein Konto wurde eingeschränkt.',
    es: 'Se ha aplicado una restricción a tu cuenta.',
  },
  appealTitle: { en: 'Appeal decided', de: 'Einspruch entschieden', es: 'Apelación resuelta' },
  appealBody: { en: 'The team has decided on your appeal.', de: 'Das Team hat über deinen Einspruch entschieden.', es: 'El equipo ha resuelto tu apelación.' },
  reportTitle: { en: 'Report reviewed', de: 'Meldung geprüft', es: 'Denuncia revisada' },
  reportBody: {
    en: 'Thank you! The team has reviewed your report.',
    de: 'Danke! Das Team hat deine Meldung geprüft.',
    es: '¡Gracias! El equipo ha revisado tu denuncia.',
  },
  applicationTitle: { en: 'Team application', de: 'Team-Bewerbung', es: 'Solicitud al equipo' },
  applicationBody: { en: 'There is news about your application.', de: 'Es gibt Neuigkeiten zu deiner Bewerbung.', es: 'Hay novedades sobre tu solicitud.' },
  circuitAcceptedTitle: { en: 'Circuit accepted', de: 'Schaltung angenommen', es: 'Circuito aceptado' },
  circuitAcceptedBody: {
    en: '"{name}" is now in the circuit library.',
    de: '„{name}“ ist jetzt in der Schaltungs-Bibliothek.',
    es: '«{name}» ya está en la biblioteca de circuitos.',
  },
  circuitRejectedTitle: { en: 'Circuit not accepted', de: 'Schaltung nicht angenommen', es: 'Circuito no aceptado' },
  circuitRejectedBody: { en: 'The team has reviewed "{name}".', de: 'Das Team hat „{name}“ geprüft.', es: 'El equipo ha revisado «{name}».' },
  issueTitle: { en: 'Issue #{number}', de: 'Issue #{number}', es: 'Issue #{number}' },
  issueStatus: { en: 'The status has changed.', de: 'Der Status hat sich geändert.', es: 'El estado ha cambiado.' },
  issueComment: { en: 'The team has replied.', de: 'Das Team hat geantwortet.', es: 'El equipo ha respondido.' },
  issueFixed: { en: 'Fixed in {version}.', de: 'Behoben in {version}.', es: 'Corregido en {version}.' },
  issueFixedNoVersion: { en: 'Marked as fixed.', de: 'Als behoben markiert.', es: 'Marcado como corregido.' },
  issueMerged: { en: 'Merged into #{number}.', de: 'Zusammengeführt mit #{number}.', es: 'Fusionado con #{number}.' },
  achievementTitle: { en: 'Achievement unlocked', de: 'Erfolg freigeschaltet', es: 'Logro desbloqueado' },
} satisfies Record<string, Texts>

export type PushTextKey = keyof typeof PUSH_TEXTS

/** Ein Wert für Platzhalter: Steuerzeichen raus, Leerraum zusammengefasst, gekürzt. */
export function clip(s: string, max: number): string {
  const clean = s.replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, ' ').replace(/\s+/g, ' ').trim()
  const chars = [...clean]
  return chars.length <= max ? clean : `${chars.slice(0, max - 1).join('').trimEnd()}…`
}

export function pushText(key: PushTextKey, lang: PushLang, vars: Record<string, string | number> = {}): string {
  return PUSH_TEXTS[key][lang].replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m))
}
