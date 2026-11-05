import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
import { motion } from 'framer-motion'
import type { MutexState } from '@/hooks/use-sync-events'

interface MutexStateIndicatorProps {
  mutex: MutexState
}

export function MutexStateIndicator({ mutex }: MutexStateIndicatorProps) {
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
      className="rounded-xl"
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
              <div className="font-mono font-semibold">
                {mutex.currentHolder
                  ? mutex.currentHolderLabel || mutex.currentHolder
                  : 'None'}
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
