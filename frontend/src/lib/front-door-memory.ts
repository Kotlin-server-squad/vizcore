/**
 * Whether this browser's last look at the sessions list had an app in it.
 * The front door lays itself out by this until the list is in, so a returning
 * visitor's page does not jump when it loads (routes/index.tsx).
 */
export const FRONT_DOOR_APPS_KEY = 'vizcore.frontDoor.hadApps'

export function rememberedHadApps(): boolean {
  try {
    return localStorage.getItem(FRONT_DOOR_APPS_KEY) === '1'
  } catch {
    return false
  }
}

export function rememberHadApps(hadApps: boolean): void {
  try {
    if (hadApps) localStorage.setItem(FRONT_DOOR_APPS_KEY, '1')
    else localStorage.removeItem(FRONT_DOOR_APPS_KEY)
  } catch {
    // Storage is off (private mode): the page lays itself out once the list is in.
  }
}
