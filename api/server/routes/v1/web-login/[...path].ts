import { defineEventHandler } from 'h3'
import { ApiError } from '../../../lib/errors'

/**
 * Die Anmeldung per Launcher-Code gibt es nicht mehr (§15, §24.1): Die Website meldet sich nur noch mit Microsoft an.
 * Ältere Launcher rufen `POST /v1/web-login/approve` noch auf und bekommen `410 web_login_removed`.
 */
export default defineEventHandler(() => {
  throw new ApiError(410, 'web_login_removed', 'Website sign-in with a launcher code was removed – sign in with Microsoft on the website')
})
