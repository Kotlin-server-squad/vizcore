import { InspectorCard, InspectorRow, NotReported } from './InspectorCard'
import type { CoroutineNode } from '@/types/api'

/**
 * Who this coroutine is, structurally.
 *
 * The one card that needs no fetch — every field is already on the
 * `CoroutineNode` the canvas rendered — which is why it is the only card the
 * read-only shared view can show.
 */
export function IdentityCard({ coroutine }: { coroutine: CoroutineNode }) {
  return (
    <InspectorCard title="Identity" testId="identity-card">
      <InspectorRow label="Label" value={coroutine.label ?? <NotReported />} />
      <InspectorRow label="Coroutine" value={coroutine.id} />
      <InspectorRow label="Job" value={coroutine.jobId} />
      <InspectorRow label="Scope" value={coroutine.scopeId} />
      <InspectorRow label="Parent" value={coroutine.parentId ?? 'root'} />
      <InspectorRow label="State" value={coroutine.state} />
    </InspectorCard>
  )
}
