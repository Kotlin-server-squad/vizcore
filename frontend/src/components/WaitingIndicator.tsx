import { HierarchyNode } from '../types/api'
import { cn } from '../lib/utils'

interface WaitingIndicatorProps {
  node: HierarchyNode
  className?: string
}

/**
 * Visual indicator for coroutines waiting for children to complete.
 * Shows progress and active children count.
 */
export function WaitingIndicator({ node, className }: WaitingIndicatorProps) {
  if (node.state !== 'WAITING_FOR_CHILDREN') {
    return null
  }

  const totalChildren = node.children.length
  const activeChildren = node.activeChildrenCount || 0
  const completedChildren = totalChildren - activeChildren
  
  // Calculate progress (0-100%)
  const progress = totalChildren > 0 ? (completedChildren / totalChildren) * 100 : 0

  return (
    <div className={cn('flex flex-col gap-2', className)}>
      {/* Progress Bar */}
      <div className="relative w-full h-2 bg-default-200 rounded-full overflow-hidden">
        <div
          className="absolute top-0 left-0 h-full bg-blue-500 transition-all duration-300 ease-out"
          style={{ width: `${progress}%` }}
        />
      </div>

      {/* Status Text */}
      <div className="flex items-center justify-between text-xs">
        <span className="text-blue-600 dark:text-blue-400 font-medium">
          Waiting for {activeChildren} {activeChildren === 1 ? 'child' : 'children'}
        </span>
        <span className="text-default-500">
          {completedChildren} / {totalChildren} completed
        </span>
      </div>

      {/* Active Children Badge */}
      {activeChildren > 0 && (
        <div className="flex items-center gap-1 text-xs text-default-500">
          <svg
            className="w-3 h-3 animate-spin text-blue-500"
            xmlns="http://www.w3.org/2000/svg"
            fill="none"
            viewBox="0 0 24 24"
          >
            <circle
              className="opacity-25"
              cx="12"
              cy="12"
              r="10"
              stroke="currentColor"
              strokeWidth="4"
            />
            <path
              className="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
            />
          </svg>
          <span>Still running...</span>
        </div>
      )}
    </div>
  )
}

/**
 * Compact badge version for inline display
 */
export function WaitingBadge({ node }: { node: HierarchyNode }) {
  if (node.state !== 'WAITING_FOR_CHILDREN') {
    return null
  }

  const activeChildren = node.activeChildrenCount || 0

  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium rounded-full bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300">
      <svg
        className="w-3 h-3 animate-pulse"
        fill="currentColor"
        viewBox="0 0 20 20"
      >
        <path
          fillRule="evenodd"
          d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z"
          clipRule="evenodd"
        />
      </svg>
      Waiting: {activeChildren} {activeChildren === 1 ? 'child' : 'children'}
    </span>
  )
}

