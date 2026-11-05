import { Chip } from '@heroui/react'
import { motion, AnimatePresence } from 'framer-motion'
import { CoroutineName, type CoroutinePicker } from './CoroutineName'

interface WaitQueueEntry {
  id: string
  label: string | null
}

interface WaitQueueListProps {
  queue: WaitQueueEntry[]
  title?: string
  /** Selects a waiter, when the page can (S-2). */
  picker?: CoroutinePicker
  /** The page's selected coroutine, marked in the queue. */
  selectedId?: string | null
}

export function WaitQueueList({ queue, title = 'Wait Queue', picker, selectedId = null }: WaitQueueListProps) {
  if (queue.length === 0) {
    return (
      <div className="text-xs text-default-400" data-testid="wait-queue-empty">
        No coroutines waiting
      </div>
    )
  }

  return (
    <div className="space-y-1" data-testid="wait-queue-list">
      <div className="text-xs text-default-500 font-semibold">{title} ({queue.length})</div>
      <div className="space-y-1 max-h-32 overflow-y-auto">
        <AnimatePresence mode="popLayout">
          {queue.map((entry, idx) => (
            <motion.div
              key={`${entry.id}-${idx}`}
              className="flex items-center gap-2 rounded bg-default-50 px-2 py-1 text-xs"
              data-testid="wait-queue-entry"
              initial={{ opacity: 0, y: -10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 10 }}
              transition={{ duration: 0.2, delay: idx * 0.03 }}
            >
              <Chip size="sm" variant="flat" color="warning">#{idx + 1}</Chip>
              <span className="truncate">
                <CoroutineName id={entry.id} label={entry.label} picker={picker} selected={entry.id === selectedId} />
              </span>
            </motion.div>
          ))}
        </AnimatePresence>
      </div>
    </div>
  )
}
