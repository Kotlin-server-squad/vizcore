import { namesCoroutine } from '@/lib/selection'

/** How Locks and permits selects a coroutine by its name (S-2): only one the page can select. */
export interface CoroutinePicker {
  select: (id: string) => void
  /** False for a coroutine the session snapshot does not hold: selecting it would be cleared at once. */
  canSelect: (id: string) => boolean
}

interface CoroutineNameProps {
  id: string
  label: string | null
  /** Without it (replay, the shared view) the name is plain text. */
  picker?: CoroutinePicker
  /** It is the page's selection. */
  selected?: boolean
}

/**
 * A coroutine's name in the Locks and permits section: a button that selects it when the page can, marked
 * when selected. A wrapper used outside any coroutine records "unknown…": that names nobody, and says so.
 */
export function CoroutineName({ id, label, picker, selected = false }: CoroutineNameProps) {
  const name = label || (namesCoroutine(id) ? id : 'outside any coroutine')
  const marked = selected ? ' rounded-small bg-primary/10 px-1 font-semibold' : ''
  if (!picker || !namesCoroutine(id) || !picker.canSelect(id)) {
    return <span className={`font-mono${marked}`}>{name}</span>
  }
  return (
    <button
      type="button"
      aria-current={selected ? 'true' : undefined}
      onClick={() => picker.select(id)}
      className={`font-mono text-primary hover:underline${marked}`}
    >
      {name}
    </button>
  )
}
