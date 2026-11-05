import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DockSeparator } from './DockSeparator'

function renderSeparator(props: Partial<Parameters<typeof DockSeparator>[0]> = {}) {
  const onResize = vi.fn()
  const onToggleFold = vi.fn()
  render(
    <DockSeparator controls="session-dock" height={300} maxHeight={500} folded={false} onResize={onResize} onToggleFold={onToggleFold} {...props} />,
  )
  return { separator: screen.getByRole('separator', { name: 'Resize the dock' }), onResize, onToggleFold }
}

describe('DockSeparator', () => {
  it('is a focusable horizontal separator that says how tall the dock is', () => {
    const { separator } = renderSeparator()
    expect(separator).toHaveAttribute('tabindex', '0')
    expect(separator).toHaveAttribute('aria-orientation', 'horizontal')
    expect(separator).toHaveAttribute('aria-valuenow', '300')
    expect(separator).toHaveAttribute('aria-valuemin', '0')
    expect(separator).toHaveAttribute('aria-controls', 'session-dock')
    expect(separator).toHaveAttribute('aria-valuemax', '500')
    expect(separator).toHaveAttribute('aria-valuetext', 'Dock 300 pixels tall')
  })

  it('reports a folded dock as collapsed', () => {
    const { separator } = renderSeparator({ folded: true })
    expect(separator).toHaveAttribute('aria-valuenow', '0')
    expect(separator).toHaveAttribute('aria-valuetext', 'Dock folded')
  })

  it('omits the maximum until the space is measured', () => {
    const { separator } = renderSeparator({ maxHeight: Number.POSITIVE_INFINITY })
    expect(separator).not.toHaveAttribute('aria-valuemax')
  })

  it('moves with the arrow and page keys; Home gives the dock its smallest size, End its largest (APG)', async () => {
    const { separator, onResize } = renderSeparator()
    separator.focus()
    await userEvent.keyboard('{ArrowUp}')
    expect(onResize).toHaveBeenLastCalledWith(324)
    await userEvent.keyboard('{ArrowDown}')
    expect(onResize).toHaveBeenLastCalledWith(276)
    await userEvent.keyboard('{PageUp}')
    expect(onResize).toHaveBeenLastCalledWith(396)
    await userEvent.keyboard('{PageDown}')
    expect(onResize).toHaveBeenLastCalledWith(204)
    await userEvent.keyboard('{Home}')
    expect(onResize).toHaveBeenLastCalledWith(120)
    await userEvent.keyboard('{End}')
    expect(onResize).toHaveBeenLastCalledWith(500)
  })

  it('folds and unfolds with Enter', async () => {
    const { separator, onToggleFold } = renderSeparator()
    separator.focus()
    await userEvent.keyboard('{Enter}')
    expect(onToggleFold).toHaveBeenCalledTimes(1)
  })

  it('follows the mouse: dragging up makes the dock taller', () => {
    const { separator, onResize } = renderSeparator()
    fireEvent.pointerDown(separator, { clientY: 500, pointerId: 1, button: 0 })
    fireEvent.pointerMove(window, { clientY: 450, pointerId: 1 })
    expect(onResize).toHaveBeenLastCalledWith(350)
    fireEvent.pointerUp(window, { clientY: 450, pointerId: 1 })
    fireEvent.pointerMove(window, { clientY: 300, pointerId: 1 })
    expect(onResize).toHaveBeenCalledTimes(1)
  })
})
