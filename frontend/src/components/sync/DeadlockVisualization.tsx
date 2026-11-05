import { useMemo } from 'react'
import { Card, CardBody, CardHeader, Chip, Divider } from '@heroui/react'
import { motion } from 'framer-motion'
import { FiAlertOctagon } from 'react-icons/fi'
import { deadlockEdge, pathHighlight } from '@/lib/animation-variants'
import type { DeadlockDetected } from '@/types/api'
import { vizColors } from '@/styles/palette'

interface DeadlockVisualizationProps {
  deadlock: DeadlockDetected
}

interface GraphNode {
  id: string
  label: string | null | undefined
  x: number
  y: number
  type: 'coroutine' | 'mutex'
}

interface GraphEdge {
  from: string
  to: string
  label: string
}

export function DeadlockVisualization({ deadlock }: DeadlockVisualizationProps) {
  const { nodes, edges } = useMemo(() => {
    const nodes: GraphNode[] = []
    const edges: GraphEdge[] = []
    const allIds = new Set<string>()

    // Position coroutines and mutexes in a circle
    const coroutineCount = deadlock.involvedCoroutines.length
    const mutexCount = deadlock.involvedMutexes.length
    const totalItems = coroutineCount + mutexCount
    const radius = 120
    const centerX = 160
    const centerY = 140

    // Place coroutines
    deadlock.involvedCoroutines.forEach((id, i) => {
      const angle = (2 * Math.PI * i) / totalItems
      nodes.push({
        id,
        label: deadlock.involvedCoroutineLabels[i],
        x: centerX + radius * Math.cos(angle),
        y: centerY + radius * Math.sin(angle),
        type: 'coroutine',
      })
      allIds.add(id)
    })

    // Place mutexes
    deadlock.involvedMutexes.forEach((id, i) => {
      const angle = (2 * Math.PI * (coroutineCount + i)) / totalItems
      nodes.push({
        id,
        label: deadlock.involvedMutexLabels[i],
        x: centerX + radius * Math.cos(angle),
        y: centerY + radius * Math.sin(angle),
        type: 'mutex',
      })
      allIds.add(id)
    })

    // Wait edges: coroutine -> mutex (waiting for)
    for (const [coroutineId, mutexId] of Object.entries(deadlock.waitGraph)) {
      edges.push({ from: coroutineId, to: mutexId, label: 'waiting' })
    }

    // Hold edges: mutex -> coroutine (held by)
    for (const [mutexId, coroutineId] of Object.entries(deadlock.holdGraph)) {
      edges.push({ from: mutexId, to: coroutineId, label: 'held by' })
    }

    return { nodes, edges }
  }, [deadlock])

  const nodeMap = useMemo(() => {
    const map = new Map<string, GraphNode>()
    nodes.forEach(n => map.set(n.id, n))
    return map
  }, [nodes])

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      className="relative"
    >
      <Card className="border-2 border-danger">
        <CardHeader className="flex items-center gap-2 bg-danger-50">
          <FiAlertOctagon className="text-danger" size={20} />
          <span className="font-bold text-danger">Deadlock Detected</span>
          <Chip color="danger" variant="flat" size="sm">
            {deadlock.involvedCoroutines.length} coroutines
          </Chip>
        </CardHeader>
        <Divider />
        <CardBody>
          <p className="text-sm text-default-600 mb-3">{deadlock.cycleDescription}</p>

          {/* SVG circular dependency graph */}
          <svg viewBox="0 0 320 280" className="w-full max-w-md mx-auto">
            {/* Edges */}
            {edges.map((edge, i) => {
              const from = nodeMap.get(edge.from)
              const to = nodeMap.get(edge.to)
              if (!from || !to) return null
              return (
                <motion.line
                  key={`edge-${i}`}
                  x1={from.x}
                  y1={from.y}
                  x2={to.x}
                  y2={to.y}
                  variants={deadlockEdge}
                  initial="idle"
                  animate="alert"
                  strokeWidth={2}
                  markerEnd="url(#arrowhead)"
                />
              )
            })}

            {/* Arrow marker */}
            <defs>
              <marker id="arrowhead" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
                <polygon points="0 0, 8 3, 0 6" fill={vizColors.deadlockEdge} />
              </marker>
            </defs>

            {/* Nodes */}
            {nodes.map(node => (
              <g key={node.id}>
                <motion.circle
                  cx={node.x}
                  cy={node.y}
                  r={node.type === 'coroutine' ? 20 : 14}
                  fill={node.type === 'coroutine' ? vizColors.deadlockCoroutineFill : vizColors.deadlockLockFill}
                  stroke={node.type === 'coroutine' ? vizColors.deadlockCoroutineStroke : vizColors.deadlockLockStroke}
                  strokeWidth={2}
                  variants={pathHighlight}
                  initial="hidden"
                  animate="visible"
                />
                <text
                  x={node.x}
                  y={node.y + 32}
                  textAnchor="middle"
                  className="text-[10px] fill-default-600"
                >
                  {node.label ?? node.id.slice(-6)}
                </text>
              </g>
            ))}
          </svg>

          {/* Details */}
          <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
            <div>
              <span className="font-medium">Coroutines:</span>
              <ul className="list-disc ml-4 mt-1">
                {deadlock.involvedCoroutines.map((id, i) => (
                  <li key={id} className="text-default-600">
                    {deadlock.involvedCoroutineLabels[i] ?? id}
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <span className="font-medium">Mutexes:</span>
              <ul className="list-disc ml-4 mt-1">
                {deadlock.involvedMutexes.map((id, i) => (
                  <li key={id} className="text-default-600">
                    {deadlock.involvedMutexLabels[i] ?? id}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </CardBody>
      </Card>
    </motion.div>
  )
}
