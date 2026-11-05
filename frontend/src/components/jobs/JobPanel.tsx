import { Card, CardBody, Chip, Divider } from '@heroui/react'
import { motion } from 'framer-motion'
import { useJobEvents } from '@/hooks/use-job-events'
import { JobStateBadge } from './JobStateBadge'
import { WaitingForChildrenCard } from './WaitingForChildrenCard'
import { JobHierarchyView } from './JobHierarchyView'
import type { VizEvent } from '@/types/api'

interface JobPanelProps {
  sessionId: string
  /** The events the page shows (live, paused or at the replay cursor); the stored history when absent. */
  events?: readonly VizEvent[]
}

export function JobPanel({ sessionId, events }: JobPanelProps) {
  const { jobs, roots, waitingForChildren } = useJobEvents(sessionId, events)

  if (jobs.length === 0) {
    return (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.4 }}
      >
        <Card className="mt-2">
          <CardBody>
            <div className="text-center text-default-400 py-8" data-testid="jobs-empty">
              <p className="text-lg font-semibold mb-2">No Job Activity</p>
              <p className="text-sm">
                Job state changes and structured concurrency events will appear here.
              </p>
            </div>
          </CardBody>
        </Card>
      </motion.div>
    )
  }

  const active = jobs.filter((j) => j.isActive).length
  const completed = jobs.filter((j) => j.isCompleted).length
  const cancelled = jobs.filter((j) => j.isCancelled).length

  return (
    <motion.div
      className="space-y-4 mt-2"
      data-testid="job-panel"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.4 }}
    >
      {/* Summary */}
      <div className="grid grid-cols-4 gap-3">
        {[
          { value: jobs.length, label: 'Total Jobs', color: 'text-primary' },
          { value: active, label: 'Active', color: 'text-success' },
          { value: completed, label: 'Completed', color: 'text-primary' },
          { value: cancelled, label: 'Cancelled', color: 'text-warning' },
        ].map((stat, index) => (
          <motion.div
            key={stat.label}
            initial={{ scale: 0.8, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={{ type: 'spring', stiffness: 300, damping: 25, delay: index * 0.1 }}
          >
            <Card shadow="sm">
              <CardBody className="py-3">
                <div className={`text-2xl font-bold ${stat.color}`}>{stat.value}</div>
                <div className="text-xs text-default-500">{stat.label}</div>
              </CardBody>
            </Card>
          </motion.div>
        ))}
      </div>

      {/* WaitingForChildren cards */}
      {waitingForChildren.length > 0 && (
        <motion.div
          className="space-y-2"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.4, delay: 0.2 }}
        >
          <h3 className="text-sm font-semibold text-default-600">Structured Concurrency</h3>
          {waitingForChildren.map((evt, idx) => (
            <WaitingForChildrenCard key={`${evt.seq}-${idx}`} event={evt} index={idx} />
          ))}
        </motion.div>
      )}

      <Divider />

      {/* Job hierarchy */}
      <motion.div
        className="space-y-2"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.4, delay: 0.3 }}
      >
        <h3 className="text-sm font-semibold text-default-600">Job Hierarchy</h3>
        <Card shadow="sm">
          <CardBody>
            <JobHierarchyView jobs={jobs} roots={roots} />
          </CardBody>
        </Card>
      </motion.div>

      <Divider />

      {/* Individual job detail list */}
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-default-600">Job Details</h3>
        {jobs.map((job, index) => (
          <motion.div
            key={job.jobId}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3, delay: index * 0.05 }}
          >
            <Card shadow="sm">
              <CardBody>
                <div className="flex items-center justify-between mb-2">
                  <div>
                    <div className="font-semibold text-sm">{job.label || job.jobId}</div>
                    <div className="text-xs text-default-500 font-mono">{job.jobId}</div>
                  </div>
                  <div className="flex gap-2">
                    <JobStateBadge status={job.status} />
                    {job.childrenCount > 0 && (
                      <Chip size="sm" variant="bordered" color="default">
                        {job.childrenCount} children
                      </Chip>
                    )}
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-2 text-xs">
                  <div>
                    <span className="text-default-500">Coroutine: </span>
                    <span className="font-mono">{job.coroutineId}</span>
                  </div>
                  {job.parentCoroutineId && (
                    <div>
                      <span className="text-default-500">Parent: </span>
                      <span className="font-mono">{job.parentCoroutineId}</span>
                    </div>
                  )}
                </div>
              </CardBody>
            </Card>
          </motion.div>
        ))}
      </div>
    </motion.div>
  )
}
