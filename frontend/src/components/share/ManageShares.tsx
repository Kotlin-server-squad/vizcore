import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Card,
  CardBody,
  CardHeader,
  Table,
  TableHeader,
  TableColumn,
  TableBody,
  TableRow,
  TableCell,
  Button,
  Spinner,
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
  ModalFooter,
} from '@heroui/react'
import { apiClient } from '@/lib/api-client'
import { toastSuccess, toastError } from '@/lib/toast'
import { EmptyState } from '@/components/EmptyState'
import { ErrorAlert } from '@/components/ErrorAlert'
import { ShareDialog } from './ShareDialog'
import type { ShareSummary } from '@/types/share'

/**
 * ManageShares (D-13) — list a session's active share links + revoke them.
 *
 * Columns: Link (short token) / Expires (Never for null) / Views (access_count)
 * / Last accessed (— when null) / Revoke (danger). Empty → an EmptyState whose
 * "Create link" action opens the ShareDialog. A list-fetch failure → ErrorAlert.
 * Revoke opens a destructive confirm Modal mirroring RecordConfirmModal; confirm
 * → `revokeShare` → row removed + `toastSuccess('Share link revoked')`.
 *
 * Copy is locked by 03-UI-SPEC §Manage-shares list / §Destructive confirmation.
 */

const COPY = {
  heading: 'Manage shares',
  colLink: 'Link',
  colExpires: 'Expires',
  colViews: 'Views',
  colLastAccessed: 'Last accessed',
  never: 'Never',
  noHits: '—',
  revoke: 'Revoke',
  emptyHeading: 'No active share links',
  emptyBody: 'Create a share link to give read-only access to this session.',
  emptyAction: 'Create link',
  listError: 'Could not load share links. Try again.',
  confirmTitle: 'Revoke share link?',
  confirmBody: 'Anyone using this link will lose access immediately. This cannot be undone.',
  confirmCancel: 'Cancel',
  confirmRevoke: 'Revoke link',
  revoked: 'Share link revoked',
} as const

/** A short, display-friendly slice of the opaque token (first 8 chars + ellipsis). */
function shortToken(token: string): string {
  return token.length > 8 ? `${token.slice(0, 8)}…` : token
}

function formatExpiry(expiresAt: string | null): string {
  if (expiresAt === null) return COPY.never
  return new Date(expiresAt).toLocaleDateString()
}

function formatLastAccessed(lastAccessedAt: string | null): string {
  if (lastAccessedAt === null) return COPY.noHits
  return new Date(lastAccessedAt).toLocaleString()
}

export interface ManageSharesProps {
  sessionId: string
}

export function ManageShares({ sessionId }: ManageSharesProps) {
  const queryClient = useQueryClient()
  const sharesKey = ['shares', sessionId] as const

  const { data: shares, isLoading, isError } = useQuery({
    queryKey: sharesKey,
    queryFn: () => apiClient.listShares(sessionId),
  })

  // The token pending revoke (drives the destructive confirm modal); null = closed.
  const [pendingRevoke, setPendingRevoke] = useState<string | null>(null)
  const [shareDialogOpen, setShareDialogOpen] = useState(false)

  const revokeMutation = useMutation({
    mutationFn: (token: string) => apiClient.revokeShare(sessionId, token),
    onSuccess: (_data, token) => {
      // Optimistically drop the revoked row from the cached list.
      queryClient.setQueryData<ShareSummary[]>(sharesKey, (prev) =>
        (prev ?? []).filter((s) => s.token !== token),
      )
      toastSuccess(COPY.revoked)
    },
    onError: () => {
      toastError(COPY.listError)
    },
    onSettled: () => {
      setPendingRevoke(null)
    },
  })

  const confirmRevoke = () => {
    if (pendingRevoke) revokeMutation.mutate(pendingRevoke)
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Spinner size="lg" />
      </div>
    )
  }

  if (isError) {
    return <ErrorAlert message={COPY.listError} />
  }

  const rows = shares ?? []

  return (
    <>
      {rows.length === 0 ? (
        <EmptyState
          title={COPY.emptyHeading}
          description={COPY.emptyBody}
          action={{ label: COPY.emptyAction, onClick: () => setShareDialogOpen(true) }}
        />
      ) : (
        <Card>
          <CardHeader>
            <h2 className="text-lg font-semibold">{COPY.heading}</h2>
          </CardHeader>
          <CardBody>
            <Table aria-label="Active share links" removeWrapper>
              <TableHeader>
                <TableColumn>{COPY.colLink}</TableColumn>
                <TableColumn>{COPY.colExpires}</TableColumn>
                <TableColumn>{COPY.colViews}</TableColumn>
                <TableColumn>{COPY.colLastAccessed}</TableColumn>
                <TableColumn aria-label="Actions">{''}</TableColumn>
              </TableHeader>
              <TableBody>
                {rows.map((share) => (
                  <TableRow key={share.token}>
                    <TableCell>
                      <span className="font-mono text-sm">{shortToken(share.token)}</span>
                    </TableCell>
                    <TableCell>
                      <span className="text-sm text-default-500">
                        {formatExpiry(share.expiresAt)}
                      </span>
                    </TableCell>
                    <TableCell>{share.accessCount}</TableCell>
                    <TableCell>
                      <span className="text-sm text-default-500">
                        {formatLastAccessed(share.lastAccessedAt)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <Button
                        size="sm"
                        color="danger"
                        variant="light"
                        onPress={() => setPendingRevoke(share.token)}
                      >
                        {COPY.revoke}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardBody>
        </Card>
      )}

      {/* Destructive revoke confirm (mirrors RecordConfirmModal). */}
      <Modal
        isOpen={pendingRevoke !== null}
        onOpenChange={(open) => {
          if (!open) setPendingRevoke(null)
        }}
        size="sm"
      >
        <ModalContent>
          <ModalHeader>{COPY.confirmTitle}</ModalHeader>
          <ModalBody>
            <p>{COPY.confirmBody}</p>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={() => setPendingRevoke(null)}>
              {COPY.confirmCancel}
            </Button>
            <Button
              color="danger"
              onPress={confirmRevoke}
              isLoading={revokeMutation.isPending}
            >
              {COPY.confirmRevoke}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <ShareDialog
        isOpen={shareDialogOpen}
        sessionId={sessionId}
        onClose={() => {
          setShareDialogOpen(false)
          // A freshly created link should appear in the list.
          void queryClient.invalidateQueries({ queryKey: sharesKey })
        }}
      />
    </>
  )
}
