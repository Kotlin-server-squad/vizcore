import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { StateCounts } from '@/lib/state-counts'
import { StateBar } from './StateBar'

const counts: StateCounts = {
  active: 47,
  waiting: 2,
  suspended: 31,
  completed: 1200,
  cancelled: 0,
  failed: 2,
  total: 1282,
  leaks: 0,
}

const renderBar = (over: Partial<Parameters<typeof StateBar>[0]> = {}) => {
  const onFilterChange = vi.fn()
  render(<StateBar counts={counts} filter="all" onFilterChange={onFilterChange} {...over} />)
  return { onFilterChange }
}

describe('StateBar leak chip', () => {
  it('stays hidden when nothing is flagged', () => {
    renderBar()
    expect(screen.queryByRole('button', { name: /potential leaks/i })).toBeNull()
  })

  it('appears with its count once something is flagged', () => {
    renderBar({ counts: { ...counts, leaks: 3 } })
    expect(screen.getByRole('button', { name: 'Long-lived 3' })).toBeInTheDocument()
  })

  it('selects the leak filter', async () => {
    const { onFilterChange } = renderBar({ counts: { ...counts, leaks: 3 } })
    await userEvent.click(screen.getByRole('button', { name: 'Long-lived 3' }))
    expect(onFilterChange).toHaveBeenCalledWith('leaks')
  })

  it('is amber, never danger red — a potential leak is not a failure', () => {
    renderBar({ counts: { ...counts, leaks: 3 }, filter: 'leaks' })
    const chip = screen.getByRole('button', { name: 'Long-lived 3' })
    expect(chip.style.color).toContain('--warning')
    expect(chip.style.color).not.toContain('--danger')
  })
})

describe('StateBar waiting chip', () => {
  it('shows Waiting for children as its own chip when any parent waits', () => {
    renderBar()
    expect(screen.getByRole('button', { name: 'Waiting for children 2' })).toBeInTheDocument()
  })

  it('selects the waiting filter', async () => {
    const { onFilterChange } = renderBar()
    await userEvent.click(screen.getByRole('button', { name: 'Waiting for children 2' }))
    expect(onFilterChange).toHaveBeenCalledWith('waiting')
  })

  it('keeps the selected chip at zero, so the filter it applies can still be cleared', async () => {
    // Parents stop waiting as their children finish: the bucket empties under
    // an active filter, and a hidden chip would leave nothing to click.
    const { onFilterChange } = renderBar({ counts: { ...counts, waiting: 0 }, filter: 'waiting' })
    const chip = screen.getByRole('button', { name: 'Waiting for children 0' })
    expect(chip).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(chip)
    expect(onFilterChange).toHaveBeenCalledWith('all')
  })
})

describe('StateBar', () => {
  it('drops its own box when it sits in a toolbar', () => {
    render(<StateBar counts={counts} filter="all" onFilterChange={() => {}} bare />)
    const bar = screen.getByRole('group', { name: 'Coroutine state' })
    expect(bar.className).not.toContain('border')
    expect(bar.className).not.toContain('bg-content1')
  })

  it('shows a chip per non-zero state with its count', () => {
    renderBar()
    expect(screen.getByRole('button', { name: /active 47/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /suspended 31/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /completed 1200/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /failed 2/i })).toBeInTheDocument()
  })

  it('hides zero-count states so a healthy session reads calm', () => {
    renderBar()
    expect(screen.queryByRole('button', { name: /cancelled/i })).toBeNull()
  })

  it('always shows Active so the bar never collapses to nothing', () => {
    renderBar({
      counts: {
        active: 0,
        waiting: 0,
        suspended: 0,
        completed: 0,
        cancelled: 0,
        failed: 0,
        total: 0,
        leaks: 0,
      },
    })
    expect(screen.getByRole('button', { name: /active 0/i })).toBeInTheDocument()
  })

  it('shows coroutines whose state is unknown at the replay cursor as their own chip, only when there are some', async () => {
    const counts: StateCounts = { active: 1, waiting: 0, suspended: 0, completed: 0, cancelled: 0, failed: 0, total: 3, leaks: 0, unknown: 2 }
    const onFilterChange = vi.fn()
    render(<StateBar counts={counts} filter="all" onFilterChange={onFilterChange} />)
    await userEvent.click(screen.getByRole('button', { name: /state unknown 2/i }))
    expect(onFilterChange).toHaveBeenCalledWith('unknown')
    cleanup()
    render(<StateBar counts={{ ...counts, unknown: 0 }} filter="all" onFilterChange={vi.fn()} />)
    expect(screen.queryByRole('button', { name: /state unknown/i })).toBeNull()
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

  it('writes the active chip in body text in light mode, in its state colour in dark', () => {
    renderBar({ filter: 'completed' })
    const chip = screen.getByRole('button', { name: /completed 1200/i })
    // A state colour on its own 16% tint is under 4.5:1 on white; the dot and border keep the colour.
    expect(chip.style.color).toBe('var(--state-chip-text, var(--success))')

    const tokens = readFileSync(
      resolve(dirname(fileURLToPath(import.meta.url)), '../styles/tokens.css'),
      'utf8',
    )
    const light = tokens.slice(tokens.indexOf('html.light {'))
    expect(light).toContain('--state-chip-text: var(--text);')
    expect(tokens.slice(0, tokens.indexOf('html.light {'))).not.toContain('--state-chip-text')
  })

  it('marks the active chip for assistive tech', () => {
    renderBar({ filter: 'suspended' })
    expect(screen.getByRole('button', { name: /suspended 31/i })).toHaveAttribute(
      'aria-pressed',
      'true'
    )
    expect(screen.getByRole('button', { name: /active 47/i })).toHaveAttribute(
      'aria-pressed',
      'false'
    )
  })
})

describe('StateBar — the checks action (M-3)', () => {
  const calm: StateCounts = {
    active: 3,
    waiting: 0,
    suspended: 0,
    completed: 0,
    cancelled: 0,
    failed: 0,
    total: 3,
    leaks: 0,
  }

  it('has no validation chip: checks are not health (Part 7, T-8)', () => {
    render(<StateBar counts={calm} filter="all" onFilterChange={vi.fn()} />)

    expect(screen.queryByRole('button', { name: /checks/i })).toBeNull()
  })
})
