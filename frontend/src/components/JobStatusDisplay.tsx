import { HierarchyNode, CoroutineState } from '../types/api'
import { cn } from '../lib/utils'
import { WaitingBadge } from './WaitingIndicator'

interface JobStatusDisplayProps {
  node: HierarchyNode
  showDetails?: boolean
  className?: string
}

/**
 * Displays the current status of a coroutine job with visual indicators
 */
export function JobStatusDisplay({ 
  node, 
  showDetails = false, 
  className 
}: JobStatusDisplayProps) {
  const stateConfig = getStateConfig(node.state)

  return (
    <div className={cn('flex items-center gap-2', className)}>
      {/* State Icon */}
      <div
        className={cn(
          'flex items-center justify-center w-6 h-6 rounded-full',
          stateConfig.bgColor
        )}
      >
        {stateConfig.icon}
      </div>

      {/* State Label */}
      <span
        className={cn(
          'text-sm font-medium',
          stateConfig.textColor
        )}
      >
        {stateConfig.label}
      </span>

      {/* Waiting Badge */}
      {node.state === 'WAITING_FOR_CHILDREN' && (
        <WaitingBadge node={node} />
      )}

      {/* Additional Details */}
      {showDetails && (
        <div className="flex items-center gap-2 text-xs text-default-500">
          {node.currentThreadName && (
            <span className="flex items-center gap-1">
              <ThreadIcon className="w-3 h-3" />
              {node.currentThreadName}
            </span>
          )}
          {node.children.length > 0 && (
            <span className="flex items-center gap-1">
              <ChildrenIcon className="w-3 h-3" />
              {node.children.length} {node.children.length === 1 ? 'child' : 'children'}
            </span>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * Get visual configuration for each state
 */
function getStateConfig(state: CoroutineState) {
  switch (state) {
    case 'CREATED':
      return {
        label: 'Created',
        icon: <CircleIcon />,
        bgColor: 'bg-default-200',
        textColor: 'text-default-600',
      }
    case 'ACTIVE':
      return {
        label: 'Running',
        icon: <PlayIcon />,
        bgColor: 'bg-green-100 dark:bg-green-900',
        textColor: 'text-green-700 dark:text-green-300',
      }
    case 'SUSPENDED':
      return {
        label: 'Suspended',
        icon: <PauseIcon />,
        bgColor: 'bg-yellow-100 dark:bg-yellow-900',
        textColor: 'text-yellow-700 dark:text-yellow-300',
      }
    case 'WAITING_FOR_CHILDREN':
      return {
        label: 'Waiting',
        icon: <ClockIcon />,
        bgColor: 'bg-blue-100 dark:bg-blue-900',
        textColor: 'text-blue-700 dark:text-blue-300',
      }
    case 'COMPLETED':
      return {
        label: 'Completed',
        icon: <CheckIcon />,
        bgColor: 'bg-emerald-100 dark:bg-emerald-900',
        textColor: 'text-emerald-700 dark:text-emerald-300',
      }
    case 'CANCELLED':
      return {
        label: 'Cancelled',
        icon: <XIcon />,
        bgColor: 'bg-orange-100 dark:bg-orange-900',
        textColor: 'text-orange-700 dark:text-orange-300',
      }
    case 'FAILED':
      return {
        label: 'Failed',
        icon: <AlertIcon />,
        bgColor: 'bg-red-100 dark:bg-red-900',
        textColor: 'text-red-700 dark:text-red-300',
      }
    default:
      return {
        label: 'Unknown',
        icon: <CircleIcon />,
        bgColor: 'bg-default-200',
        textColor: 'text-default-600',
      }
  }
}

// Icon Components
function CircleIcon() {
  return (
    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
      <circle cx="10" cy="10" r="4" />
    </svg>
  )
}

function PlayIcon() {
  return (
    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
      <path d="M6.3 2.841A1.5 1.5 0 004 4.11V15.89a1.5 1.5 0 002.3 1.269l9.344-5.89a1.5 1.5 0 000-2.538L6.3 2.84z" />
    </svg>
  )
}

function PauseIcon() {
  return (
    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
      <path d="M5.75 3a.75.75 0 00-.75.75v12.5c0 .414.336.75.75.75h1.5a.75.75 0 00.75-.75V3.75A.75.75 0 007.25 3h-1.5zM12.75 3a.75.75 0 00-.75.75v12.5c0 .414.336.75.75.75h1.5a.75.75 0 00.75-.75V3.75a.75.75 0 00-.75-.75h-1.5z" />
    </svg>
  )
}

function ClockIcon() {
  return (
    <svg className="w-3 h-3 animate-spin" fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z"
        clipRule="evenodd"
      />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
        clipRule="evenodd"
      />
    </svg>
  )
}

function XIcon() {
  return (
    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
        clipRule="evenodd"
      />
    </svg>
  )
}

function AlertIcon() {
  return (
    <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z"
        clipRule="evenodd"
      />
    </svg>
  )
}

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

function ChildrenIcon({ className }: { className?: string }) {
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
        d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z"
      />
    </svg>
  )
}

