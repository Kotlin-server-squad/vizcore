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
import { useCreateSession } from '@/hooks/use-sessions'

const DEP_SNIPPET = 'implementation("com.jh:vizcore-client:0.1")'
const START_SNIPPET = 'VizcoreClient.start(appName = "order-service")'
const APP_NAME = 'order-service'

/** Poll the new session every 300ms so the spinner auto-resolves (PD-11). */
const POLL_INTERVAL_MS = 300

/**
 * The 3-step connect wizard (Phase 08.5, Surface 003 winner A).
 *
 * A HeroUI Modal with three step rows: (1) add the client-library dependency,
 * (2) enable `VizcoreClient.start()`, (3) run the app — a Spinner + "Waiting for
 * events from {appName}…". On open it mints a new session via `useCreateSession`
 * and POLLS that new id (PD-11): because the shipped `useSession` has NO
 * refetchInterval, this wizard runs a LOCAL `useQuery` scoped to the new id with
 * an explicit `refetchInterval`, and AUTO-resolves to the live view the moment the
 * polled `coroutineCount` transitions > 0 — no manual click needed. A "Skip to
 * live view" affordance navigates immediately as a fallback so the wizard is never
 * a dead-end (Pitfall 6).
 *
 * `appName` is rendered as plain React text (auto-escaped) and never injected into
 * a URL/clipboard as markup (PD-14 / T-085-06). The snippets are static public copy
 * — no secret embedded (PD-14 / T-085-07). Literal Tailwind only (IN-12).
 */
export function ConnectWizard({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) {
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [newSessionId, setNewSessionId] = useState<string | undefined>(undefined)
  const resolvedRef = useRef(false)

  // On open, mint exactly one new session and capture its id to poll.
  useEffect(() => {
    if (!isOpen) {
      setNewSessionId(undefined)
      resolvedRef.current = false
      return
    }
    let cancelled = false
    createSession
      .mutateAsync(undefined)
      .then(result => {
        if (!cancelled) setNewSessionId(result.sessionId)
      })
      .catch(() => {
        /* surfaced by the mutation state; the user can still Skip/Cancel */
      })
    return () => {
      cancelled = true
    }
    // Intentionally keyed to isOpen only: createSession is a stable mutation
    // handle and we mint exactly once per open.
  }, [isOpen])

  // Poll the NEW session (scoped to its id) until first events arrive (PD-11).
  // refetchInterval keeps it polling; enabled-guard scopes detection to the new id
  // so we never resolve off a previous session's cache (Pitfall 6).
  const { data } = useQuery({
    queryKey: ['sessions', newSessionId],
    queryFn: () => apiClient.getSession(newSessionId!),
    enabled: !!newSessionId,
    refetchInterval: POLL_INTERVAL_MS,
  })

  const goLive = () => {
    if (!newSessionId) return
    navigate({ to: '/sessions/$sessionId', params: { sessionId: newSessionId } })
  }

  // Auto-resolve to the live view on the first-events transition (0 → N).
  useEffect(() => {
    if (resolvedRef.current) return
    if (newSessionId && data && data.coroutineCount > 0) {
      resolvedRef.current = true
      navigate({ to: '/sessions/$sessionId', params: { sessionId: newSessionId } })
    }
    // Intentionally keyed to the poll result + new id: navigate is stable.
  }, [data, newSessionId])

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
          <Button variant="ghost" size="sm" onPress={goLive} isDisabled={!newSessionId}>
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
