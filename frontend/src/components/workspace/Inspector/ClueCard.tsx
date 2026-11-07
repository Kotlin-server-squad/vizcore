import { InspectorCard } from './InspectorCard'

/**
 * The event-loop clue (triage "Event-loop clue", T-3): the thread of a possibly blocked coroutine's current run has a
 * known event-loop name. A clue with conditional advice, never a verdict (H-1).
 */
export function ClueCard({ clue }: { clue: string }) {
  return (
    <div data-look-part="clue-card">
      <InspectorCard title="Clue" testId="clue-card">
        <p className="text-sm">
          Its current run is on a thread named like an event loop (<span className="font-mono">{clue}…</span>).
        </p>
        <p className="mt-2 text-sm text-default-600">
          If the code it is running makes a blocking call, it holds that thread, and everything else scheduled on the
          event loop waits. If it does block, move that call to <code className="font-mono">withContext(Dispatchers.IO) {'{ … }'}</code>.
        </p>
      </InspectorCard>
    </div>
  )
}
