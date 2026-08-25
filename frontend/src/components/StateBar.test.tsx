import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { StateCounts } from '@/lib/state-counts'
import { StateBar } from './StateBar'

const counts: StateCounts = {
  running: 47,
  suspended: 31,
  completed: 1200,
  cancelled: 0,
  failed: 2,
  total: 1280,
}

const renderBar = (over: Partial<Parameters<typeof StateBar>[0]> = {}) => {
  const onFilterChange = vi.fn()
  render(<StateBar counts={counts} filter="all" onFilterChange={onFilterChange} {...over} />)
  return { onFilterChange }
}

describe('StateBar', () => {
  it('shows a chip per non-zero state with its count', () => {
    renderBar()
    expect(screen.getByRole('button', { name: /running 47/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /suspended 31/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /completed 1200/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /failed 2/i })).toBeInTheDocument()
  })

  it('hides zero-count states so a healthy session reads calm', () => {
    renderBar()
    expect(screen.queryByRole('button', { name: /cancelled/i })).toBeNull()
  })

  it('always shows Running so the bar never collapses to nothing', () => {
    renderBar({
      counts: { running: 0, suspended: 0, completed: 0, cancelled: 0, failed: 0, total: 0 },
    })
    expect(screen.getByRole('button', { name: /running 0/i })).toBeInTheDocument()
  })

  it('selects a state when its chip is clicked', async () => {
    const { onFilterChange } = renderBar()
    await userEvent.click(screen.getByRole('button', { name: /suspended 31/i }))
    expect(onFilterChange).toHaveBeenCalledWith('suspended')
  })

  it('clears the filter when the active chip is clicked again', async () => {
    const { onFilterChange } = renderBar({ filter: 'suspended' })
    await userEvent.click(screen.getByRole('button', { name: /suspended 31/i }))
    expect(onFilterChange).toHaveBeenCalledWith('all')
  })

  it('marks the active chip for assistive tech', () => {
    renderBar({ filter: 'suspended' })
    expect(screen.getByRole('button', { name: /suspended 31/i })).toHaveAttribute(
      'aria-pressed',
      'true'
    )
    expect(screen.getByRole('button', { name: /running 47/i })).toHaveAttribute(
      'aria-pressed',
      'false'
    )
  })
})
