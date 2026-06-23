import { useState } from 'react'
import {
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter,
  Button,
  Select,
  SelectItem,
  Input,
} from '@heroui/react'
import { apiClient } from '@/lib/api-client'
import { toastSuccess, toastError } from '@/lib/toast'
import type { CreateShareResponse, ShareExpiry } from '@/types/share'

/**
 * ShareDialog (D-11) — mint + copy a read-only share link for a session.
 *
 * Mirrors the canonical `RecordConfirmModal` HeroUI pattern (`Modal` size="sm",
 * ModalHeader/Body/Footer, light Cancel + primary action). Two phases:
 *   1. Before a link exists: an expiry picker ("1 day"/"7 days"/"30 days"/
 *      "Never" → `1d`/`7d`/`30d`/`never`) + a primary "Create link" CTA.
 *   2. After creation: a read-only field with the returned URL + a "Copy link"
 *      action (`navigator.clipboard.writeText` → `toastSuccess('Link copied')`).
 *
 * Copy is locked by 03-UI-SPEC §Share-creation dialog.
 */

const COPY = {
  title: 'Share session',
  expiryLabel: 'Link expires',
  createCta: 'Create link',
  creating: 'Creating…',
  fieldLabel: 'Anyone with this link can view this session (read-only).',
  copyCta: 'Copy link',
  done: 'Done',
  createError: 'Could not create the share link. Try again.',
  copySuccess: 'Link copied',
  copyError: 'Could not copy the link. Select it and copy manually.',
} as const

/** Expiry options (D-11) — UI label → ADR-019 `expiresIn` code. */
const EXPIRY_OPTIONS: ReadonlyArray<{ key: ShareExpiry; label: string }> = [
  { key: '1d', label: '1 day' },
  { key: '7d', label: '7 days' },
  { key: '30d', label: '30 days' },
  { key: 'never', label: 'Never' },
]

const DEFAULT_EXPIRY: ShareExpiry = '7d'

export interface ShareDialogProps {
  isOpen: boolean
  sessionId: string
  onClose: () => void
}

export function ShareDialog({ isOpen, sessionId, onClose }: ShareDialogProps) {
  const [expiry, setExpiry] = useState<ShareExpiry>(DEFAULT_EXPIRY)
  const [created, setCreated] = useState<CreateShareResponse | null>(null)
  const [isCreating, setIsCreating] = useState(false)

  // The recipient opens the link in a browser, so it must point at the FRONTEND
  // origin that serves the /shared/:token SPA route — NOT the origin the
  // api-client's request reached. In the documented split-port dev setup the
  // backend derives its own origin (:8080) and returns http://localhost:8080/...,
  // which 404s for the recipient (only :3000 serves /shared/:token). Build the
  // link from where the app is actually served (F3).
  const shareUrl = created
    ? new URL(`/shared/${created.token}`, window.location.origin).toString()
    : ''

  const handleCreate = async () => {
    setIsCreating(true)
    try {
      const result = await apiClient.createShare(sessionId, expiry)
      setCreated(result)
    } catch {
      toastError(COPY.createError)
    } finally {
      setIsCreating(false)
    }
  }

  const handleCopy = async () => {
    if (!created) return
    try {
      await navigator.clipboard.writeText(shareUrl)
      toastSuccess(COPY.copySuccess)
    } catch {
      // Leave the URL field selectable for manual copy.
      toastError(COPY.copyError)
    }
  }

  const handleClose = () => {
    // Reset for the next open so a stale URL never lingers.
    setCreated(null)
    setExpiry(DEFAULT_EXPIRY)
    onClose()
  }

  return (
    <Modal
      isOpen={isOpen}
      onOpenChange={(open) => {
        if (!open) handleClose()
      }}
      size="sm"
    >
      <ModalContent>
        <ModalHeader>{COPY.title}</ModalHeader>
        <ModalBody>
          {created === null ? (
            <Select
              label={COPY.expiryLabel}
              selectedKeys={[expiry]}
              disallowEmptySelection
              onSelectionChange={(keys) => {
                const next = Array.from(keys)[0] as ShareExpiry | undefined
                if (next) setExpiry(next)
              }}
            >
              {EXPIRY_OPTIONS.map((opt) => (
                <SelectItem key={opt.key}>{opt.label}</SelectItem>
              ))}
            </Select>
          ) : (
            <div className="flex flex-col gap-2">
              <Input
                label={COPY.fieldLabel}
                value={shareUrl}
                isReadOnly
                onFocus={(e) => e.currentTarget.select()}
              />
            </div>
          )}
        </ModalBody>
        <ModalFooter>
          {created === null ? (
            <Button color="primary" onPress={handleCreate} isLoading={isCreating}>
              {isCreating ? COPY.creating : COPY.createCta}
            </Button>
          ) : (
            <>
              <Button variant="light" onPress={handleClose}>
                {COPY.done}
              </Button>
              <Button color="primary" onPress={handleCopy}>
                {COPY.copyCta}
              </Button>
            </>
          )}
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
