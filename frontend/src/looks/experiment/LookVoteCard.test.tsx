import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { LookVoteCard } from './LookVoteCard'

const props = { look: 'b' as const, label: 'B · Calm', surveyUrl: null, onSubmit: vi.fn(async () => true), onClose: vi.fn() }

describe('LookVoteCard', () => {
  it('starts small: the comment box opens once a rating is picked', () => {
    render(<LookVoteCard {...props} />)
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
    fireEvent.click(screen.getByRole('radio', { name: '3 of 5' }))
    expect(screen.getByRole('textbox')).toHaveAttribute('maxLength', '500')
  })

  it('starts from this browser’s earlier rating', () => {
    render(<LookVoteCard {...props} previous={4} />)
    expect(screen.getByRole('radio', { name: '4 of 5' })).toHaveAttribute('aria-checked', 'true')
  })

  it('says so when the rating could not be saved', async () => {
    render(<LookVoteCard {...props} onSubmit={vi.fn(async () => false)} />)
    fireEvent.click(screen.getByRole('radio', { name: '3 of 5' }))
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be saved')
  })

  it('links the survey with the look when there is one, and not otherwise', () => {
    const { rerender } = render(<LookVoteCard {...props} />)
    expect(screen.queryByRole('link', { name: 'Tell us more' })).toBeNull()
    rerender(<LookVoteCard {...props} surveyUrl="https://x.org/s?a=1" />)
    const link = screen.getByRole('link', { name: 'Tell us more' })
    expect(link).toHaveAttribute('href', 'https://x.org/s?a=1&look=b')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })
})
