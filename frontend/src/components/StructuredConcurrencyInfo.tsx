import { useState } from 'react'
import { motion } from 'framer-motion'
import { FiAlertTriangle, FiCheckCircle, FiChevronDown, FiChevronRight, FiClock, FiInfo } from 'react-icons/fi'

/** localStorage key for whether "How to read the tree" is open; remembered per browser. */
export const HOW_TO_READ_KEY = 'vizcore.howToReadTree'

function readOpen(): boolean {
  try {
    return localStorage.getItem(HOW_TO_READ_KEY) === 'open'
  } catch {
    return false
  }
}

/**
 * How to read the tree (spec Part 1): the structured-concurrency explanation and
 * the colour legend, folded into one line that is closed by default, so the
 * tree is the first thing on the page. Opening or closing it is remembered.
 */
export function StructuredConcurrencyInfo() {
  const [open, setOpen] = useState(readOpen)

  const toggle = () => {
    const next = !open
    setOpen(next)
    try {
      localStorage.setItem(HOW_TO_READ_KEY, next ? 'open' : 'closed')
    } catch {
      // Private mode or storage full: the fold still works for this visit.
    }
  }

  return (
    <div className="rounded-medium border border-default-200 bg-content1">
      <button
        type="button"
        aria-expanded={open}
        aria-controls="how-to-read-tree"
        onClick={toggle}
        className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
      >
        {open ? <FiChevronDown className="h-4 w-4" /> : <FiChevronRight className="h-4 w-4" />}
        <FiInfo className="h-4 w-4 text-primary" />
        <span className="font-semibold">How to read the tree</span>
        {!open && (
          <span className="text-default-500">— states, colours, and how failures spread</span>
        )}
      </button>
      {open && (
        <div id="how-to-read-tree" className="space-y-3 px-3 pb-3 text-sm">
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
                <span>Active / Waiting for children</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="inline-block h-2 w-2 rounded-full bg-warning"></span>
                <span>Suspended</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="inline-block h-2 w-2 rounded-full bg-success"></span>
                <span>Completed</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="inline-block h-2 w-2 rounded-full bg-danger"></span>
                <span>Failed</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="inline-block h-2 w-2 rounded-full bg-warning opacity-60"></span>
                <span>Cancelled</span>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
