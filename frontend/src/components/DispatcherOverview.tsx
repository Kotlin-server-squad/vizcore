import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
import { useThreadLanesByDispatcher } from '@/hooks/use-thread-activity'
import { FiCpu, FiLayers, FiZap, FiAlertCircle } from 'react-icons/fi'
import type { DispatcherInfo } from '@/types/api'

interface DispatcherOverviewProps {
  sessionId: string
  /**
   * Forwarded to the shared ['thread-activity', sessionId] query (WR-15):
   * while the SSE stream drives updates, polling falls back to the slow 5s
   * interval instead of re-arming the legacy 2s poll on the shared key.
   */
  isLive?: boolean
}

export function DispatcherOverview({ sessionId, isLive = false }: DispatcherOverviewProps) {
  const { dispatcherInfo, isLoading } = useThreadLanesByDispatcher(sessionId, isLive)

  if (isLoading) {
    return (
      <Card>
        <CardBody>
          <div className="text-center text-default-400 py-4">Loading dispatchers...</div>
        </CardBody>
      </Card>
    )
  }

  if (dispatcherInfo.length === 0) {
    return (
      <Card>
        <CardBody>
          <div className="text-center text-default-400 py-4">No dispatcher data available</div>
        </CardBody>
      </Card>
    )
  }

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      {dispatcherInfo.map(dispatcher => (
        <DispatcherCard key={dispatcher.id} dispatcher={dispatcher} />
      ))}
    </div>
  )
}

function DispatcherCard({ dispatcher }: { dispatcher: DispatcherInfo }) {
  const color = getDispatcherColor(dispatcher.name)
  const icon = getDispatcherIcon(dispatcher.name)
  const description = getDispatcherDescription(dispatcher.name)

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between w-full">
          <div className="flex items-center gap-3">
            <div className={`p-2 rounded-lg bg-${color}/10 text-${color}`}>
              {icon}
            </div>
            <div>
              <h4 className="font-semibold">{dispatcher.name}</h4>
              <p className="text-xs text-default-500">{description}</p>
            </div>
          </div>
          <Chip size="sm" variant="flat" color={color}>
            {dispatcher.name}
          </Chip>
        </div>
      </CardHeader>
      <CardBody>
        <div className="space-y-4">
          {/* Thread Pool Size */}
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2 text-sm text-default-600">
              <FiCpu className="w-4 h-4" />
              <span>Thread Pool Size</span>
            </div>
            <span className="font-semibold text-lg">{dispatcher.threadIds.length}</span>
          </div>

          {/* Thread IDs */}
          <div>
            <div className="text-xs text-default-500 mb-2">Thread IDs:</div>
            <div className="flex flex-wrap gap-1">
              {dispatcher.threadIds.map(threadId => (
                <Chip key={threadId} size="sm" variant="flat">
                  {threadId}
                </Chip>
              ))}
            </div>
          </div>

          {/* Queue Depth */}
          {dispatcher.queueDepth != null && (
            <div className={`p-3 rounded-lg ${dispatcher.queueDepth > 0 ? 'bg-warning/10 border border-warning/20' : 'bg-default-100'}`}>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  {dispatcher.queueDepth > 0 ? (
                    <FiAlertCircle className="w-4 h-4 text-warning" />
                  ) : (
                    <FiZap className="w-4 h-4 text-success" />
                  )}
                  <span className="text-sm font-semibold">Queue Depth</span>
                </div>
                <span className={`text-lg font-bold ${dispatcher.queueDepth > 0 ? 'text-warning' : 'text-success'}`}>
                  {dispatcher.queueDepth}
                </span>
              </div>
              {dispatcher.queueDepth > 0 && (
                <div className="text-xs text-default-600 mt-1">
                  {dispatcher.queueDepth} {dispatcher.queueDepth === 1 ? 'task' : 'tasks'} waiting
                </div>
              )}
            </div>
          )}

          {/* Dispatcher ID */}
          <div className="text-xs text-default-400">
            ID: <code className="font-mono">{dispatcher.id}</code>
          </div>
        </div>
      </CardBody>
    </Card>
  )
}

function getDispatcherColor(name: string): 'primary' | 'secondary' | 'success' | 'warning' {
  const colors: Record<string, 'primary' | 'secondary' | 'success' | 'warning'> = {
    'Default': 'primary',
    'IO': 'secondary',
    'Main': 'success',
    'Unconfined': 'warning',
  }
  return colors[name] || 'primary'
}

function getDispatcherIcon(name: string): React.ReactNode {
  const icons: Record<string, React.ReactNode> = {
    'Default': <FiCpu className="w-5 h-5" />,
    'IO': <FiLayers className="w-5 h-5" />,
    'Main': <FiZap className="w-5 h-5" />,
    'Unconfined': <FiAlertCircle className="w-5 h-5" />,
  }
  return icons[name] || <FiCpu className="w-5 h-5" />
}

function getDispatcherDescription(name: string): string {
  const descriptions: Record<string, string> = {
    'Default': 'CPU-bound work, computational tasks',
    'IO': 'I/O-bound work, network & file operations',
    'Main': 'UI thread, main application thread',
    'Unconfined': 'Runs in caller thread, no thread switching',
  }
  return descriptions[name] || 'Custom dispatcher'
}

