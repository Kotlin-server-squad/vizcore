import type { CoroutineNode, SessionComparison } from '@/types/api'

export interface SyncedTreePairProps {
  sessionAId: string
  sessionBId: string
  coroutinesA: CoroutineNode[]
  coroutinesB: CoroutineNode[]
  comparison: SessionComparison
}

// Implemented in Task 2.
export function SyncedTreePair(_props: SyncedTreePairProps) {
  return null
}
