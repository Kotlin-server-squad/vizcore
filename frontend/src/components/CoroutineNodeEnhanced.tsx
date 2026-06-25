/**
 * Enhanced Coroutine Node Component
 * 
 * This shows how to enhance individual nodes in CoroutineTreeGraph
 * with dispatcher, thread, and time information.
 * 
 * USAGE: Add these enhancements to your existing TreeNodeComponent in CoroutineTreeGraph.tsx
 */

import { Chip, Tooltip } from '@heroui/react'
import { motion } from 'framer-motion'
import { FiCpu, FiZap, FiClock } from 'react-icons/fi'
import { getDispatcherBadgeProps, formatThreadName } from '@/lib/dispatcher-utils'
import { formatNanoTime } from '@/lib/utils'
import { isUserFrame } from '@/lib/source-frames'
import { toastSuccess } from '@/lib/toast'
import type { HierarchyNode } from '@/types/api'

/**
 * Delta S1 jump-to-code resolution (v1, LOCKED): copy `{fileName}:{lineNumber}`
 * to the clipboard and confirm with a success toast. The IDE deep-link strategy
 * can be swapped behind this handler later without changing the visual contract.
 */
export function copyFileLine(fileName: string, lineNumber: number | string): void {
  const target = `${fileName}:${lineNumber}`
  void navigator.clipboard?.writeText(target)
  toastSuccess(`Copied ${target}`)
}

/**
 * Delta S1 source reference for a suspension point's `file:line`.
 *
 * User-code frames (per {@link isUserFrame}) render as an accent jump-to-code
 * target (copy `file:line` + toast); library frames render as plain dimmed text
 * that inherits the call site's existing weight-400 dim class.
 */
export function SuspensionSourceRef({
  fn,
  fileName,
  lineNumber,
}: {
  fn: string | null | undefined
  fileName?: string | null
  lineNumber?: number | null
}) {
  if (!fileName) return null
  const label = `${fileName}:${lineNumber}`

  if (isUserFrame(fn)) {
    return (
      <span
        role="button"
        tabIndex={0}
        aria-label={`Jump to code: ${label}`}
        className="font-semibold text-primary cursor-pointer hover:underline"
        onClick={() => copyFileLine(fileName, lineNumber!)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            copyFileLine(fileName, lineNumber!)
          }
        }}
      >
        {label}
      </span>
    )
  }

  return <>{label}</>
}

interface EnhancedNodeBadgesProps {
  node: HierarchyNode
}

/**
 * Component showing dispatcher, thread, and time badges for a coroutine node
 * Add this to your existing node rendering in CoroutineTreeGraph
 */
export function EnhancedNodeBadges({ node }: EnhancedNodeBadgesProps) {
  return (
    <div className="flex flex-wrap gap-2 mt-2">
      {/* Dispatcher Badge */}
      {node.dispatcherName && (
        <Chip {...getDispatcherBadgeProps(node.dispatcherName)} startContent={<FiZap />}>
          {node.dispatcherName}
        </Chip>
      )}

      {/* Current Thread Badge */}
      {node.currentThreadId !== null && node.currentThreadId !== undefined && (
        <Chip size="sm" variant="flat" color="secondary" startContent={<FiCpu />}>
          {formatThreadName(node.currentThreadName, node.currentThreadId)}
        </Chip>
      )}

      {/* Time Metrics Badge */}
      {(node.activeTime !== undefined || node.suspendedTime !== undefined) && (
        <Tooltip
          content={
            <div className="p-2 space-y-1">
              {node.activeTime !== undefined && (
                <div className="text-xs">
                  <span className="text-success">Active:</span> {formatNanoTime(node.activeTime)}
                </div>
              )}
              {node.suspendedTime !== undefined && (
                <div className="text-xs">
                  <span className="text-warning">Suspended:</span> {formatNanoTime(node.suspendedTime)}
                </div>
              )}
            </div>
          }
        >
          <Chip size="sm" variant="flat" startContent={<FiClock />}>
            {formatNanoTime((node.activeTime || 0) + (node.suspendedTime || 0))}
          </Chip>
        </Tooltip>
      )}

      {/* Suspension Points Indicator */}
      {node.suspensionPoints && node.suspensionPoints.length > 0 && (
        <Tooltip
          content={
            <div className="p-2 max-w-xs">
              <div className="text-xs font-semibold mb-1">Suspension Points:</div>
              {node.suspensionPoints.map((point, i) => (
                <div key={i} className="text-xs mb-1">
                  <div className="font-mono">{point.function}</div>
                  <div className="text-default-400">
                    <SuspensionSourceRef
                      fn={point.function}
                      fileName={point.fileName}
                      lineNumber={point.lineNumber}
                    />{' '}
                    ({point.reason})
                  </div>
                </div>
              ))}
            </div>
          }
        >
          <Chip size="sm" variant="dot" color="warning">
            {node.suspensionPoints.length} suspension{node.suspensionPoints.length > 1 ? 's' : ''}
          </Chip>
        </Tooltip>
      )}
    </div>
  )
}

/**
 * Time metrics visualization bar
 * Shows active vs suspended time as a progress bar
 */
export function TimeMetricsBar({ node }: { node: HierarchyNode }) {
  const activeTime = node.activeTime || 0
  const suspendedTime = node.suspendedTime || 0
  const totalTime = activeTime + suspendedTime

  if (totalTime === 0) return null

  const activePercent = (activeTime / totalTime) * 100
  const suspendedPercent = (suspendedTime / totalTime) * 100

  return (
    <div className="mt-3 space-y-1">
      <div className="flex justify-between text-xs text-default-600">
        <span>Time Distribution</span>
        <span>{formatNanoTime(totalTime)}</span>
      </div>
      <div className="flex h-2 rounded-full overflow-hidden bg-default-100">
        <motion.div
          className="bg-success"
          initial={{ width: 0 }}
          animate={{ width: `${activePercent}%` }}
          transition={{ duration: 0.5, delay: 0.2 }}
          title={`Active: ${activePercent.toFixed(1)}%`}
        />
        <motion.div
          className="bg-warning"
          initial={{ width: 0 }}
          animate={{ width: `${suspendedPercent}%` }}
          transition={{ duration: 0.5, delay: 0.2 }}
          title={`Suspended: ${suspendedPercent.toFixed(1)}%`}
        />
      </div>
      <div className="flex justify-between text-xs text-default-500">
        <span className="text-success">Active: {activePercent.toFixed(1)}%</span>
        <span className="text-warning">Suspended: {suspendedPercent.toFixed(1)}%</span>
      </div>
    </div>
  )
}

/**
 * Example: Full enhanced node card
 * This shows all the enhancements in one place
 */
export function EnhancedCoroutineNodeCard({ 
  node, 
  onClick 
}: { 
  node: HierarchyNode
  onClick?: (id: string) => void
}) {
  return (
    <motion.div
      className="relative rounded-2xl border-2 bg-content1 p-6 shadow-lg cursor-pointer hover:shadow-2xl transition-all"
      whileHover={{ scale: 1.02 }}
      onClick={() => onClick?.(node.id)}
    >
      {/* Header */}
      <div className="mb-3 flex items-center gap-3">
        <div className="flex-1">
          <div className="text-lg font-bold">{node.name || node.id}</div>
          <div className="text-xs text-default-500">{node.state}</div>
        </div>
      </div>

      {/* Enhanced Badges */}
      <EnhancedNodeBadges node={node} />

      {/* Time Metrics */}
      <TimeMetricsBar node={node} />

      {/* Basic Info */}
      <div className="mt-3 space-y-1 text-xs text-default-600">
        <div>
          <span className="font-semibold">ID:</span> <code className="font-mono">{node.id}</code>
        </div>
        <div>
          <span className="font-semibold">Job:</span> <code className="font-mono">{node.jobId}</code>
        </div>
        <div>
          <span className="font-semibold">Scope:</span> <code className="font-mono">{node.scopeId}</code>
        </div>
      </div>
    </motion.div>
  )
}

