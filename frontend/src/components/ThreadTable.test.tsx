import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThreadTable } from './ThreadTable'
import type { ThreadActivity } from '@/types/api'

const S = 1_000_000_000

function activityWith(names: number): ThreadActivity {
  const out: ThreadActivity = {}
  for (let i = 0; i < names; i++) {
    out[String(i)] = [
      { threadId: i, threadName: `worker-${i}`, coroutineId: `c-${i}`, timestamp: (i + 1) * S, eventType: 'ASSIGNED', dispatcherName: 'Default' },
    ]
  }
  return out
}

describe('ThreadTable', () => {
  it('shows one row per thread name with T-5\'s columns', () => {
    render(<ThreadTable threadActivity={activityWith(2)} captureStartNanos={0} />)
    const table = screen.getByRole('table', { name: 'Threads by name' })
    expect(within(table).getAllByRole('columnheader').map(h => h.textContent)).toEqual([
      'Thread', 'JVM threads', 'Coroutines seen', 'Assignments observed', 'Last assignment',
    ])
    expect(within(table).getByRole('row', { name: /worker-1/ })).toHaveTextContent('2.0 s in')
  })

  it('shows ten names, then the rest behind "Show all"', async () => {
    render(<ThreadTable threadActivity={activityWith(12)} captureStartNanos={0} />)
    expect(screen.getAllByRole('row')).toHaveLength(11) // header + 10
    await userEvent.click(screen.getByRole('button', { name: 'Show all (12)' }))
    expect(screen.getAllByRole('row')).toHaveLength(13)
  })

  it('measures from the first thread event when the capture start is unknown', () => {
    render(<ThreadTable threadActivity={activityWith(2)} captureStartNanos={null} />)
    expect(screen.getByRole('row', { name: /worker-1/ })).toHaveTextContent('1.0 s in')
  })

  it('says so when no thread activity was captured', () => {
    render(<ThreadTable threadActivity={{}} captureStartNanos={null} />)
    expect(screen.getByText('No thread activity captured yet.')).toBeInTheDocument()
  })
})
