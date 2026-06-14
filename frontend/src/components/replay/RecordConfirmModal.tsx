/**
 * RecordConfirmModal — D-26 long-recording confirmation.
 *
 * Shown when the estimated replay duration exceeds 120s (the >2min threshold).
 * The copy is locked by UI-SPEC §Copywriting:
 *   Title: "Record replay?"
 *   Body:  "This recording will take about {m} min {s} s at {speed}x speed.
 *           Choose a higher speed for a shorter video."
 *   Actions: "Cancel" (variant="light") / "Start recording" (color="primary")
 *
 * One of three DoS brakes on an unbounded WebM blob (threat T-02-15) alongside
 * controller Stop (D-23) and abort-on-hidden (D-24).
 */

import {
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter,
  Button,
} from '@heroui/react'

export interface RecordConfirmModalProps {
  /** Whether the confirm modal is visible. */
  isOpen: boolean
  /** The estimated recording duration in ms (drives the m/s copy). */
  estimateMs: number
  /** The active playback speed shown in the body copy. */
  speed: number
  /** Proceed with recording (D-26 confirm). */
  onConfirm: () => void
  /** Dismiss without recording. */
  onCancel: () => void
}

/** Format an ms estimate as whole "{m} min {s} s" (e.g. 150000 → "2 min 30 s"). */
function formatEstimate(ms: number): string {
  const totalSeconds = Math.round(ms / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes} min ${seconds} s`
}

export function RecordConfirmModal({
  isOpen,
  estimateMs,
  speed,
  onConfirm,
  onCancel,
}: RecordConfirmModalProps) {
  return (
    <Modal
      isOpen={isOpen}
      onOpenChange={(open) => {
        if (!open) onCancel()
      }}
      size="sm"
    >
      <ModalContent>
        <ModalHeader>Record replay?</ModalHeader>
        <ModalBody>
          <p data-testid="record-confirm-body">
            This recording will take about {formatEstimate(estimateMs)} at {speed}x
            speed. Choose a higher speed for a shorter video.
          </p>
        </ModalBody>
        <ModalFooter>
          <Button variant="light" onPress={onCancel}>
            Cancel
          </Button>
          <Button color="primary" onPress={onConfirm}>
            Start recording
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
