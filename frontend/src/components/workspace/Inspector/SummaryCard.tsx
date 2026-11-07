import { apiClient } from '@/lib/api-client'
import { hierarchyKey, readAt } from '@/lib/moment-read'
import { useMomentQuery } from '@/hooks/use-moment-query'
import { useMomentView } from '@/hooks/use-moment'
import type { SuspensionPoint } from '@/types/api'
import { InspectorCard, InspectorRow, NotReported } from './InspectorCard'

function frame(point: SuspensionPoint | null | undefined) {
  if (!point?.fileName) return <NotReported />
  return point.lineNumber != null ? `${point.fileName}:${point.lineNumber}` : point.fileName
}

/**
 * What the server's durable summary keeps of a coroutine whose early events were trimmed (Part 8, M-10; triage
 * "Modes"): where it was created, where it last suspended, its last thread and dispatcher, and its failure. Read from
 * `…/hierarchy` with the moment's binding, so it describes the same moment as the rest of the Inspector.
 */
export function SummaryCard({ coroutineId }: { coroutineId: string }) {
  const view = useMomentView()
  const read = view?.read
  const query = useMomentQuery({
    enabled: !!view,
    identity: `summary:${coroutineId}`,
    keyAt: seq => (read ? hierarchyKey(readAt(read, seq)) : ['no-moment']),
    fetchAt: seq => apiClient.getHierarchyAt(readAt(read!, seq)),
    cursorSeq: view?.cursorSeq ?? 0,
    changesBetween: (a, b) => (view ? view.changesBetween(coroutineId, a, b) : true),
    settleMs: view?.settleMs ?? 0,
    hold: view?.hold ?? false,
  })
  const node = query.data?.find(n => n.id === coroutineId)
  const lastThread = node?.currentThreadName ?? node?.summary?.lastThread?.threadName ?? null
  const dispatcher = node?.dispatcherName ?? node?.summary?.dispatcher ?? null

  return (
    <InspectorCard title="Summary" testId="summary-card">
      <p className="mb-2 text-xs text-default-500">
        From the durable summary: its early events were trimmed.
      </p>
      {query.status !== 'ready' ? (
        <p className="text-sm text-default-400">
          {query.status === 'error' ? 'The summary could not be read.' : 'Reading the summary…'}
        </p>
      ) : !node ? (
        <p className="text-sm text-default-400">Not in the summary at this moment.</p>
      ) : (
        <>
          <InspectorRow label="Created at" value={frame(node.creationPoint)} />
          <InspectorRow label="Last suspended at" value={frame(node.lastSuspensionPoint)} />
          <InspectorRow label="Last thread" value={lastThread ?? <NotReported />} />
          <InspectorRow label="Dispatcher" value={dispatcher ?? <NotReported />} />
          {node.exceptionType && (
            <InspectorRow
              label="Failed with"
              value={node.exceptionMessage ? `${node.exceptionType}: ${node.exceptionMessage}` : node.exceptionType}
            />
          )}
        </>
      )}
    </InspectorCard>
  )
}
