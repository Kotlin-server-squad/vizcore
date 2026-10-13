import { useEffect, useRef, useState } from 'react'
import {
  Button,
  Input,
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
  Spinner,
} from '@heroui/react'
import { FiCheck, FiCopy } from 'react-icons/fi'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { apiClient, RateLimitedError } from '@/lib/api-client'
import { getToken } from '@/lib/auth-store'
import { backendBaseUrl } from '@/lib/backend-url'
import { APP_NAME_PLACEHOLDER, dependencySnippet, startSnippet } from '@/lib/connect-snippets'
import { resolveRefetchInterval } from '@/lib/poll-interval'

/**
 * After this long without the app binding, the waiting step turns into a
 * troubleshooting checklist instead of an indefinite spinner (#126).
 */
export const STILL_WAITING_AFTER_MS = 45_000

/**
 * The 3-step connect wizard (Phase 08.5, Surface 003 winner A; Phase 9 ONB-01 rewire).
 *
 * A HeroUI Modal with three step rows: (1) add the client-library dependency
 * (repository + coordinate), (2) call `VizcoreClient.start(...)`, (3) run the
 * app — a spinner while waiting for it, then a troubleshooting checklist.
 *
 * Every OPEN mints a fresh client-side correlation token (D-01, #126) and bakes
 * it into the start snippet; the wizard polls
 * `GET /api/sessions/resolve?correlation=<uuid>` and auto-navigates to the
 * app's real session — with the live stream on — the moment it binds (D-03).
 * The snippet is generated against the SDK's current signature and the backend
 * URL this SPA is actually talking to; connect-snippets.test.ts guards both.
 *
 * The correlation UUID and `appName` are rendered as plain React text
 * (auto-escaped); the correlation token is non-secret (PD-14 / T-09-08).
 * Literal Tailwind only (IN-12).
 */
export function ConnectWizard({ isOpen, onClose }: { isOpen: boolean; onClose: () => void }) {
  const navigate = useNavigate()
  const [correlation, setCorrelation] = useState(() => crypto.randomUUID())
  const [appName, setAppName] = useState(APP_NAME_PLACEHOLDER)
  const [stillWaiting, setStillWaiting] = useState(false)
  // A fresh token per OPEN, not per mount (#126): the home page keeps the
  // wizard mounted, so a lazy initial state alone reused one token across
  // opens. Derived-state-on-prop-change, applied during render.
  const [wasOpen, setWasOpen] = useState(isOpen)
  if (isOpen !== wasOpen) {
    setWasOpen(isOpen)
    if (isOpen) {
      setCorrelation(crypto.randomUUID())
      setStillWaiting(false)
    }
  }
  const resolvedRef = useRef(false)

  const backendUrl = backendBaseUrl()
  const depSnippet = dependencySnippet()
  const codeSnippet = startSnippet({
    appName,
    backendUrl,
    authRequired: getToken() !== null,
    correlation,
  })

  // Poll resolve (token-scoped) until the connecting app's session binds to the
  // correlation token (D-02/D-03). resolveCorrelation returns null on 404 (not
  // bound yet) and throws on a 429/5xx; the interval keeps running through
  // errors (backing off after a 429) and stops once a session id appears.
  const { data, error } = useQuery<{ sessionId: string } | null, Error>({
    queryKey: ['resolve-correlation', correlation],
    queryFn: () => apiClient.resolveCorrelation(correlation),
    enabled: isOpen,
    refetchInterval: resolveRefetchInterval,
    // The interval IS the retry policy — an immediate retry would only spend
    // more of the same rate-limit budget.
    retry: false,
  })
  const pollProblem =
    error instanceof RateLimitedError
      ? 'The server is rate-limiting requests — checking again shortly.'
      : error
        ? `Can't reach vizcore right now (${error.message}) — still trying.`
        : null

  // Reset the one-shot navigate guard when the wizard closes so a re-open with a
  // fresh token can resolve again.
  useEffect(() => {
    if (!isOpen) {
      resolvedRef.current = false
    }
  }, [isOpen])

  // "Still waiting" after a while, per open (keyed on the token).
  useEffect(() => {
    if (!isOpen) return
    const timer = setTimeout(() => setStillWaiting(true), STILL_WAITING_AFTER_MS)
    return () => clearTimeout(timer)
  }, [isOpen, correlation])

  // A newly connected real app opens with the live stream ON (#126).
  const openSession = (sessionId: string) =>
    navigate({ to: '/sessions/$sessionId', params: { sessionId }, search: { live: true } })

  const goLive = () => {
    if (!data?.sessionId) return
    void openSession(data.sessionId)
  }

  // Auto-navigate to the REAL session the instant resolve returns its id (D-03),
  // exactly once.
  useEffect(() => {
    if (resolvedRef.current) return
    if (data?.sessionId) {
      resolvedRef.current = true
      void openSession(data.sessionId)
    }
    // Intentionally keyed to the resolve result: navigate is stable.
  }, [data])

  const shownName = appName.trim() || APP_NAME_PLACEHOLDER

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="2xl" scrollBehavior="inside">
      <ModalContent>
        <ModalHeader>Connect your app</ModalHeader>
        <ModalBody className="gap-5">
          <Step index={1} title="Add the client library">
            <p className="text-xs text-default-500">
              In your app&apos;s <code className="font-mono">build.gradle.kts</code>. The SDK is
              published to GitHub Packages, which needs a GitHub token with{' '}
              <code className="font-mono">read:packages</code>.
            </p>
            <CodeBlock label="Gradle dependency" code={depSnippet} />
          </Step>

          <Step index={2} title="Start it in your app">
            <Input
              size="sm"
              label="App name"
              description="How the app is labelled in vizcore"
              value={appName}
              onValueChange={setAppName}
              className="max-w-xs"
            />
            <CodeBlock label="Kotlin start call" code={codeSnippet} />
            <p className="text-xs text-default-500">
              Installs DebugProbes and streams to{' '}
              <span className="font-mono">{backendUrl}</span>. The correlation value is new
              each time this wizard opens, so copy the snippet again after reopening it.
            </p>
          </Step>

          <Step index={3} title="Run your app">
            {stillWaiting ? (
              <StillWaiting backendUrl={backendUrl} onShowSessions={onClose} />
            ) : (
              <div className="flex items-center gap-3">
                <Spinner size="sm" />
                <span className="font-mono text-xs text-default-500">
                  Waiting for events from {shownName}…
                </span>
              </div>
            )}
            {pollProblem && (
              <p role="status" className="text-xs text-warning">
                {pollProblem}
              </p>
            )}
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
            <CopyButton
              color="primary"
              label="Copy setup snippet"
              text={`${depSnippet}\n\n${codeSnippet}`}
            />
          </div>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}

/** The troubleshooting state shown once waiting has gone on too long (#126). */
function StillWaiting({
  backendUrl,
  onShowSessions,
}: {
  backendUrl: string
  onShowSessions: () => void
}) {
  return (
    <div data-testid="still-waiting" className="flex flex-col gap-2 rounded-medium border border-warning-200 bg-warning-50 p-3 text-xs">
      <div className="flex items-center gap-2 font-semibold text-warning">
        <Spinner size="sm" color="warning" />
        Still waiting — nothing has connected with this wizard&apos;s correlation yet.
      </div>
      <ul className="list-disc space-y-1 pl-5 text-default-600">
        <li>Is the app running, and does it call <code className="font-mono">VizcoreClient.start(...)</code> at startup?</li>
        <li>
          Did Gradle resolve the dependency? A 401 from GitHub Packages means the
          credentials are missing.
        </li>
        <li>
          Can the app reach vizcore? Try <code className="font-mono">curl {backendUrl}/api/health</code> from
          the app&apos;s machine.
        </li>
        <li>Did you reopen this wizard after copying? Each open has a new correlation value.</li>
        <li>The app still shows up in the sessions list if it connected without the correlation.</li>
      </ul>
      <div>
        <Button size="sm" variant="flat" onPress={onShowSessions}>
          Check the sessions list
        </Button>
      </div>
    </div>
  )
}

/** A scrollable code block with its own copy button — never overflows the modal (#126). */
function CodeBlock({ label, code }: { label: string; code: string }) {
  return (
    <div className="relative min-w-0 rounded-medium border border-default-200 bg-default-100">
      <div className="absolute right-1 top-1">
        <CopyButton label={`Copy ${label.toLowerCase()}`} text={code} iconOnly />
      </div>
      <pre
        aria-label={label}
        className="max-h-64 max-w-full overflow-auto whitespace-pre p-3 pr-12 font-mono text-xs"
      >
        <code>{code}</code>
      </pre>
    </div>
  )
}

/** Copy to clipboard with visible feedback ("Copied" / "Copy failed"). */
function CopyButton({
  label,
  text,
  iconOnly = false,
  color = 'default',
}: {
  label: string
  text: string
  iconOnly?: boolean
  color?: 'default' | 'primary'
}) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')

  useEffect(() => {
    if (state === 'idle') return
    const timer = setTimeout(() => setState('idle'), 2000)
    return () => clearTimeout(timer)
  }, [state])

  const copy = async () => {
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable')
      await navigator.clipboard.writeText(text)
      setState('copied')
    } catch {
      setState('failed')
    }
  }

  const feedback = state === 'copied' ? 'Copied' : state === 'failed' ? 'Copy failed — select the text' : null

  if (iconOnly) {
    return (
      <Button
        isIconOnly
        size="sm"
        variant="light"
        aria-label={feedback ?? label}
        title={feedback ?? label}
        onPress={() => void copy()}
      >
        {state === 'copied' ? <FiCheck /> : <FiCopy />}
      </Button>
    )
  }
  return (
    <Button color={color} size="sm" onPress={() => void copy()}>
      {feedback ?? label}
    </Button>
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
