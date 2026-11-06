import { useState } from 'react'
import { Card, CardBody, CardHeader, Chip, Divider, Button } from '@heroui/react'
import { motion } from 'framer-motion'
import { FiGrid, FiActivity } from 'react-icons/fi'
import { useFlowEvents } from '@/hooks/use-flow-events'
import { FlowOperatorChain } from './FlowOperatorChain'
import { FlowBackpressureIndicator } from './FlowBackpressureIndicator'
import { FlowValueTrace } from './FlowValueTrace'
import { SharedFlowPanel } from './SharedFlowPanel'
import { StateFlowPanel } from './StateFlowPanel'
import type { VizEvent } from '@/types/api'

interface FlowPanelProps {
  sessionId: string
  /** The events the page shows (live, paused or at the replay cursor); the stored history when absent. */
  events?: readonly VizEvent[]
}

function getFlowTypeColor(flowType: string): 'primary' | 'secondary' | 'warning' | 'success' {
  switch (flowType) {
    case 'Cold': return 'primary'
    case 'Hot': return 'secondary'
    case 'SharedFlow': return 'warning'
    case 'StateFlow': return 'success'
    default: return 'primary'
  }
}

export function FlowPanel({ sessionId, events }: FlowPanelProps) {
  const { flows, hasBackpressure } = useFlowEvents(sessionId, events)
  const [visualMode, setVisualMode] = useState<'chips' | 'svg'>('chips')

  if (flows.length === 0) {
    return (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.4 }}
      >
        <Card className="mt-2">
          <CardBody>
            <div className="text-center text-default-400 py-8" data-testid="flow-empty">
              <p className="text-lg font-semibold mb-2">No Flow Activity</p>
              <p className="text-sm">
                Flow events will appear here when flows are created and collected.
              </p>
            </div>
          </CardBody>
        </Card>
      </motion.div>
    )
  }

  return (
    <motion.div
      className="space-y-4 mt-2"
      data-testid="flow-panel"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.4 }}
    >
      {/* Visual mode toggle */}
      <div className="flex justify-end">
        <Button
          size="sm"
          variant={visualMode === 'svg' ? 'solid' : 'flat'}
          color="primary"
          startContent={visualMode === 'svg' ? <FiActivity size={14} /> : <FiGrid size={14} />}
          onPress={() => setVisualMode(m => (m === 'chips' ? 'svg' : 'chips'))}
        >
          {visualMode === 'svg' ? 'Particle View' : 'Chip View'}
        </Button>
      </div>

      {/* Summary statistics */}
      <div className="grid grid-cols-4 gap-3">
        {[
          { value: flows.length, label: 'Total Flows', color: 'text-primary' },
          { value: flows.reduce((sum, f) => sum + f.operators.length, 0), label: 'Operators', color: 'text-secondary' },
          { value: flows.reduce((sum, f) => sum + f.emissions.length, 0), label: 'Emissions', color: 'text-success' },
          { value: flows.reduce((sum, f) => sum + f.backpressureEvents.length, 0), label: 'Backpressure', color: hasBackpressure ? 'text-warning' : 'text-default-300' },
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

      {/* Per-flow detail cards */}
      {flows.map((flow, index) => (
        <motion.div
          key={flow.flowId}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.3, delay: index * 0.05 }}
        >
          <Card shadow="sm">
            <CardHeader className="pb-2">
              <div className="flex w-full items-center justify-between">
                <div>
                  <div className="font-semibold">
                    {flow.label || flow.flowId}
                  </div>
                  <div className="text-xs text-default-500 font-mono">
                    {flow.flowId}
                  </div>
                </div>
                <div className="flex gap-2">
                  <Chip size="sm" variant="flat" color={getFlowTypeColor(flow.flowType)}>
                    {flow.flowType}
                  </Chip>
                  <Chip size="sm" variant="bordered" color="default">
                    {flow.emissions.length} emitted
                  </Chip>
                </div>
              </div>
            </CardHeader>
            <CardBody className="pt-0 space-y-4">
              {/* Operator chain */}
              <FlowOperatorChain operators={flow.operators} flowLabel={flow.label} visualMode={visualMode} flow={flow} />

              {/* Backpressure warning */}
              <FlowBackpressureIndicator events={flow.backpressureEvents} />

              {/* Value traces */}
              {flow.valueTraces.length > 0 && (
                <>
                  <Divider />
                  <FlowValueTrace traces={flow.valueTraces} />
                </>
              )}

              {/* SharedFlow sub-panel */}
              {flow.flowType === 'SharedFlow' && (
                <>
                  <Divider />
                  <SharedFlowPanel flow={flow} />
                </>
              )}

              {/* StateFlow sub-panel */}
              {flow.flowType === 'StateFlow' && (
                <>
                  <Divider />
                  <StateFlowPanel flow={flow} />
                </>
              )}
            </CardBody>
          </Card>
        </motion.div>
      ))}
    </motion.div>
  )
}
