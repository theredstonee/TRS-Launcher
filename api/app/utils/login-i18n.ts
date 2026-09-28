// Texte der Anmeldeseite mit zwei Wegen (§29): „TRS Launcher“ (Code-Abgleich) und „Microsoft“ – Englisch, Deutsch,
// Spanisch. Die bisherigen Microsoft-Texte (Fehler, Datenschutz-Anker) stehen weiter in team-i18n.ts (`t.login`).

import type { Lang } from './messages'

const en = {
  title: 'Sign in',
  lead: 'Choose how you want to sign in. We only learn your Minecraft name and UUID – no e-mail, no password.',
  choose: 'Sign-in method',
  launcher: { title: 'TRS Launcher', sub: 'With your launcher account' },
  microsoft: { title: 'Microsoft', sub: 'With the account that owns Minecraft' },
  starting: 'Starting…',
  wait: {
    kicker: 'Sign in with the TRS Launcher',
    title: 'Confirm in the TRS Launcher',
    lead: 'Your launcher now shows a sign-in request. Check that it shows this code, then click “Confirm”.',
    code: 'Your code',
    copy: 'Copy code',
    copied: 'Copied',
    waiting: 'Waiting for confirmation…',
    expires: 'Expires in {time}',
    reopen: 'Open launcher again',
    manualTitle: 'Launcher on another computer?',
    manual: 'Open the TRS Launcher there, choose “Sign in on the website” (Settings → Privacy, or Ctrl+K) and type in this code.',
    noLauncher: 'No TRS Launcher yet?',
    download: 'Download it for free',
    back: 'Other sign-in method',
    signedIn: 'Confirmed – signing you in…',
  },
  expired: { title: 'The request expired', text: 'Nothing was confirmed within two minutes. Start again to get a new code.' },
  denied: { title: 'Sign-in declined', text: 'The request was declined in the launcher.', banned: 'This Minecraft account is banned from the TRS online features.' },
  restart: 'Start again',
  errors: {
    rate_limited: 'Too many attempts. Please wait a few minutes.',
    busy: 'Too many sign-ins right now. Please try again in a minute.',
    failed: 'The sign-in could not be started. Please try again.',
  } as Record<string, string>,
}

export type LoginTexts = typeof en

const de: LoginTexts = {
  title: 'Anmelden',
  lead: 'Wähle, wie du dich anmelden möchtest. Wir erfahren nur deinen Minecraft-Namen und deine UUID – keine E-Mail, kein Passwort.',
  choose: 'Anmeldemethode',
  launcher: { title: 'TRS Launcher', sub: 'Mit deinem Launcher-Konto' },
  microsoft: { title: 'Microsoft', sub: 'Mit dem Konto, dem Minecraft gehört' },
  starting: 'Wird gestartet …',
  wait: {
    kicker: 'Anmelden mit dem TRS Launcher',
    title: 'Bestätige im TRS Launcher',
    lead: 'Dein Launcher zeigt jetzt eine Anmelde-Anfrage. Prüfe, ob dort dieser Code steht, und klicke auf „Bestätigen“.',
    code: 'Dein Code',
    copy: 'Code kopieren',
    copied: 'Kopiert',
    waiting: 'Warte auf Bestätigung …',
    expires: 'Läuft ab in {time}',
    reopen: 'Launcher erneut öffnen',
    manualTitle: 'Launcher auf einem anderen PC?',
    manual: 'Öffne dort den TRS Launcher, wähle „Auf der Website anmelden“ (Einstellungen → Datenschutz oder Strg+K) und gib diesen Code ein.',
    noLauncher: 'Noch keinen TRS Launcher?',
    download: 'Kostenlos herunterladen',
    back: 'Andere Anmeldemethode',
    signedIn: 'Bestätigt – du wirst angemeldet …',
  },
  expired: { title: 'Die Anfrage ist abgelaufen', text: 'In zwei Minuten wurde nichts bestätigt. Starte neu, um einen neuen Code zu bekommen.' },
  denied: { title: 'Anmeldung abgelehnt', text: 'Die Anfrage wurde im Launcher abgelehnt.', banned: 'Dieses Minecraft-Konto ist für die Online-Funktionen von TRS gesperrt.' },
  restart: 'Neu starten',
  errors: {
    rate_limited: 'Zu viele Versuche. Bitte warte ein paar Minuten.',
    busy: 'Gerade melden sich zu viele an. Bitte versuche es in einer Minute erneut.',
    failed: 'Die Anmeldung konnte nicht gestartet werden. Bitte versuche es erneut.',
  },
}

const es: LoginTexts = {
  title: 'Iniciar sesión',
  lead: 'Elige cómo quieres iniciar sesión. Solo conocemos tu nombre de Minecraft y tu UUID: ni correo ni contraseña.',
  choose: 'Método de inicio de sesión',
  launcher: { title: 'TRS Launcher', sub: 'Con tu cuenta del launcher' },
  microsoft: { title: 'Microsoft', sub: 'Con la cuenta que tiene Minecraft' },
  starting: 'Iniciando…',
  wait: {
    kicker: 'Iniciar sesión con el TRS Launcher',
    title: 'Confirma en el TRS Launcher',
    lead: 'Tu launcher muestra ahora una solicitud de inicio de sesión. Comprueba que muestra este código y haz clic en «Confirmar».',
    code: 'Tu código',
    copy: 'Copiar código',
    copied: 'Copiado',
    waiting: 'Esperando la confirmación…',
    expires: 'Caduca en {time}',
    reopen: 'Abrir el launcher de nuevo',
    manualTitle: '¿El launcher está en otro PC?',
    manual: 'Abre allí el TRS Launcher, elige «Iniciar sesión en la web» (Ajustes → Privacidad o Ctrl+K) e introduce este código.',
    noLauncher: '¿Aún no tienes el TRS Launcher?',
    download: 'Descárgalo gratis',
    back: 'Otro método de inicio de sesión',
    signedIn: 'Confirmado: iniciando sesión…',
  },
  expired: { title: 'La solicitud ha caducado', text: 'No se confirmó nada en dos minutos. Vuelve a empezar para obtener un código nuevo.' },
  denied: { title: 'Inicio de sesión rechazado', text: 'La solicitud se rechazó en el launcher.', banned: 'Esta cuenta de Minecraft está bloqueada para las funciones en línea de TRS.' },
  restart: 'Volver a empezar',
  errors: {
    rate_limited: 'Demasiados intentos. Espera unos minutos.',
    busy: 'Hay demasiados inicios de sesión ahora mismo. Inténtalo de nuevo en un minuto.',
    failed: 'No se pudo iniciar el inicio de sesión. Inténtalo de nuevo.',
  },
}

export const loginTexts: Record<Lang, LoginTexts> = { en, de, es }
