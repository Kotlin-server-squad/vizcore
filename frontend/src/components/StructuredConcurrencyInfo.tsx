import { Card, CardBody, CardHeader } from '@heroui/react'
import { motion } from 'framer-motion'
import { FiInfo, FiAlertTriangle, FiCheckCircle, FiClock } from 'react-icons/fi'

export function StructuredConcurrencyInfo() {
  return (
    <Card className="mb-4">
      <CardHeader className="flex items-center gap-2">
        <FiInfo className="h-5 w-5 text-primary" />
        <h3 className="text-lg font-semibold">Structured Concurrency Behavior</h3>
      </CardHeader>
      <CardBody className="space-y-3 text-sm">
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.1 }}
          className="flex items-start gap-3 rounded-lg bg-primary/10 p-3"
        >
          <FiClock className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary" />
          <div>
            <div className="font-semibold text-primary">WAITING_FOR_CHILDREN State</div>
            <div className="text-xs text-default-600 mt-1">
              When a parent coroutine's body finishes but it has child coroutines still running,
              it enters <span className="font-mono bg-default-100 px-1 rounded">WAITING_FOR_CHILDREN</span> state.
              This demonstrates how structured concurrency ensures parents wait for their children.
            </div>
          </div>
        </motion.div>

        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.2 }}
          className="flex items-start gap-3 rounded-lg bg-success/10 p-3"
        >
          <FiCheckCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-success" />
          <div>
            <div className="font-semibold text-success">Normal Completion</div>
            <div className="text-xs text-default-600 mt-1">
              A coroutine only reaches <span className="font-mono bg-default-100 px-1 rounded">COMPLETED</span> state
              when both its own code AND all child coroutines have successfully finished.
            </div>
          </div>
        </motion.div>

        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.3 }}
          className="flex items-start gap-3 rounded-lg bg-danger/10 p-3"
        >
          <FiAlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-danger" />
          <div>
            <div className="font-semibold text-danger">Failure Propagation</div>
            <div className="text-xs text-default-600 mt-1">
              When a child coroutine throws an exception, it enters <span className="font-mono bg-default-100 px-1 rounded">FAILED</span> state.
              The exception propagates to the parent, which gets <span className="font-mono bg-default-100 px-1 rounded">FAILED</span> (completes
              exceptionally), and all sibling coroutines are also cancelled. This is the <strong>power of structured concurrency</strong>:
              failures don't leak, they're handled in a predictable, hierarchical way.
            </div>
          </div>
        </motion.div>

        <div className="mt-3 rounded-lg border border-default-200 p-3">
          <div className="text-xs font-semibold text-default-700 mb-2">Visual Indicators:</div>
          <div className="grid grid-cols-2 gap-2 text-xs">
            <div className="flex items-center gap-2">
              <span className="inline-block h-2 w-2 rounded-full bg-primary animate-pulse"></span>
              <span>ACTIVE / WAITING_FOR_CHILDREN</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="inline-block h-2 w-2 rounded-full bg-success"></span>
              <span>COMPLETED</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="inline-block h-2 w-2 rounded-full bg-danger"></span>
              <span>FAILED</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="inline-block h-2 w-2 rounded-full bg-warning"></span>
              <span>CANCELLED</span>
            </div>
          </div>
        </div>
      </CardBody>
    </Card>
  )
}

