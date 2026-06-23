import { addToast } from '@heroui/react'

/**
 * Thin wrappers over HeroUI's `addToast` exposing the ADR-018 copy contract.
 *
 * These are the single entry points that export (plan 02-05) and recording
 * (plan 02-08) use so the success/error copy and color mapping stay consistent.
 * `ToastProvider` must be mounted at the app root (main.tsx) before any of
 * these fire — see issue #5086 ordering footgun.
 */

/** Show a success toast (HeroUI `success` color). */
export function toastSuccess(title: string): void {
  addToast({ title, color: 'success' })
}

/** Show an error toast (HeroUI `danger` color). */
export function toastError(title: string): void {
  addToast({ title, color: 'danger' })
}
