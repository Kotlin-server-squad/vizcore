import { Card, CardBody } from '@heroui/react'
import { motion } from 'framer-motion'
import type { BackendTimingReport } from '@/types/api'

interface TimingReportViewProps {
  timing: BackendTimingReport
}

function formatMs(ms: number): string {
  if (ms >= 1000) {
    return `${(ms / 1000).toFixed(2)}s`
  }
  return `${ms}ms`
}

export function TimingReportView({ timing }: TimingReportViewProps) {
  const coroutineEntries = Object.entries(timing.coroutineDurations).sort(
    ([, a], [, b]) => b - a,
  )
  const maxDuration = Math.max(1, ...coroutineEntries.map(([, d]) => d))

  const suspensionEntries = Object.entries(timing.suspensionDurations)

  return (
    <motion.div
      className="space-y-4"
      data-testid="timing-report"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.4 }}
    >
      {/* Total Duration card */}
      <motion.div
        initial={{ scale: 0.8, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={{ type: 'spring', stiffness: 300, damping: 25 }}
      >
        <Card shadow="sm">
          <CardBody className="py-2">
            <div className="text-lg font-semibold text-primary">
              {formatMs(timing.totalDuration)}
            </div>
            <div className="text-xs text-default-500">Total Duration</div>
          </CardBody>
        </Card>
      </motion.div>

      {/* Coroutine Durations — bar chart */}
      {coroutineEntries.length > 0 && (
        <motion.div
          className="space-y-2"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.4, delay: 0.2 }}
        >
          <div className="text-sm font-semibold text-default-600">Coroutine Durations</div>
          <div className="space-y-1">
            {coroutineEntries.map(([coroutineId, duration], index) => {
              const widthPercent = (duration / maxDuration) * 100
              return (
                <motion.div
                  key={coroutineId}
                  className="flex items-center gap-2 text-xs"
                  data-testid="timing-coroutine-row"
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ duration: 0.3, delay: 0.2 + index * 0.05 }}
                >
                  <div className="w-32 text-right text-default-700 shrink-0 font-mono truncate">
                    {coroutineId}
                  </div>
                  <div className="flex-1 h-4 bg-default-100 rounded overflow-hidden">
                    <motion.div
                      className="h-full bg-primary rounded"
                      initial={{ width: 0 }}
                      animate={{ width: `${widthPercent}%` }}
                      transition={{ duration: 0.8, ease: 'easeOut', delay: 0.2 + index * 0.05 }}
                    />
                  </div>
                  <span className="text-xs text-primary shrink-0 font-mono">
                    {formatMs(duration)}
                  </span>
                </motion.div>
              )
            })}
          </div>
        </motion.div>
      )}

      {/* Suspension Durations */}
      {suspensionEntries.length > 0 && (
        <motion.div
          className="space-y-2"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.4, delay: 0.4 }}
        >
          <div className="text-sm font-semibold text-default-600">Suspension Durations</div>
          <div className="space-y-1">
            {suspensionEntries.map(([coroutineId, durations]) => {
              const count = durations.length
              const maxSuspension = count > 0 ? Math.max(...durations) : 0
              return (
                <div key={coroutineId} className="flex items-center gap-2 text-xs">
                  <span className="font-mono text-xs text-default-700 truncate">{coroutineId}</span>
                  <span className="text-xs text-default-500">
                    {count} suspension{count !== 1 ? 's' : ''}, max: {formatMs(maxSuspension)}
                  </span>
                </div>
              )
            })}
          </div>
        </motion.div>
      )}
    </motion.div>
  )
}
