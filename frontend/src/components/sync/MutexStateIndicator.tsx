import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
import { motion } from 'framer-motion'
import type { MutexState } from '@/hooks/use-sync-events'
import { CoroutineName, type CoroutinePicker } from './CoroutineName'

interface MutexStateIndicatorProps {
  mutex: MutexState
  /** The page selected this lock (S-2): ringed. */
  selected?: boolean
  /** The page's selected coroutine, marked when it holds the lock. */
  selectedCoroutineId?: string | null
  /** Selects the holder, when the page can (S-2). */
  picker?: CoroutinePicker
}

export function MutexStateIndicator({
  mutex,
  selected = false,
  selectedCoroutineId = null,
  picker,
}: MutexStateIndicatorProps) {
  // A held lock is normal work, not an error (spec S-6): neutral grey, never red.
  return (
    <motion.div
      animate={
        mutex.isLocked
          ? {
              boxShadow: [
                '0 0 0 0 rgba(113, 113, 122, 0)',
                '0 0 0 4px rgba(113, 113, 122, 0.15)',
                '0 0 0 0 rgba(113, 113, 122, 0)',
              ],
            }
          : {
              boxShadow: [
                '0 0 0 0 rgba(23, 201, 100, 0)',
                '0 0 0 4px rgba(23, 201, 100, 0.15)',
                '0 0 0 0 rgba(23, 201, 100, 0)',
              ],
            }
      }
      transition={{ duration: 2, repeat: Infinity }}
      data-testid={`resource-${mutex.mutexId}`}
      // Focusable from script only: a selected resource takes focus when it is revealed (S-2).
      tabIndex={-1}
      data-selected={selected ? 'true' : 'false'}
      className={selected ? 'rounded-xl ring-2 ring-primary' : 'rounded-xl'}
    >
      <Card shadow="sm" data-testid="mutex-state">
        <CardHeader className="pb-2">
          <div className="flex w-full items-center justify-between">
            <div>
              <div className="font-semibold text-sm">
                {mutex.label || mutex.mutexId}
              </div>
              <div className="text-xs text-default-500 font-mono">{mutex.mutexId}</div>
            </div>
            <motion.div
              initial={{ scale: 0.8, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              transition={{ type: 'spring', stiffness: 300, damping: 25 }}
            >
              <Chip
                size="sm"
                variant="flat"
                color={mutex.isLocked ? 'default' : 'success'}
                data-testid="lock-status"
              >
                {mutex.isLocked ? 'Locked' : 'Unlocked'}
              </Chip>
            </motion.div>
          </div>
        </CardHeader>
        <CardBody className="pt-0">
          <div className="grid grid-cols-3 gap-3 text-xs">
            <div>
              <div className="text-default-500">Holder</div>
              <div className="font-semibold">
                {mutex.currentHolder ? (
                  <CoroutineName
                    id={mutex.currentHolder}
                    label={mutex.currentHolderLabel}
                    picker={picker}
                    selected={mutex.currentHolder === selectedCoroutineId}
                  />
                ) : (
                  'None'
                )}
              </div>
            </div>
            <div>
              <div className="text-default-500">Contention</div>
              <div className="font-semibold">{mutex.contentionCount}</div>
            </div>
            <div>
              <div className="text-default-500">Acquisitions</div>
              <div className="font-semibold">{mutex.lockAcquisitions}</div>
            </div>
          </div>
        </CardBody>
      </Card>
    </motion.div>
  )
}
