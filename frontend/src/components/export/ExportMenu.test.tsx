import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HeroUIProvider } from '@heroui/react'

// --- Mock the export libs + toast + findSvgRoot ---------------------------
const mockExportToPng = vi.fn().mockResolvedValue(undefined)
const mockExportToSvg = vi.fn()
const mockExportEventsToJson = vi.fn()
const mockFindSvgRoot = vi.fn()
const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()

vi.mock('@/lib/export-png', () => ({
  exportToPng: (...args: unknown[]) => mockExportToPng(...args),
}))
vi.mock('@/lib/export-svg', () => ({
  exportToSvg: (...args: unknown[]) => mockExportToSvg(...args),
  findSvgRoot: (...args: unknown[]) => mockFindSvgRoot(...args),
}))
vi.mock('@/lib/export-json', () => ({
  exportEventsToJson: (...args: unknown[]) => mockExportEventsToJson(...args),
}))
vi.mock('@/lib/toast', () => ({
  toastSuccess: (...args: unknown[]) => mockToastSuccess(...args),
  toastError: (...args: unknown[]) => mockToastError(...args),
}))

import { ExportMenu, type ExportMenuProps } from './ExportMenu'

function renderMenu(overrides: Partial<ExportMenuProps> = {}) {
  const panelEl = document.createElement('div')
  const props: ExportMenuProps = {
    getPanelEl: () => panelEl,
    sessionId: 'abc123',
    sessionName: 'session-abc123',
    events: [{ kind: 'CoroutineCreated' }],
    panel: 'tree',
    ...overrides,
  }
  return {
    panelEl,
    ...render(
      <HeroUIProvider>
        <ExportMenu {...props} />
      </HeroUIProvider>
    ),
  }
}

async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByTestId('export-menu-trigger'))
  await waitFor(() =>
    expect(screen.getByTestId('export-png')).toBeInTheDocument()
  )
}

describe('ExportMenu', () => {
  const originalMediaRecorder = globalThis.MediaRecorder

  beforeEach(() => {
    vi.clearAllMocks()
    mockFindSvgRoot.mockReturnValue(null)
    // Provide a supported MediaRecorder by default.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).MediaRecorder = Object.assign(vi.fn(), {
      isTypeSupported: vi.fn().mockReturnValue(true),
    })
  })

  afterEach(() => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(globalThis as any).MediaRecorder = originalMediaRecorder
  })

  it('always renders PNG, JSON, and Record items', async () => {
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    expect(screen.getByTestId('export-png')).toBeInTheDocument()
    expect(screen.getByTestId('export-json')).toBeInTheDocument()
    expect(screen.getByTestId('export-record')).toBeInTheDocument()
  })

  it('hides the SVG item when the panel has no <svg> root (D-21)', async () => {
    const user = userEvent.setup()
    mockFindSvgRoot.mockReturnValue(null)
    renderMenu()
    await openMenu(user)

    expect(screen.queryByTestId('export-svg')).not.toBeInTheDocument()
  })

  it('shows the SVG item only when findSvgRoot returns a node (D-21)', async () => {
    const user = userEvent.setup()
    mockFindSvgRoot.mockReturnValue(
      document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    )
    renderMenu()
    await openMenu(user)

    expect(screen.getByTestId('export-svg')).toBeInTheDocument()
  })

  it('disables the Record item with the unsupported tooltip when MediaRecorder is undefined (D-25)', async () => {
    const user = userEvent.setup()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    delete (globalThis as any).MediaRecorder
    renderMenu()
    await openMenu(user)

    expect(
      screen.getByLabelText('Video recording is not supported in this browser')
    ).toBeInTheDocument()
  })

  it('enables the Record item when MediaRecorder is supported', async () => {
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    expect(
      screen.queryByLabelText(
        'Video recording is not supported in this browser'
      )
    ).not.toBeInTheDocument()
  })

  it('clicking PNG calls exportToPng then toastSuccess', async () => {
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    await user.click(screen.getByTestId('export-png'))

    await waitFor(() => expect(mockExportToPng).toHaveBeenCalledOnce())
    await waitFor(() =>
      expect(mockToastSuccess).toHaveBeenCalledWith(
        expect.stringMatching(/^Exported abc123-tree-\d{8}-\d{4}\.png$/)
      )
    )
  })

  it('a PNG export rejection calls toastError with the failure copy', async () => {
    const user = userEvent.setup()
    mockExportToPng.mockRejectedValueOnce(new Error('tainted canvas'))
    renderMenu()
    await openMenu(user)

    await user.click(screen.getByTestId('export-png'))

    await waitFor(() =>
      expect(mockToastError).toHaveBeenCalledWith(
        expect.stringContaining('Export failed — tainted canvas')
      )
    )
    expect(mockToastSuccess).not.toHaveBeenCalled()
  })

  it('clicking JSON calls exportEventsToJson then toastSuccess', async () => {
    const user = userEvent.setup()
    renderMenu()
    await openMenu(user)

    await user.click(screen.getByTestId('export-json'))

    await waitFor(() => expect(mockExportEventsToJson).toHaveBeenCalledOnce())
    await waitFor(() =>
      expect(mockToastSuccess).toHaveBeenCalledWith(
        expect.stringMatching(/^Exported abc123-tree-\d{8}-\d{4}\.json$/)
      )
    )
  })

  it('invokes the onRecord callback when the Record item is pressed (02-08 wiring point)', async () => {
    const user = userEvent.setup()
    const onRecord = vi.fn()
    renderMenu({ onRecord })
    await openMenu(user)

    await user.click(screen.getByTestId('export-record'))
    expect(onRecord).toHaveBeenCalledOnce()
  })
})
