import { describeApiError } from '@/lib/api-errors'
import type { DemoStartError } from '@/hooks/use-start-demo'

/** "Could not start …", for a demo whose session could not be created. */
export function DemoStartAlert({ startError }: { startError: DemoStartError | null }) {
  if (!startError) return null
  return (
    <div role="alert" className="rounded-medium border border-danger-200 bg-danger-50 p-3 text-sm text-danger">
      Could not start “{startError.scenario}”:{' '}
      {describeApiError(startError.error, 'The scenario').message}
    </div>
  )
}
