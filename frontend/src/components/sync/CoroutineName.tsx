interface CoroutineNameProps {
  id: string
  label: string | null
  /** Selects this coroutine (S-2); without it the name is plain text. */
  onSelect?: (id: string) => void
  /** It is the page's selection. */
  selected?: boolean
}

/** A coroutine's name in the Locks and permits section: a button that selects it, marked when selected. */
export function CoroutineName({ id, label, onSelect, selected = false }: CoroutineNameProps) {
  const name = label || id
  const marked = selected ? ' rounded-small bg-primary/10 px-1 font-semibold' : ''
  if (!onSelect) return <span className={`font-mono${marked}`}>{name}</span>
  return (
    <button
      type="button"
      aria-current={selected ? 'true' : undefined}
      onClick={() => onSelect(id)}
      className={`font-mono text-primary hover:underline${marked}`}
    >
      {name}
    </button>
  )
}
