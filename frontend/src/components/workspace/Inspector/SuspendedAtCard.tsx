import { InspectorCard } from './InspectorCard'
import { CoroutineSourceStack } from '../../CoroutineSourceStack'

/**
 * Where this coroutine is stopped, in the user's own code.
 *
 * The body is the shipped `CoroutineSourceStack` verbatim — compact
 * `file:line` chips expanding to the full creation + suspension stack, with
 * user frames as jump targets and library frames dimmed. That rendering is
 * LOCKED v1; this card only gives it a heading and a place in the D-7 order.
 */
export function SuspendedAtCard({
  sessionId,
  coroutineId,
}: {
  sessionId: string
  coroutineId: string
}) {
  return (
    <InspectorCard title="Suspended at" testId="suspended-at-card">
      <CoroutineSourceStack sessionId={sessionId} coroutineId={coroutineId} />
    </InspectorCard>
  )
}
