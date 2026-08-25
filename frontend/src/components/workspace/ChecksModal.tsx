import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, Button } from '@heroui/react'
import { ValidationPanel } from '../validation/ValidationPanel'
import type { useValidation } from '@/hooks/use-validation'
import type { ValidationResponse } from '@/types/api'

type Validation = ReturnType<typeof useValidation>

/**
 * How many checks failed in the last run.
 *
 * Returns 0 before any run — an un-run check is not a passing check, and a bar
 * chip that reads "Checks 0" for a session nobody has validated is a claim the
 * app has no basis for. The caller shows the chip only when this is non-zero.
 */
export function countFailedChecks(data: ValidationResponse | null | undefined): number {
  if (!data) return 0
  return data.results.filter(r => r.type === 'Fail').length
}

/**
 * Session validation, moved out of the tab bar (M-3).
 *
 * Validation was a permanent eighth tab for a report that most sessions never
 * run. It is an action, so it lives behind one — a header button and a state-bar
 * chip when a run has found something — and the results are owned by
 * `SessionWorkspace` so closing this modal does not discard them.
 */
export function ChecksModal({
  sessionId,
  isOpen,
  onOpenChange,
  validation,
}: {
  sessionId: string
  isOpen: boolean
  onOpenChange: (open: boolean) => void
  validation: Validation
}) {
  return (
    <Modal isOpen={isOpen} onOpenChange={onOpenChange} size="3xl" scrollBehavior="inside">
      <ModalContent>
        <ModalHeader className="flex flex-col gap-1">
          <h2 className="text-lg font-semibold">Session checks</h2>
          <span className="text-xs font-normal text-default-500">
            Event ordering, timing anomalies and structural problems in this session.
          </span>
        </ModalHeader>
        <ModalBody>
          <ValidationPanel sessionId={sessionId} validation={validation} />
        </ModalBody>
        <ModalFooter>
          <Button variant="light" onPress={() => onOpenChange(false)}>
            Close
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
