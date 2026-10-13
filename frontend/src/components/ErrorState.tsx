import { useEffect, type ReactNode } from 'react'
import { Button, Card, CardBody } from '@heroui/react'
import { FiAlertCircle, FiClock, FiLock, FiSearch, FiWifiOff } from 'react-icons/fi'
import { describeApiError, type ApiErrorKind } from '@/lib/api-errors'

const ICONS: Record<ApiErrorKind, ReactNode> = {
  'not-found': <FiSearch className="h-5 w-5" />,
  unauthorized: <FiLock className="h-5 w-5" />,
  'rate-limited': <FiClock className="h-5 w-5" />,
  server: <FiAlertCircle className="h-5 w-5" />,
  network: <FiWifiOff className="h-5 w-5" />,
  client: <FiAlertCircle className="h-5 w-5" />,
  unknown: <FiAlertCircle className="h-5 w-5" />,
}

interface ErrorStateProps {
  error: unknown
  /** What failed to load, for the not-found copy ("This session"). */
  subject?: string
  /** Re-run the failed request. Offered only when a retry can succeed. */
  onRetry?: () => void
  /** A way out that always makes sense, e.g. back to the sessions list. */
  action?: { label: string; onPress: () => void }
  /** Render without the card chrome (inside lists and modals). */
  bare?: boolean
}

/**
 * One truthful failure state for any failed read (#139).
 *
 * 404, 401, 429, 5xx and "no response" used to collapse into whatever the
 * caller's empty state said ("Session not found", "No app connected"). Each
 * now names what actually happened. A rate limit retries itself once the
 * server's Retry-After has passed.
 */
export function ErrorState({ error, subject, onRetry, action, bare = false }: ErrorStateProps) {
  const described = describeApiError(error, subject)
  const autoRetryMs = described.kind === 'rate-limited' ? described.retryAfterMs : undefined

  useEffect(() => {
    if (!onRetry || autoRetryMs === undefined) return
    const timer = setTimeout(onRetry, autoRetryMs + 250)
    return () => clearTimeout(timer)
  }, [onRetry, autoRetryMs])

  // Literal Tailwind classes only (IN-12).
  const titleClass =
    described.kind === 'rate-limited'
      ? 'flex items-center gap-2 font-semibold text-warning'
      : 'flex items-center gap-2 font-semibold text-danger'
  const body = (
    <div
      role="alert"
      data-testid="error-state"
      data-kind={described.kind}
      className="flex flex-col items-center gap-3 py-8 text-center"
    >
      <div className={titleClass}>
        {ICONS[described.kind]}
        <span>{described.title}</span>
      </div>
      <p className="max-w-prose text-sm text-default-500">{described.message}</p>
      <div className="flex gap-2">
        {onRetry && described.retryable && (
          <Button size="sm" variant="flat" onPress={onRetry}>
            Retry
          </Button>
        )}
        {action && (
          <Button size="sm" variant="light" onPress={action.onPress}>
            {action.label}
          </Button>
        )}
      </div>
    </div>
  )

  if (bare) return body
  return (
    <Card>
      <CardBody>{body}</CardBody>
    </Card>
  )
}
