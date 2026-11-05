import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
import { motion } from 'framer-motion'
import type { SemaphoreState } from '@/hooks/use-sync-events'
import { CoroutineName } from './CoroutineName'

interface SemaphoreGaugeProps {
  semaphore: SemaphoreState
  /** The page selected this semaphore (S-2): ringed. */
  selected?: boolean
  /** The page's selected coroutine, marked when it holds a permit. */
  selectedCoroutineId?: string | null
  /** Selects a holder (S-2). */
  onSelectCoroutine?: (id: string) => void
}

function getGaugeColor(available: number, total: number): 'success' | 'warning' | 'danger' {
  if (total === 0) return 'success'
  const ratio = available / total
  if (ratio <= 0) return 'danger'
  if (ratio < 0.3) return 'warning'
  return 'success'
}

export function SemaphoreGauge({
  semaphore,
  selected = false,
  selectedCoroutineId = null,
  onSelectCoroutine,
}: SemaphoreGaugeProps) {
  const usedPermits = semaphore.totalPermits - semaphore.availablePermits
  const percent = semaphore.totalPermits > 0 ? (usedPermits / semaphore.totalPermits) * 100 : 0
  const color = getGaugeColor(semaphore.availablePermits, semaphore.totalPermits)

  // SVG arc calculation
  const circumference = 2 * Math.PI * 40 // r=40
  const dashArray = (percent / 100) * circumference
  const dashOffset = circumference - dashArray

  return (
    <div
      data-testid={`resource-${semaphore.semaphoreId}`}
      data-selected={selected ? 'true' : 'false'}
      className={selected ? 'rounded-large ring-2 ring-primary' : undefined}
    >
      <Card shadow="sm" data-testid="semaphore-gauge">
        <CardHeader className="pb-2">
          <div className="flex w-full items-center justify-between">
            <div>
              <div className="font-semibold text-sm">
                {semaphore.label || semaphore.semaphoreId}
              </div>
              <div className="text-xs text-default-500 font-mono">{semaphore.semaphoreId}</div>
            </div>
            <Chip size="sm" variant="flat" color={color}>
              {semaphore.availablePermits} / {semaphore.totalPermits} available
            </Chip>
          </div>
        </CardHeader>
        <CardBody className="pt-0 space-y-3">
          {/* Circular gauge representation using progress bar */}
          <div className="flex items-center justify-center" data-testid="gauge-visual">
            <div className="relative w-24 h-24">
              <svg viewBox="0 0 100 100" className="w-full h-full -rotate-90">
                {/* Background circle */}
                <circle
                  cx="50"
                  cy="50"
                  r="40"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="8"
                  className="text-default-200"
                />
                {/* Used permits arc - animated */}
                <motion.circle
                  cx="50"
                  cy="50"
                  r="40"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="8"
                  strokeDasharray={circumference}
                  initial={{ strokeDashoffset: circumference }}
                  animate={{ strokeDashoffset: dashOffset }}
                  transition={{ duration: 0.8, ease: 'easeOut' }}
                  className={
                    color === 'danger'
                      ? 'text-danger'
                      : color === 'warning'
                        ? 'text-warning'
                        : 'text-success'
                  }
                  strokeLinecap="round"
                />
              </svg>
              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <motion.div
                  className="text-lg font-bold"
                  initial={{ scale: 0.8, opacity: 0 }}
                  animate={{ scale: 1, opacity: 1 }}
                  transition={{ type: 'spring', stiffness: 300, damping: 25, delay: 0.3 }}
                >
                  {usedPermits}
                </motion.div>
                <div className="text-[10px] text-default-400">used</div>
              </div>
            </div>
          </div>

          {/* Who holds the permits (S-6): counted, never numbered; a permit that came back from a
            coroutine that held none has no known holder (S-7). */}
          <div data-testid="semaphore-holders" className="text-xs">
            <span className="text-default-500">Held by </span>
            {semaphore.activeHolders.length === 0 && semaphore.unknownHolderPermits === 0 ? (
              <span>none</span>
            ) : (
              <span className="inline-flex flex-wrap items-baseline gap-x-2">
                {semaphore.activeHolders.map(h => (
                  <span key={h.id}>
                    <CoroutineName
                      id={h.id}
                      label={h.label}
                      onSelect={onSelectCoroutine}
                      selected={h.id === selectedCoroutineId}
                    />
                    {h.permits > 1 && (
                      <span className="text-default-500"> ({h.permits} permits)</span>
                    )}
                  </span>
                ))}
                {semaphore.unknownHolderPermits > 0 && (
                  <span className="text-default-500">
                    {semaphore.unknownHolderPermits === 1
                      ? '1 permit'
                      : `${semaphore.unknownHolderPermits} permits`}
                    , holder unknown
                  </span>
                )}
              </span>
            )}
          </div>

          {/* Waiting info */}
          {semaphore.waitQueue.length > 0 && (
            <div className="text-xs">
              <Chip size="sm" variant="flat" color="warning">
                {semaphore.waitQueue.length} waiting
              </Chip>
            </div>
          )}
        </CardBody>
      </Card>
    </div>
  )
}
