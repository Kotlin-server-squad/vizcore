import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, Button } from '@heroui/react'
import { ValidationPanel } from '../validation/ValidationPanel'
import type { useValidation } from '@/hooks/use-validation'
import type { ValidationResponse } from '@/types/api'
import type { ChecksRun } from '@/types/health'

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
  lastRun = null,
}: {
  sessionId: string
  isOpen: boolean
  onOpenChange: (open: boolean) => void
  validation: Validation
  /** The session's persisted last run (triage C-5), shown until this visit runs the checks itself. */
  lastRun?: ChecksRun | null
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
          {lastRun && !validation.data && (
            <div data-testid="checks-last-run" className="rounded-medium border border-default-200 p-3 text-sm">
              <p className="font-semibold">Last run at event {lastRun.checkedSeq.toLocaleString('en-US')}</p>
              <ul className="mt-1 space-y-0.5">
                {lastRun.rules.map(rule => (
                  <li key={rule.name} className={rule.passed ? 'text-default-600' : 'text-danger'}>
                    {rule.name}: {rule.passed ? 'passed' : `failed (${rule.failures.toLocaleString('en-US')})`}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <ValidationPanel sessionId={sessionId} validation={validation} showHeading={false} />
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
