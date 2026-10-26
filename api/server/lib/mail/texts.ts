/** Feste Texte der Mails (DE/EN/ES). Platzhalter `{name}`. */

export const MAIL_LANGS = ['de', 'en', 'es'] as const
export type MailLang = (typeof MAIL_LANGS)[number]
export const isMailLang = (v: unknown): v is MailLang => v === 'de' || v === 'en' || v === 'es'

export interface MailTexts {
  website: string
  wiki: string
  discord: string
  unsubscribe: string
  privacy: string
  imprint: string
  brandLine: string
  reasonNewsletter: string
  reasonTeam: string
  teamSettings: string
  confirm: { subject: string, preheader: string, title: string, lead: string, button: string, note: string, reason: string }
  teamConfirm: { subject: string, preheader: string, title: string, lead: string, button: string, note: string, reason: string }
  update: { download: string, autoUpdate: string, banner: string }
  cosmetic: { redeem: string, validUntil: string, perAccount: string }
  creator: { code: string, channel: string, page: string, tiers: Record<'starter' | 'partner' | 'top', string> }
  team: {
    kicker: string
    open: string
    kinds: Record<TeamKind, { one: string, many: string }>
    fields: Record<string, string>
    more: string
  }
  test: string
}

export type TeamKind = 'application' | 'report' | 'appeal' | 'pet_request'
export const TEAM_KINDS: readonly TeamKind[] = ['application', 'report', 'appeal', 'pet_request']

export const MAIL_TEXTS: Record<MailLang, MailTexts> = {
  de: {
    website: 'Website',
    wiki: 'Wiki',
    discord: 'Discord',
    unsubscribe: 'Mit einem Klick abmelden',
    privacy: 'Datenschutz',
    imprint: 'Impressum',
    brandLine: 'TRS Launcher · by TheRedstonee',
    reasonNewsletter: 'Du bekommst diese Mail, weil du den TRS-Newsletter abonniert hast.',
    reasonTeam: 'Du bekommst diese Mail, weil du im TRS-Team Benachrichtigungen für {kind} aktiviert hast.',
    teamSettings: 'Benachrichtigungen einstellen',
    confirm: {
      subject: 'Bitte bestätige deine Anmeldung zum TRS-Newsletter',
      preheader: 'Bestätige deine Anmeldung zum TRS-Newsletter.',
      title: 'Fast *geschafft!*',
      lead: 'Bitte bestätige, dass du den TRS-Newsletter bekommen möchtest.\nUpdates, neue Cosmetics und ab und zu ein Gratis-Code.',
      button: 'Anmeldung bestätigen',
      note: 'Der Link gilt 48 Stunden. Du warst das nicht? Dann ignoriere diese Mail einfach – ohne Bestätigung passiert nichts.',
      reason: 'Diese Mail wurde angefordert für {email}.',
    },
    teamConfirm: {
      subject: 'Bestätige deine Adresse für TRS-Team-Benachrichtigungen',
      preheader: 'Bestätige deine Adresse für Team-Benachrichtigungen.',
      title: 'Adresse *bestätigen*',
      lead: 'Du möchtest Team-Benachrichtigungen an diese Adresse bekommen. Bitte bestätige sie.',
      button: 'Adresse bestätigen',
      note: 'Der Link gilt 48 Stunden. Du warst das nicht? Dann ignoriere diese Mail einfach.',
      reason: 'Diese Mail wurde im TRS-Team-Bereich angefordert für {email}.',
    },
    update: { download: 'Jetzt aktualisieren', autoUpdate: 'Der Launcher aktualisiert sich auch von selbst.', banner: 'Update-Banner {version}' },
    cosmetic: { redeem: 'Löse den Code im Launcher unter **Skins → Code einlösen** ein.', validUntil: 'Gültig bis {date}', perAccount: 'einmal pro Konto' },
    creator: { code: 'Creator-Code', channel: 'Zum Kanal', page: 'Zur Creator-Seite', tiers: { starter: 'CREATOR', partner: 'PARTNER-CREATOR', top: 'TOP-CREATOR' } },
    team: {
      kicker: 'Team-Benachrichtigung',
      open: 'Im Admin öffnen',
      kinds: {
        application: { one: 'Neue Bewerbung eingegangen', many: '{n} neue Bewerbungen' },
        report: { one: 'Neue Meldung eingegangen', many: '{n} neue Meldungen' },
        appeal: { one: 'Neuer Einspruch eingegangen', many: '{n} neue Einsprüche' },
        pet_request: { one: 'Neuer Haustier-Antrag', many: '{n} neue Haustier-Anträge' },
      },
      fields: { kind: 'Art', job: 'Stelle', from: 'Von', at: 'Eingang', reason: 'Grund', id: 'Nummer', sanction: 'Strafe', species: 'Tier', creator: 'Creator' },
      more: '… und {n} weitere',
    },
    test: 'TEST',
  },
  en: {
    website: 'Website',
    wiki: 'Wiki',
    discord: 'Discord',
    unsubscribe: 'Unsubscribe with one click',
    privacy: 'Privacy',
    imprint: 'Imprint',
    brandLine: 'TRS Launcher · by TheRedstonee',
    reasonNewsletter: 'You are getting this e-mail because you subscribed to the TRS newsletter.',
    reasonTeam: 'You are getting this e-mail because you turned on notifications for {kind} in the TRS team area.',
    teamSettings: 'Notification settings',
    confirm: {
      subject: 'Please confirm your TRS newsletter subscription',
      preheader: 'Confirm your subscription to the TRS newsletter.',
      title: 'Almost *there!*',
      lead: 'Please confirm that you want to get the TRS newsletter.\nUpdates, new cosmetics and now and then a free code.',
      button: 'Confirm subscription',
      note: 'The link is valid for 48 hours. Not you? Just ignore this e-mail – nothing happens without confirmation.',
      reason: 'This e-mail was requested for {email}.',
    },
    teamConfirm: {
      subject: 'Confirm your address for TRS team notifications',
      preheader: 'Confirm your address for team notifications.',
      title: 'Confirm *address*',
      lead: 'You want to get team notifications at this address. Please confirm it.',
      button: 'Confirm address',
      note: 'The link is valid for 48 hours. Not you? Just ignore this e-mail.',
      reason: 'This e-mail was requested in the TRS team area for {email}.',
    },
    update: { download: 'Update now', autoUpdate: 'The launcher also updates itself.', banner: 'Update banner {version}' },
    cosmetic: { redeem: 'Redeem the code in the launcher under **Skins → Redeem code**.', validUntil: 'Valid until {date}', perAccount: 'once per account' },
    creator: { code: 'Creator code', channel: 'Visit channel', page: 'Creator page', tiers: { starter: 'CREATOR', partner: 'PARTNER CREATOR', top: 'TOP CREATOR' } },
    team: {
      kicker: 'Team notification',
      open: 'Open in admin',
      kinds: {
        application: { one: 'New application received', many: '{n} new applications' },
        report: { one: 'New report received', many: '{n} new reports' },
        appeal: { one: 'New appeal received', many: '{n} new appeals' },
        pet_request: { one: 'New pet request', many: '{n} new pet requests' },
      },
      fields: { kind: 'Type', job: 'Position', from: 'From', at: 'Received', reason: 'Reason', id: 'Number', sanction: 'Sanction', species: 'Animal', creator: 'Creator' },
      more: '… and {n} more',
    },
    test: 'TEST',
  },
  es: {
    website: 'Web',
    wiki: 'Wiki',
    discord: 'Discord',
    unsubscribe: 'Darse de baja con un clic',
    privacy: 'Privacidad',
    imprint: 'Aviso legal',
    brandLine: 'TRS Launcher · by TheRedstonee',
    reasonNewsletter: 'Recibes este correo porque te suscribiste al boletín de TRS.',
    reasonTeam: 'Recibes este correo porque activaste las notificaciones de {kind} en el área del equipo TRS.',
    teamSettings: 'Ajustes de notificaciones',
    confirm: {
      subject: 'Confirma tu suscripción al boletín de TRS',
      preheader: 'Confirma tu suscripción al boletín de TRS.',
      title: '¡Casi *listo!*',
      lead: 'Confirma que quieres recibir el boletín de TRS.\nActualizaciones, cosméticos nuevos y de vez en cuando un código gratis.',
      button: 'Confirmar suscripción',
      note: 'El enlace vale 48 horas. ¿No fuiste tú? Ignora este correo: sin confirmación no pasa nada.',
      reason: 'Este correo se solicitó para {email}.',
    },
    teamConfirm: {
      subject: 'Confirma tu dirección para las notificaciones del equipo TRS',
      preheader: 'Confirma tu dirección para las notificaciones del equipo.',
      title: 'Confirmar *dirección*',
      lead: 'Quieres recibir notificaciones del equipo en esta dirección. Confírmala.',
      button: 'Confirmar dirección',
      note: 'El enlace vale 48 horas. ¿No fuiste tú? Ignora este correo.',
      reason: 'Este correo se solicitó en el área del equipo TRS para {email}.',
    },
    update: { download: 'Actualizar ahora', autoUpdate: 'El launcher también se actualiza solo.', banner: 'Banner de la actualización {version}' },
    cosmetic: { redeem: 'Canjea el código en el launcher en **Skins → Canjear código**.', validUntil: 'Válido hasta el {date}', perAccount: 'una vez por cuenta' },
    creator: { code: 'Código de creador', channel: 'Ir al canal', page: 'Página del creador', tiers: { starter: 'CREADOR', partner: 'CREADOR PARTNER', top: 'CREADOR TOP' } },
    team: {
      kicker: 'Notificación del equipo',
      open: 'Abrir en el admin',
      kinds: {
        application: { one: 'Nueva solicitud recibida', many: '{n} solicitudes nuevas' },
        report: { one: 'Nuevo reporte recibido', many: '{n} reportes nuevos' },
        appeal: { one: 'Nueva apelación recibida', many: '{n} apelaciones nuevas' },
        pet_request: { one: 'Nueva solicitud de mascota', many: '{n} solicitudes de mascota nuevas' },
      },
      fields: { kind: 'Tipo', job: 'Puesto', from: 'De', at: 'Recibido', reason: 'Motivo', id: 'Número', sanction: 'Sanción', species: 'Animal', creator: 'Creador' },
      more: '… y {n} más',
    },
    test: 'PRUEBA',
  },
}

/** Name der Benachrichtigungs-Art im Satz „… für {kind} aktiviert“. */
export const TEAM_KIND_NAMES: Record<MailLang, Record<TeamKind, string>> = {
  de: { application: 'Bewerbungen', report: 'Meldungen', appeal: 'Einsprüche', pet_request: 'Haustier-Anträge' },
  en: { application: 'applications', report: 'reports', appeal: 'appeals', pet_request: 'pet requests' },
  es: { application: 'solicitudes', report: 'reportes', appeal: 'apelaciones', pet_request: 'solicitudes de mascota' },
}

export function fill(text: string, params: Record<string, string | number>): string {
  return text.replace(/\{(\w+)\}/g, (_, k: string) => String(params[k] ?? `{${k}}`))
}
