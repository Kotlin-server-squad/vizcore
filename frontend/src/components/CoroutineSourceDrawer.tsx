import { Drawer, DrawerContent, DrawerHeader, DrawerBody, Button } from '@heroui/react'
import { FiX } from 'react-icons/fi'
import { CoroutineSourceStack } from './CoroutineSourceStack'

interface CoroutineSourceDrawerProps {
  sessionId: string
  coroutineId: string | null
  label?: string | null
  isOpen: boolean
  onClose: () => void
}

/**
 * HeroUI `Drawer` shell hosting the per-coroutine source detail (D-01/D-02/D-05/D-07).
 *
 * Slides in from the right (`placement="right"`, `size="lg"`) over a dimmed
 * backdrop with the live canvas still visible behind. Mounts the headerless
 * `CoroutineSourceStack` body, which lazily fetches the timeline only while a
 * `coroutineId` is set. Closes via the header close button, backdrop click, or
 * Esc (HeroUI default). Literal Tailwind only (IN-12); no new dependency.
 */
export function CoroutineSourceDrawer({
  sessionId,
  coroutineId,
  label,
  isOpen,
  onClose,
}: CoroutineSourceDrawerProps) {
  return (
    <Drawer isOpen={isOpen} onClose={onClose} placement="right" size="lg">
      <DrawerContent>
        <DrawerHeader className="flex items-center justify-between">
          <h3 className="text-lg font-semibold">Coroutine source — {label || coroutineId}</h3>
          <Button
            isIconOnly
            size="sm"
            variant="light"
            aria-label="Close source drawer"
            onPress={onClose}
          >
            <FiX />
          </Button>
        </DrawerHeader>
        <DrawerBody className="p-4">
          <CoroutineSourceStack sessionId={sessionId} coroutineId={coroutineId} />
        </DrawerBody>
      </DrawerContent>
    </Drawer>
  )
}
