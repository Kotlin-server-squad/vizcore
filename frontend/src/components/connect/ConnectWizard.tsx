import { useEffect, useRef, useState } from 'react'
import {
  Button,
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
  Snippet,
  Spinner,
} from '@heroui/react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { apiClient } from '@/lib/api-client'
import { DEP_SNIPPET } from '@/lib/dep-snippet'

const APP_NAME = 'order-service'

/** Poll resolve every 300ms so the spinner auto-resolves the instant the
 *  connecting app's session binds to the correlation token (D-03). */
const POLL_INTERVAL_MS = 300

/**
 * The 3-step connect wizard (Phase 08.5, Surface 003 winner A; Phase 9 ONB-01 rewire).
 *
 * A HeroUI Modal with three step rows: (1) add the client-library dependency,
 * (2) enable `VizcoreClient.start()`, (3) run the app — a Spinner + "Waiting for
 * events from {appName}…".
 *
 * On open the wizard generates ONE client-side correlation token via
 * `crypto.randomUUID()` (D-01) and bakes it into the start snippet. It NO LONGER
 * mints its own session (the old `useCreateSession` self-mint is gone — D-02);
 * the user's `VizcoreClient.start(..., correlation = "<uuid>")` creates the only
 * session. The wizard POLLS `GET /api/sessions/resolve?correlation=<uuid>` via
 * `apiClient.resolveCorrelation` (token-scoped `useQuery` with `refetchInterval`)
 * and AUTO-navigates to the REAL connected app's live session the moment resolve
 * returns its id (D-03) — this is the structural ONB-01 fix: the user lands on
 * their actual app, not a decoupled empty self-minted session. A "Skip to live
 * view" affordance navigates to that same resolved id so the wizard is never a
 * dead-end (Pitfall 6); it stays disabled until resolve lands so it can never
 * point at a non-existent id.
 *
 * The correlation UUID and `appName` are rendered as plain React text
 * (auto-escaped) and never injected into a URL/clipboard as markup (PD-14 /
 * T-09-08). The snippets are static public copy — the correlation token is
 * non-secret (PD-14 / T-09-08). Literal Tailwind only (IN-12).
 */
export function ConnectWizard({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) {
  const navigate = useNavigate()
  // One client-side correlation token per wizard instance (D-01). The lazy state
  // initializer runs once and stays stable across renders so the snippet and the
  // poll key never drift.
  const [correlation] = useState(() => crypto.randomUUID())
  const resolvedRef = useRef(false)

  const START_SNIPPET = `VizcoreClient.start(appName = "${APP_NAME}", correlation = "${correlation}")`

  // Poll resolve (token-scoped) until the connecting app's session binds to the
  // correlation token (D-02/D-03). resolveCorrelation returns null on 404
  // (not bound yet) — the queryFn never throws or returns undefined, so the poll
  // keeps running cleanly until a real session id appears.
  const { data } = useQuery({
    queryKey: ['resolve-correlation', correlation],
    queryFn: () => apiClient.resolveCorrelation(correlation),
    enabled: isOpen,
    refetchInterval: POLL_INTERVAL_MS,
  })

  // Reset the one-shot navigate guard when the wizard closes so a re-open with a
  // fresh token can resolve again.
  useEffect(() => {
    if (!isOpen) {
      resolvedRef.current = false
    }
  }, [isOpen])

  const goLive = () => {
    if (!data?.sessionId) return
    navigate({ to: '/sessions/$sessionId', params: { sessionId: data.sessionId } })
  }

  // Auto-navigate to the REAL session the instant resolve returns its id (D-03),
  // exactly once.
  useEffect(() => {
    if (resolvedRef.current) return
    if (data?.sessionId) {
      resolvedRef.current = true
      navigate({ to: '/sessions/$sessionId', params: { sessionId: data.sessionId } })
    }
    // Intentionally keyed to the resolve result: navigate is stable.
  }, [data])

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="lg">
      <ModalContent>
        <ModalHeader>Connect your app</ModalHeader>
        <ModalBody className="gap-4">
          <Step index={1} title="Add the client library">
            <Snippet hideSymbol className="w-full" size="sm">
              {DEP_SNIPPET}
            </Snippet>
          </Step>

          <Step index={2} title="Enable in your app">
            <Snippet hideSymbol className="w-full" size="sm">
              {START_SNIPPET}
            </Snippet>
            <p className="text-xs text-default-500">
              installs DebugProbes, streams to localhost:8080
            </p>
          </Step>

          <Step index={3} title="Run your app">
            <div className="flex items-center gap-3">
              <Spinner size="sm" />
              <span className="font-mono text-xs text-default-500">
                Waiting for events from {APP_NAME}…
              </span>
            </div>
          </Step>
        </ModalBody>
        <ModalFooter className="flex items-center justify-between">
          <Button variant="ghost" size="sm" onPress={goLive} isDisabled={!data?.sessionId}>
            Skip to live view
          </Button>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onPress={onClose}>
              Cancel
            </Button>
            <Button
              color="primary"
              size="sm"
              onPress={() => {
                void navigator.clipboard?.writeText(`${DEP_SNIPPET}\n${START_SNIPPET}`)
              }}
            >
              Copy setup snippet
            </Button>
          </div>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/** One numbered wizard step row (literal-class indicator, IN-12). */
function Step({
  index,
  title,
  children,
}: {
  index: number
  title: string
  children: React.ReactNode
}) {
  return (
    <div className="flex gap-3">
      <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary">
        {index}
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <h3 className="text-sm font-semibold">{title}</h3>
        {children}
      </div>
    </div>
  )
}
