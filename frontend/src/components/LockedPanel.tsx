import { FiArrowUpRight } from 'react-icons/fi'

/**
 * What a panel says when it is empty *because of the session's fidelity rung*
 * (D-5) — not because of a filter, and not because anything went wrong.
 *
 * Today the app hides these panels entirely: attaching to a real app silently
 * shrinks the tab bar, so the product's whole growth path is invisible behind a
 * conditional render. This component is the opposite move — it names what you
 * would see and the one change that gets you there.
 *
 * The tone is deliberate. Nothing here is an error, so nothing here is danger-
 * coloured or apologetic.
 */
export function LockedPanel({
  title,
  whatYouWouldSee,
  unlockWith,
}: {
  /** The capability that is missing — named as itself, not as "locked". */
  title: string
  whatYouWouldSee: string
  /** The single concrete change that unlocks it. */
  unlockWith: string
}) {
  return (
    <div className="flex flex-col gap-3 rounded-medium border border-dashed border-default-300 bg-content1 p-6">
      <h3 className="text-sm font-semibold text-default-700">{title}</h3>
      <p className="max-w-prose text-sm text-default-500">{whatYouWouldSee}</p>
      <p className="flex items-start gap-2 text-sm text-primary">
        <FiArrowUpRight className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
        <span>{unlockWith}</span>
      </p>
    </div>
  )
}
