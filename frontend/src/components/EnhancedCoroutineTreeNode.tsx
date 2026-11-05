import { HierarchyNode } from '../types/api'
import { cn } from '../lib/utils'
import { JobStatusDisplay } from './JobStatusDisplay'
import { WaitingIndicator } from './WaitingIndicator'
import { JobInfoCard } from './JobInfoCard'

interface EnhancedCoroutineTreeNodeProps {
  node: HierarchyNode
  allNodes: Map<string, HierarchyNode>
  level?: number
  onNodeClick?: (node: HierarchyNode) => void
  selectedNodeId?: string
  showJobInfo?: boolean  // NEW: Show job properties
}

/**
 * Enhanced tree node that visualizes coroutine hierarchy with
 * structured concurrency indicators (waiting for children)
 */
export function EnhancedCoroutineTreeNode({
  node,
  allNodes,
  level = 0,
  onNodeClick,
  selectedNodeId,
  showJobInfo = false,
}: EnhancedCoroutineTreeNodeProps) {
  const isSelected = selectedNodeId === node.id
  const hasChildren = node.children.length > 0
  const isWaiting = node.state === 'WAITING_FOR_CHILDREN'

  // Get child nodes
  const childNodes = (node.children as string[])
    .map(childId => allNodes.get(childId))
    .filter((n): n is HierarchyNode => n !== undefined)

  return (
    <div className="relative">
      {/* Current Node */}
      <div
        className={cn(
          'group relative rounded-lg border-2 transition-all duration-200',
          isSelected
            ? 'border-blue-500 bg-blue-50 dark:bg-blue-900/20'
            : 'border-divider bg-content1',
          'hover:border-default-300 hover:shadow-md',
          'cursor-pointer'
        )}
        style={{ marginLeft: `${level * 24}px` }}
        onClick={() => onNodeClick?.(node)}
      >
        <div className="p-3">
          {/* Header: Label and Status */}
          <div className="flex items-center justify-between mb-2">
            <div className="flex items-center gap-2">
              {/* Tree Connector */}
              {level > 0 && (
                <div className="absolute left-0 top-0 bottom-0 w-0.5 bg-default-300" />
              )}
              
              {/* Label */}
              <span className="font-mono text-sm font-semibold text-foreground">
                {node.name}
              </span>

              {/* Children Count Badge */}
              {hasChildren && (
                <span className="px-2 py-0.5 text-xs font-medium rounded-full bg-default-100 text-default-600">
                  {node.children.length} {node.children.length === 1 ? 'child' : 'children'}
                </span>
              )}
            </div>

            {/* Job Status */}
            <JobStatusDisplay node={node} />
          </div>

          {/* Waiting Indicator */}
          {isWaiting && (
            <div className="mt-3 pt-3 border-t border-divider">
              <WaitingIndicator node={node} />
            </div>
          )}

          {/* Job Info */}
          {showJobInfo && (
            <div className="mt-3 pt-3 border-t border-divider">
              <JobInfoCard node={node} compact />
            </div>
          )}

          {/* Additional Info */}
          <div className="mt-2 flex items-center gap-4 text-xs text-default-500">
            {node.currentThreadName && (
              <div className="flex items-center gap-1">
                <ThreadIcon className="w-3 h-3" />
                <span>{node.currentThreadName}</span>
              </div>
            )}
            {node.dispatcherName && (
              <div className="flex items-center gap-1">
                <DispatcherIcon className="w-3 h-3" />
                <span>{node.dispatcherName}</span>
              </div>
            )}
            <div className="flex items-center gap-1">
              <ClockIcon className="w-3 h-3" />
              <span>{formatDuration(node)}</span>
            </div>
          </div>

          {/* Active Children Highlight */}
          {isWaiting && node.activeChildrenIds && node.activeChildrenIds.length > 0 && (
            <div className="mt-3 pt-3 border-t border-blue-200 dark:border-blue-700">
              <div className="text-xs font-medium text-blue-600 dark:text-blue-400 mb-1">
                Active Children:
              </div>
              <div className="flex flex-wrap gap-1">
                {node.activeChildrenIds.map(childId => {
                  const child = allNodes.get(childId)
                  return child ? (
                    <span
                      key={childId}
                      className="px-2 py-0.5 text-xs font-mono rounded bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300"
                    >
                      {child.name}
                    </span>
                  ) : null
                })}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Child Nodes */}
      {hasChildren && (
        <div className="mt-2 space-y-2">
          {childNodes.map(child => (
            <EnhancedCoroutineTreeNode
              key={child.id}
              node={child}
              allNodes={allNodes}
              level={level + 1}
              onNodeClick={onNodeClick}
              selectedNodeId={selectedNodeId}
              showJobInfo={showJobInfo}
            />
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * Format duration from nanos
 */
function formatDuration(node: HierarchyNode): string {
  const endTime = node.completedAtNanos || Date.now() * 1000000 // Current time in nanos if not completed
  const durationNanos = endTime - node.createdAtNanos
  const durationMs = durationNanos / 1000000

  if (durationMs < 1000) {
    return `${Math.round(durationMs)}ms`
  }
  
  return `${(durationMs / 1000).toFixed(2)}s`
}

// Icon Components
function ThreadIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth={2}
        d="M13 10V3L4 14h7v7l9-11h-7z"
      />
    </svg>
  )
}

function DispatcherIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth={2}
        d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"
      />
    </svg>
  )
}

function ClockIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      fill="none"
      stroke="currentColor"
      viewBox="0 0 24 24"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth={2}
        d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
      />
    </svg>
  )
}

