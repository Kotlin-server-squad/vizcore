import { HierarchyNode } from '../types/api'
import { cn } from '../lib/utils'

interface JobInfoCardProps {
  node: HierarchyNode
  className?: string
  compact?: boolean
}

/**
 * Displays Job properties for a coroutine.
 * Shows the relationship between Job state and Coroutine state.
 */
export function JobInfoCard({ node, className, compact = false }: JobInfoCardProps) {
  // Derive job state from coroutine state
  const jobState = deriveJobState(node.state)

  if (compact) {
    return <JobInfoCompact node={node} jobState={jobState} className={className} />
  }

  return (
    <div className={cn('rounded-lg border border-divider bg-content1 p-4', className)}>
      {/* Header */}
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-foreground">
          Job Status
        </h3>
        <span className="text-xs font-mono text-default-500">
          {node.jobId}
        </span>
      </div>

      {/* Job Properties */}
      <div className="space-y-2">
        {/* isActive */}
        <JobProperty
          label="isActive"
          value={jobState.isActive}
          description="Job is currently active and running"
        />

        {/* isCompleted */}
        <JobProperty
          label="isCompleted"
          value={jobState.isCompleted}
          description="Job has finished (success or failure)"
        />

        {/* isCancelled */}
        <JobProperty
          label="isCancelled"
          value={jobState.isCancelled}
          description="Job was cancelled"
        />

        {/* Children Count */}
        <div className="pt-2 border-t border-divider">
          <div className="flex items-center justify-between">
            <span className="text-xs font-mono text-default-500">
              childrenCount
            </span>
            <span className="text-sm font-bold text-blue-600 dark:text-blue-400">
              {node.children.length}
            </span>
          </div>
          {node.activeChildrenCount !== undefined && node.activeChildrenCount > 0 && (
            <div className="flex items-center justify-between mt-1">
              <span className="text-xs font-mono text-default-500">
                active children
              </span>
              <span className="text-sm font-bold text-orange-600 dark:text-orange-400">
                {node.activeChildrenCount}
              </span>
            </div>
          )}
        </div>
      </div>

      {/* Lifecycle Explanation */}
      {node.state === 'WAITING_FOR_CHILDREN' && (
        <div className="mt-3 pt-3 border-t border-blue-200 dark:border-blue-700">
          <div className="text-xs text-blue-600 dark:text-blue-400">
            <div className="font-semibold mb-1">📘 Job Lifecycle:</div>
            <div className="space-y-0.5 text-blue-600/80 dark:text-blue-400/80">
              <div>• Coroutine body: <span className="font-semibold">COMPLETED ✓</span></div>
              <div>• Job state: <span className="font-semibold">ACTIVE (waiting)</span></div>
              <div>• Will complete when: <span className="font-semibold">{node.activeChildrenCount} children finish</span></div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * Compact inline version
 */
function JobInfoCompact({ 
  node, 
  jobState, 
  className 
}: { 
  node: HierarchyNode
  jobState: JobState
  className?: string 
}) {
  return (
    <div className={cn('flex items-center gap-2 text-xs', className)}>
      <span className="font-mono text-default-500">Job:</span>
      
      <JobBadge label="active" value={jobState.isActive} />
      <JobBadge label="completed" value={jobState.isCompleted} />
      <JobBadge label="cancelled" value={jobState.isCancelled} />
      
      {node.children.length > 0 && (
        <span className="px-1.5 py-0.5 rounded bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300 font-semibold">
          {node.children.length} children
        </span>
      )}
    </div>
  )
}

/**
 * Job property row
 */
function JobProperty({ 
  label, 
  value, 
  description 
}: { 
  label: string
  value: boolean
  description: string 
}) {
  return (
    <div className="group relative">
      <div className="flex items-center justify-between">
        <span className="text-xs font-mono text-default-500">
          {label}
        </span>
        <span className={cn(
          'text-sm font-bold',
          value 
            ? 'text-green-600 dark:text-green-400' 
            : 'text-default-400'
        )}>
          {value ? 'true' : 'false'}
        </span>
      </div>
      
      {/* Tooltip */}
      <div className="absolute left-0 bottom-full mb-2 hidden group-hover:block z-10 w-64 p-2 text-xs bg-foreground text-background rounded shadow-lg">
        {description}
      </div>
    </div>
  )
}

/**
 * Job badge for compact view
 */
function JobBadge({ label, value }: { label: string; value: boolean }) {
  return (
    <span className={cn(
      'px-1.5 py-0.5 rounded text-xs font-medium',
      value
        ? 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300'
        : 'bg-default-100 text-default-500'
    )}>
      {label}
    </span>
  )
}

/**
 * Derive job state from coroutine state
 */
function deriveJobState(coroutineState: string): JobState {
  switch (coroutineState) {
    case 'CREATED':
    case 'ACTIVE':
    case 'SUSPENDED':
    case 'WAITING_FOR_CHILDREN':
      return {
        isActive: true,
        isCompleted: false,
        isCancelled: false,
      }
    case 'COMPLETED':
      return {
        isActive: false,
        isCompleted: true,
        isCancelled: false,
      }
    case 'CANCELLED':
      return {
        isActive: false,
        isCompleted: false,
        isCancelled: true,
      }
    case 'FAILED':
      return {
        isActive: false,
        isCompleted: true,  // Job completed (with failure)
        isCancelled: false,
      }
    default:
      return {
        isActive: false,
        isCompleted: false,
        isCancelled: false,
      }
  }
}

interface JobState {
  isActive: boolean
  isCompleted: boolean
  isCancelled: boolean
}

/**
 * Visual explanation of Job vs Coroutine lifecycle
 */
export function JobLifecycleExplainer() {
  return (
    <div className="rounded-lg border border-blue-200 dark:border-blue-700 bg-blue-50 dark:bg-blue-900/20 p-4">
      <h4 className="text-sm font-semibold text-blue-900 dark:text-blue-100 mb-3">
        📘 Understanding Job vs Coroutine
      </h4>
      
      <div className="space-y-3 text-sm text-blue-800 dark:text-blue-200">
        <div>
          <div className="font-semibold mb-1">Job Properties:</div>
          <ul className="space-y-1 ml-4 text-xs">
            <li>• <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">isActive</code> - Job is running or waiting</li>
            <li>• <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">isCompleted</code> - Job has finished (all children done)</li>
            <li>• <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">isCancelled</code> - Job was cancelled</li>
            <li>• <code className="bg-blue-100 dark:bg-blue-800 px-1 rounded">children</code> - Child jobs count</li>
          </ul>
        </div>

        <div>
          <div className="font-semibold mb-1">Key Insight:</div>
          <div className="text-xs bg-blue-100 dark:bg-blue-800 p-2 rounded">
            When coroutine body finishes, <code>isActive = true</code> but coroutine state = 
            <code className="font-bold"> WAITING_FOR_CHILDREN</code>. The Job doesn't complete 
            until all children finish!
          </div>
        </div>

        <div>
          <div className="font-semibold mb-1">Lifecycle Flow:</div>
          <div className="text-xs space-y-1">
            <div>1️⃣ <span className="font-semibold">Created</span> → isActive=false, isCompleted=false</div>
            <div>2️⃣ <span className="font-semibold">Started</span> → isActive=true</div>
            <div>3️⃣ <span className="font-semibold">Body Done</span> → isActive=true (still waiting!)</div>
            <div>4️⃣ <span className="font-semibold">Children Done</span> → isActive=false, isCompleted=true</div>
          </div>
        </div>
      </div>
    </div>
  )
}

