import { useId, useState, type KeyboardEvent, type ReactNode } from 'react'

/** One round in the picker, already labelled by the caller (`roundOptionLabel`). */
export interface RoundOption {
  /** `round:<roundId>`: the picker's value for it. */
  value: string
  label: string
  /** Listed under "With a problem" (the server puts problem rounds first). */
  problem: boolean
}

/** A find match, as the results list shows it. */
export interface FindMatchView {
  id: string
  name: string
  /** Its state in words, from the snapshot; null when the snapshot does not hold it. */
  state: string | null
  /** The page holds it and can draw it; otherwise it reads "not drawn". */
  selectable: boolean
}

/** One round's matches (or the ungrouped ones), in the server's order. */
export interface FindGroupView {
  key: string
  /** "request-3 · at 12.0 s", or "Ungrouped". */
  title: string
  matches: FindMatchView[]
  /** What the list leaves out of this round, in words; null when it lists every match. */
  partial: string | null
}

export interface FindResultsView {
  status: 'pending' | 'ready' | 'error'
  /** "Found 3 in 1 round · as of 1 m 4 s", "Nothing matched in the captured coroutines · …". */
  summary: string
  groups: FindGroupView[]
  /** "N more rounds matched: refine the search"; null when every matching round was read. */
  moreRounds: string | null
  /** The sampling sentence (H-4) when nothing matched in an app's session. */
  note: string | null
  /** "N coroutines without a summary were not searched."; null when there are none. */
  unsearched: string | null
}

export interface RoundBarProps {
  /** Off when there is nothing to choose between (a one-round demo). */
  showPicker: boolean
  /** The picker's value: `all`, `ungrouped` or a round option's value. */
  value: string
  options: RoundOption[]
  /** Offer "All rounds". */
  allRounds: boolean
  /** The problem rounds' group, with the list's "as of" (H-2). */
  problemLabel: string
  /** Coroutines in no round; "Ungrouped (n)" is offered when there are any. */
  ungrouped: number
  /** Coroutines whose round is not known (no summary); "Round unknown (n)" is offered when there are any. */
  unknownRound: number
  /** "Show 50 more rounds…" can list more. */
  canLoadMore: boolean
  /** Rounds beyond the most the picker lists (200): reached through Find. */
  olderRounds: number
  onChange: (value: string) => void
  onLoadMore: () => void
  /** The picker got focus: the caller may read the list again. */
  onPickerFocus: () => void
  /** vizcore's own coroutines captured; the toggle shows only when there are any. */
  vizcoreCount: number
  includeVizcore: boolean
  onToggleVizcore: (next: boolean) => void
  /** The app reports no origins: vizcore's own coroutines cannot be told apart. */
  originUnknown: boolean
  find: {
    /** Null: no search shown. */
    results: FindResultsView | null
    onSubmit: (text: string) => void
    /** A match was chosen: its id and its group's key (a round id, or `ungrouped`). */
    onChoose: (id: string, groupKey: string) => void
    /** "Open this round" on a round whose matches are not all listed: its group's key. */
    onOpenRound: (groupKey: string) => void
    onClose: () => void
  }
  /** More controls after the toggles (Part 6b: Group by name). */
  children?: ReactNode
}

const LOAD_MORE = '__more__'

/**
 * The big-app controls of the main view (dock spec S-12, triage T-4): which round is shown, "find in all rounds",
 * and the "vizcore connection (n)" toggle. Presentational: the caller derives every list and count.
 */
export function RoundBar({
  showPicker,
  value,
  options,
  allRounds,
  problemLabel,
  ungrouped,
  unknownRound,
  canLoadMore,
  olderRounds,
  onChange,
  onLoadMore,
  onPickerFocus,
  vizcoreCount,
  includeVizcore,
  onToggleVizcore,
  originUnknown,
  find,
  children,
}: RoundBarProps) {
  const pickerId = useId()
  const problem = options.filter(o => o.problem)
  const rest = options.filter(o => !o.problem)
  return (
    <div
      data-look-part="round-bar"
      className="flex flex-wrap items-center gap-x-3 gap-y-1.5 border-b border-default-200 px-3 py-1.5 text-xs"
    >
      {showPicker && (
        <div className="flex items-center gap-1.5">
          <label htmlFor={pickerId} className="font-medium text-default-600">
            Round
          </label>
          <select
            id={pickerId}
            data-look-part="round-picker"
            value={value}
            onFocus={onPickerFocus}
            onChange={e => {
              if (e.target.value === LOAD_MORE) onLoadMore()
              else onChange(e.target.value)
            }}
            className="max-w-[22rem] truncate rounded-medium border border-default-200 bg-default-100 px-2 py-1 text-xs text-foreground"
          >
            {allRounds && <option value="all">All rounds</option>}
            {problem.length > 0 && (
              <optgroup label={problemLabel}>
                {problem.map(o => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </optgroup>
            )}
            {rest.length > 0 && (
              <optgroup label="Newest first">
                {rest.map(o => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </optgroup>
            )}
            {ungrouped > 0 && <option value="ungrouped">{`Ungrouped (${ungrouped.toLocaleString('en-US')})`}</option>}
            {unknownRound > 0 && <option value="unknown">{`Round unknown (${unknownRound.toLocaleString('en-US')})`}</option>}
            {canLoadMore && <option value={LOAD_MORE}>Show 50 more rounds…</option>}
            {!canLoadMore && olderRounds > 0 && (
              <option disabled value="__older__">
                {`${olderRounds.toLocaleString('en-US')} older rounds are not listed: Find reaches them by name or creation site`}
              </option>
            )}
          </select>
        </div>
      )}
      <FindInRounds {...find} />
      {vizcoreCount > 0 && (
        <label data-look-part="vizcore-toggle" className="flex cursor-pointer items-center gap-1.5 text-default-600">
          <input
            type="checkbox"
            checked={includeVizcore}
            onChange={e => onToggleVizcore(e.target.checked)}
            className="accent-primary"
          />
          {`vizcore connection (${vizcoreCount.toLocaleString('en-US')})`}
        </label>
      )}
      {children}
      {originUnknown && (
        <span data-look-part="origin-note" className="text-default-500">
          origin not available: vizcore&apos;s own coroutines may be included
        </span>
      )}
    </div>
  )
}

function FindInRounds({ results, onSubmit, onChoose, onOpenRound, onClose }: RoundBarProps['find']) {
  const [text, setText] = useState('')
  const fieldId = useId()
  const submit = () => {
    const trimmed = text.trim()
    if (trimmed) onSubmit(trimmed)
  }
  const closeOnEscape = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && results) {
      e.preventDefault()
      onClose()
    }
  }
  return (
    <div data-look-part="round-find" className="relative" onKeyDown={closeOnEscape}>
      <form
        role="search"
        className="flex items-center gap-1"
        onSubmit={e => {
          e.preventDefault()
          submit()
        }}
      >
        <label htmlFor={fieldId} className="sr-only">
          Find in all rounds
        </label>
        <input
          id={fieldId}
          type="search"
          value={text}
          onChange={e => setText(e.target.value)}
          placeholder="Find in all rounds…"
          maxLength={200}
          className="w-44 rounded-medium border border-default-200 bg-default-100 px-2 py-1 text-xs text-foreground placeholder:text-default-400"
        />
        <button
          type="submit"
          className="rounded-medium border border-default-200 px-2 py-1 text-xs text-default-700 hover:bg-default-100"
        >
          Find
        </button>
      </form>
      {results && (
        <div
          role="region"
          aria-label="Find results"
          data-look-part="round-find-results"
          className="absolute left-0 top-full z-30 mt-1 max-h-[50vh] w-[26rem] max-w-[90vw] overflow-auto rounded-medium border border-default-200 bg-content1 p-2 shadow-lg"
        >
          <div className="mb-1 flex items-center gap-2">
            <p className="flex-1 text-default-600" aria-live="polite">
              {results.status === 'pending'
                ? 'Searching…'
                : results.status === 'error'
                  ? 'The search failed. Try again.'
                  : results.summary}
            </p>
            <button type="button" onClick={onClose} className="text-primary hover:underline">
              Close
            </button>
          </div>
          {results.status === 'ready' &&
            results.groups.map(g => (
              <div key={g.key} role="group" aria-label={g.title} className="mt-1">
                <p className="px-2 font-medium text-default-600">{g.title}</p>
                <ul className="space-y-0.5">
                  {g.matches.map(m => (
                    <li key={m.id}>
                      <button
                        type="button"
                        data-look-part="round-find-match"
                        disabled={!m.selectable}
                        onClick={() => onChoose(m.id, g.key)}
                        className="flex w-full flex-col items-start rounded-small px-2 py-1 text-left hover:bg-default-100 disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:bg-transparent"
                      >
                        <span className="font-mono text-foreground [overflow-wrap:anywhere]">{m.name}</span>{' '}
                        <span className="text-default-500">
                          {m.selectable ? (m.state ?? 'state unknown') : "not drawn: the page's snapshot does not hold it"}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
                {g.partial && (
                  <p className="px-2 text-default-500">
                    {g.partial}{' '}
                    {g.key !== 'ungrouped' && (
                      <button type="button" onClick={() => onOpenRound(g.key)} className="text-primary hover:underline">
                        Open this round
                      </button>
                    )}
                  </p>
                )}
              </div>
            ))}
          {results.status === 'ready' && results.moreRounds && (
            <p className="mt-1 text-default-500">{results.moreRounds}</p>
          )}
          {results.status === 'ready' && results.note && <p className="mt-1 text-default-500">{results.note}</p>}
          {results.status === 'ready' && results.unsearched && (
            <p className="mt-1 text-default-500">{results.unsearched}</p>
          )}
        </div>
      )}
    </div>
  )
}
