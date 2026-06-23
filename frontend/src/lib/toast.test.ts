import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock @heroui/react's addToast before importing the module under test
const mockAddToast = vi.fn()

vi.mock('@heroui/react', () => ({
  addToast: mockAddToast,
}))

// Must import after vi.mock
const { toastSuccess, toastError } = await import('./toast')

describe('toast helpers', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('toastSuccess calls addToast with success color and the given title', () => {
    toastSuccess('Exported diagram.png')

    expect(mockAddToast).toHaveBeenCalledTimes(1)
    expect(mockAddToast).toHaveBeenCalledWith({
      title: 'Exported diagram.png',
      color: 'success',
    })
  })

  it('toastError calls addToast with danger color and the given title', () => {
    toastError('Export failed')

    expect(mockAddToast).toHaveBeenCalledTimes(1)
    expect(mockAddToast).toHaveBeenCalledWith({
      title: 'Export failed',
      color: 'danger',
    })
  })
})
