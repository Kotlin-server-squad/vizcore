import { useState } from 'react'
import { Modal, ModalBody, ModalContent, ModalHeader } from '@heroui/react'
import { useNavigate } from '@tanstack/react-router'
import { ComparisonView } from '@/components/comparison/ComparisonView'

/**
 * Compare as an action rather than a destination (D-3).
 *
 * `ComparisonView` already owns its two session pickers, so this is a re-host,
 * not a rewrite: the overlay holds the selection locally instead of in the URL.
 * The shareable `/compare?a=&b=` route still exists for links that carry a
 * selection — see `shouldRedirectBareCompare`.
 */
export function CompareOverlay({
  isOpen,
  onClose,
}: {
  isOpen: boolean
  onClose: () => void
}) {
  const [a, setA] = useState<string | undefined>(undefined)
  const [b, setB] = useState<string | undefined>(undefined)
  const navigate = useNavigate()

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="5xl" scrollBehavior="inside">
      <ModalContent>
        <ModalHeader>Compare sessions</ModalHeader>
        <ModalBody className="pb-6">
          <ComparisonView
            a={a}
            b={b}
            onAChange={setA}
            onBChange={setB}
            onBrowseSessions={() => {
              onClose()
              void navigate({ to: '/' })
            }}
          />
        </ModalBody>
      </ModalContent>
    </Modal>
  )
}
