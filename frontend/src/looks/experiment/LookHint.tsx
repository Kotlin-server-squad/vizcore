import { Button } from '@heroui/react'
import { FiX } from 'react-icons/fi'

/** For a browser the experiment gave a look: which look, and where to switch. */
export function LookHint({
  label,
  count,
  onClose,
}: {
  label: string
  count: number
  onClose: () => void
}) {
  return (
    <div
      role="status"
      data-testid="look-hint"
      className="pointer-events-auto flex items-start gap-2 rounded-large border border-default-200 bg-content1 p-3 text-sm text-foreground shadow-medium"
    >
      <p className="flex-1">
        You are seeing <strong className="font-medium">{label}</strong>, one of {count} looks we are
        trying. Switch any time in ⋯ → Look.
      </p>
      <Button
        size="sm"
        variant="light"
        isIconOnly
        aria-label="Close the hint"
        onPress={onClose}
        className="-mr-1 -mt-1 h-6 min-w-6"
      >
        <FiX aria-hidden />
      </Button>
    </div>
  )
}
