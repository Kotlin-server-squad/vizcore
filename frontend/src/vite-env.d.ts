/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Optional override for the backend URL shown in the Connect wizard snippet. */
  readonly VITE_VIZCORE_BACKEND_URL?: string
}

/** The dev proxy's backend target, injected by vite.config.ts (undefined in tests). */
declare const __VIZCORE_DEV_BACKEND_URL__: string | undefined
