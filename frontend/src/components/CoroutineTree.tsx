import { createContext, useContext, useMemo } from 'react'
import { groupMix, type NameGroup } from '@/lib/name-groups'
import { Card, CardBody, Chip } from '@heroui/react'
import { motion, LayoutGroup } from 'framer-motion'
import type { CoroutineNode, CoroutineState } from '@/types/api'
import type { ActivityLine } from '@/lib/activity'
import { CardFacts, CardStateContext } from './CardFacts'
import { buildCoroutineTree } from '@/lib/utils'
import { useAnimationSlot } from '@/lib/animation-throttle'
import { fadeSlideIn, getStateVariant, hoverGlow } from '@/lib/animation-variants'
import { getStateColors, isActiveState, stateLabel } from '@/lib/coroutine-state-colors'
import { layoutSpring } from '@/lib/layout-transition'

/** A child in one of these states is done: its parent no longer waits for it. */
const TERMINAL_STATES = new Set<string>(['COMPLETED', 'CANCELLED', 'FAILED'])

interface CoroutineTreeProps {
  coroutines: CoroutineNode[]
  /**
   * Optional/additive (D-05/D-06). When provided, each node becomes a
   * clickable/focusable button that fires `onSelect(node.id)` on click or
   * Enter/Space — used by the live "What's running now" view to open the
   * source drawer. When omitted (replay/shared views) nodes stay presentational.
   */
  onSelect?: (id: string) => void
  /** Id of the currently selected node; drives the `ring-2 ring-primary` highlight. */
  selectedNodeId?: string | null
  /**
   * Unfinished children per parent id, counted over the whole snapshot. The
   * rendered set may be filtered or capped, and a waiting parent's children can
   * be hidden by the very chip that selected it. Absent: counted from the
   * rendered children.
   */
  pendingChildCounts?: ReadonlyMap<string, number>
  /** Each coroutine's second line: what it waits for or holds (S-6). */
  activity?: ReadonlyMap<string, ActivityLine>
  /** Siblings grouped by name (S-12), by their group node's id: those nodes draw as group rows. */
  groups?: ReadonlyMap<string, NameGroup>
  /** Expand or collapse a group (its row's button). */
  onToggleGroup?: (id: string) => void
}

/** The groups and their toggle, for every row of the tree (rows recurse, so a context rather than props). */
const GroupsContext = createContext<{ groups?: ReadonlyMap<string, NameGroup>; onToggle?: (id: string) => void }>({})

export function CoroutineTree({
  coroutines,
  onSelect,
  selectedNodeId,
  pendingChildCounts,
  activity,
  groups,
  onToggleGroup,
}: CoroutineTreeProps) {
  const tree = useMemo(() => buildCoroutineTree(coroutines), [coroutines])
  const groupsValue = useMemo(() => ({ groups, onToggle: onToggleGroup }), [groups, onToggleGroup])

  if (coroutines.length === 0) {
    return (
      <div className="py-8 text-center text-default-400">
        No coroutines in this session yet.
      </div>
    )
  }

  return (
    <GroupsContext.Provider value={groupsValue}>
    <LayoutGroup>
      <div className="space-y-4">
        {tree.map(node => (
          <TreeNode
            key={node.id}
            node={node}
            depth={0}
            onSelect={onSelect}
            selectedNodeId={selectedNodeId}
            pendingChildCounts={pendingChildCounts}
            activity={activity}
          />
        ))}
      </div>
    </LayoutGroup>
    </GroupsContext.Provider>
  )
}

type CoroutineTreeNode = CoroutineNode & { children: CoroutineTreeNode[] }

interface TreeNodeProps {
  node: CoroutineTreeNode
  depth: number
  onSelect?: (id: string) => void
  selectedNodeId?: string | null
  pendingChildCounts?: ReadonlyMap<string, number>
  activity?: ReadonlyMap<string, ActivityLine>
}

function TreeNode({ node, depth, onSelect, selectedNodeId, pendingChildCounts, activity }: TreeNodeProps) {
  const isSelected = selectedNodeId === node.id
  const line = activity?.get(node.id)
  const colors = getStateColors(node.state)
  const stateVariant = getStateVariant(node.state)
  const shouldAnimate = useAnimationSlot()

  const shouldPulse = isActiveState(node.state) || node.state === 'SUSPENDED'
  const shouldShake = node.state === 'FAILED'

  const OuterComponent = shouldAnimate ? motion.div : 'div'
  const PulseComponent = shouldAnimate ? motion.div : 'div'
  const { groups, onToggle } = useContext(GroupsContext)
  const group = groups?.get(node.id)
  const children = node.children.map(child => (
    <TreeNode
      key={child.id}
      node={child}
      depth={depth + 1}
      onSelect={onSelect}
      selectedNodeId={selectedNodeId}
      pendingChildCounts={pendingChildCounts}
      activity={activity}
    />
  ))
  if (group) {
    return (
      <div data-node-id={node.id} style={{ marginLeft: `${depth * 24}px` }}>
        <GroupRow group={group} selected={isSelected} onSelect={onSelect} onToggle={onToggle} />
        {children}
      </div>
    )
  }

  return (
    <OuterComponent
      {...(shouldAnimate
        ? {
            layout: true,
            layoutId: `tree-node-${node.id}`,
            variants: shouldShake ? stateVariant?.variants : fadeSlideIn,
            initial: shouldShake ? stateVariant?.initial : 'hidden',
            animate: shouldShake ? stateVariant?.animate : 'visible',
            custom: depth,
            transition: { layout: layoutSpring },
          }
        : {})}
      // Find brings a coroutine into view by this (S-12).
      data-node-id={node.id}
      style={{ marginLeft: `${depth * 24}px` }}
    >
      <PulseComponent
        {...(shouldAnimate && shouldPulse && stateVariant
          ? {
              variants: stateVariant.variants,
              initial: stateVariant.initial,
              animate: stateVariant.animate,
              whileHover: hoverGlow,
            }
          : shouldAnimate
          ? { whileHover: hoverGlow }
          : {})}
      >
        <Card
          className={
            isSelected
              ? 'mb-2 ring-2 ring-primary bg-primary/5'
              : onSelect
                ? 'mb-2 cursor-pointer hover:bg-default-100'
                : 'mb-2'
          }
          shadow="sm"
          {...(onSelect
            ? {
                // HeroUI Card gates its internal click handling behind
                // `isPressable`; this also makes the Card a real role="button"
                // focusable target firing on click + Enter/Space natively.
                isPressable: true,
                'aria-label': `Open source for ${node.label || node.id}`,
                onPress: () => onSelect(node.id),
              }
            : {})}
        >
          <CardBody className="py-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                {shouldAnimate ? (
                  <motion.div
                    className={colors.text}
                    animate={
                      node.state === 'ACTIVE'
                        ? { rotate: 360 }
                        : node.state === 'WAITING_FOR_CHILDREN'
                        ? { scale: [1, 1.1, 1] }
                        : {}
                    }
                    transition={
                      node.state === 'ACTIVE'
                        ? { duration: 2, repeat: Infinity, ease: 'linear' }
                        : node.state === 'WAITING_FOR_CHILDREN'
                        ? { duration: 2, repeat: Infinity, ease: 'easeInOut' }
                        : {}
                    }
                  >
                    <colors.Icon className="h-5 w-5" />
                  </motion.div>
                ) : (
                  <div className={colors.text}>
                    <colors.Icon className="h-5 w-5" />
                  </div>
                )}
                <div>
                  <div className="font-semibold">
                    {node.label || node.id}
                  </div>
                  <div className="text-xs text-default-500">
                    {node.id} • Job: {node.jobId}
                  </div>
                  {/* What it waits for or holds (S-6): a wait in the Suspended colour, a hold neutral. */}
                  {line && (
                    <div
                      data-testid="activity-line"
                      className={line.waiting ? 'text-xs text-warning' : 'text-xs text-default-600'}
                    >
                      {line.text}
                    </div>
                  )}
                  {/* Lifetime, creation site and the health's badges, as on the graph's cards (Part 7). */}
                  <CardFacts node={node} compact />
                </div>
              </div>
              <div className="flex items-center gap-2">
                <Chip
                  color={colors.chipColor}
                  size="sm"
                  variant="flat"
                  startContent={
                    shouldPulse ? (
                      <span className="relative flex h-2 w-2">
                        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-75" />
                        <span className="relative inline-flex h-2 w-2 rounded-full bg-current" />
                      </span>
                    ) : undefined
                  }
                >
                  {stateLabel(node.state)}
                  <CardStateContext id={node.id} />
                </Chip>
                <Chip size="sm" variant="bordered">
                  Scope: {node.scopeId}
                </Chip>
              </div>
            </div>
            
            {/* Job Properties with Animation */}
            <div className="mt-3 border-t border-default-200 pt-2">
              <div className="flex items-center gap-2 mb-2">
                <div className="flex items-center gap-1.5">
                  {deriveJobState(node.state).isActive && (
                    <motion.div
                      animate={{ rotate: 360 }}
                      transition={{ duration: 1.5, repeat: Infinity, ease: 'linear' }}
                      className="text-success"
                    >
                      <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                      </svg>
                    </motion.div>
                  )}
                  <span className="text-[11px] font-semibold text-default-600">Job Status:</span>
                </div>
                <div className="flex items-center gap-1.5 flex-wrap">
                  <JobPropertyBadge 
                    label="isActive" 
                    value={deriveJobState(node.state).isActive}
                    animated={deriveJobState(node.state).isActive}
                  />
                  <JobPropertyBadge 
                    label="isCompleted" 
                    value={deriveJobState(node.state).isCompleted} 
                  />
                  <JobPropertyBadge 
                    label="isCancelled" 
                    value={deriveJobState(node.state).isCancelled}
                    animated={deriveJobState(node.state).isCancelled}
                  />
                  {node.children.length > 0 && (
                    <Chip size="sm" variant="flat" color="primary" classNames={{ content: 'text-[10px]' }}>
                      {node.children.length} children
                    </Chip>
                  )}
                </div>
              </div>
            </div>
            
            {/* Add visual indicator for WAITING_FOR_CHILDREN */}
            {node.state === 'WAITING_FOR_CHILDREN' && (
              <motion.div
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                className="mt-2 rounded-md bg-secondary/10 px-3 py-2 text-xs text-secondary"
              >
                <div className="flex items-center justify-between mb-1">
                  <span>⏳ Waiting for {pendingChildCounts?.get(node.id) ?? node.children.filter(c => !TERMINAL_STATES.has(c.state)).length} child coroutine(s)</span>
                  <span className="font-semibold">Job.isActive = true</span>
                </div>
                <div className="text-[10px] opacity-75">
                  Coroutine body finished, but Job waits for children (structured concurrency)
                </div>
              </motion.div>
            )}
            
            {/* Add visual indicator for FAILED state */}
            {node.state === 'FAILED' && (
              <motion.div
                initial={{ opacity: 0, height: 0, scale: 0.95 }}
                animate={{ 
                  opacity: 1, 
                  height: 'auto',
                  scale: 1,
                }}
                transition={{ type: 'spring', stiffness: 300, damping: 20 }}
                className="mt-2 rounded-md bg-danger/10 px-3 py-2 text-xs text-danger border border-danger/30"
              >
                <div className="flex items-center justify-between mb-1">
                  <span className="flex items-center gap-1.5">
                    ⚠️ <span className="font-semibold">Coroutine Failed</span>
                  </span>
                  <span className="font-mono text-[10px]">Job.isCompleted = true</span>
                </div>
                <div className="text-[10px] opacity-75">
                  Exception thrown - will cancel parent and siblings (structured concurrency)
                </div>
              </motion.div>
            )}
            
            {/* Add visual indicator for CANCELLED state */}
            {node.state === 'CANCELLED' && (
              <motion.div
                initial={{ opacity: 0, height: 0, x: -10 }}
                animate={{ 
                  opacity: 1, 
                  height: 'auto',
                  x: 0,
                }}
                transition={{ type: 'spring', stiffness: 300, damping: 25 }}
                className="mt-2 rounded-md bg-default-100 px-3 py-2 text-xs text-default-500 border border-default-300"
              >
                <div className="flex items-center justify-between mb-1">
                  <span className="flex items-center gap-1.5">
                    🚫 <span className="font-semibold">Job Cancelled</span>
                  </span>
                  <span className="font-mono text-[10px]">Job.isCancelled = true</span>
                </div>
                <div className="text-[10px] opacity-75">
                  Cancelled due to structured concurrency or explicit cancellation
                </div>
              </motion.div>
            )}
          </CardBody>
        </Card>
      </PulseComponent>

      {children}
    </OuterComponent>
  )
}

/** Siblings grouped by name (S-12) as one row: its size, its states in words, and Expand / Collapse. */
function GroupRow({
  group,
  selected,
  onSelect,
  onToggle,
}: {
  group: NameGroup
  selected: boolean
  onSelect?: (id: string) => void
  onToggle?: (id: string) => void
}) {
  const size = group.memberIds.length
  return (
    <div
      data-look-part="group-row"
      className={
        'mb-2 flex items-center justify-between gap-3 rounded-large border-2 border-dashed border-default-300 bg-content1 px-3 py-2 ' +
        (selected ? 'ring-2 ring-primary bg-primary/5' : onSelect ? 'cursor-pointer hover:bg-default-100' : '')
      }
      {...(onSelect
        ? {
            role: 'button',
            tabIndex: 0,
            'aria-label': `Group ${group.label}: ${size} coroutines`,
            onClick: () => onSelect(group.id),
            onKeyDown: (e: React.KeyboardEvent) => {
              if (e.target !== e.currentTarget) return
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onSelect(group.id)
              }
            },
          }
        : {})}
    >
      <div className="min-w-0">
        <div data-look-part="group-name" className="font-mono font-semibold">{group.label}</div>
        <div className="text-xs text-default-500">
          <span data-look-part="group-count">{`group of ${size.toLocaleString('en-US')}`}</span>
          {' · '}
          <span data-look-part="group-mix">{groupMix(group.counts)}</span>
          {group.below > 0 && <span data-look-part="group-below">{` · +${group.below.toLocaleString('en-US')} below them`}</span>}
        </div>
      </div>
      {onToggle && (
        <button
          type="button"
          data-look-part="group-expand"
          aria-label={`${group.expanded ? 'Collapse' : 'Expand'} ${group.label}`}
          aria-expanded={group.expanded}
          onClick={e => {
            e.stopPropagation()
            onToggle(group.id)
          }}
          className="shrink-0 rounded-medium border border-default-300 px-2 py-0.5 text-xs text-default-700 hover:bg-default-100"
        >
          {group.expanded ? 'Collapse' : 'Expand'}
        </button>
      )}
    </div>
  )
}

/**
 * Job property badge component with animation support
 */
function JobPropertyBadge({ 
  label, 
  value, 
  animated = false 
}: { 
  label: string
  value: boolean
  animated?: boolean 
}) {
  const getBadgeColor = () => {
    if (!value) return 'default'
    if (label === 'isCancelled') return 'default'
    if (label === 'isCompleted') return 'success'
    if (label === 'isActive') return 'success'
    return 'default'
  }

  const badge = (
    <Chip
      size="sm"
      variant={value ? 'flat' : 'bordered'}
      color={getBadgeColor()}
      classNames={{
        base: value ? '' : 'opacity-40',
        content: 'text-[10px] font-mono'
      }}
      startContent={
        animated && value ? (
          <span className="relative flex h-1.5 w-1.5">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-75" />
            <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-current" />
          </span>
        ) : undefined
      }
    >
      {label}
    </Chip>
  )

  if (animated && value) {
    return (
      <motion.div
        animate={{
          scale: [1, 1.05, 1],
        }}
        transition={{
          duration: 2,
          repeat: Infinity,
          ease: 'easeInOut',
        }}
      >
        {badge}
      </motion.div>
    )
  }

  // Special animation for cancelled jobs
  if (label === 'isCancelled' && value) {
    return (
      <motion.div
        initial={{ scale: 0 }}
        animate={{ scale: 1 }}
        transition={{ type: 'spring', stiffness: 500, damping: 15 }}
      >
        {badge}
      </motion.div>
    )
  }

  return badge
}

/**
 * Derive job state from coroutine state
 */
function deriveJobState(coroutineState: string) {
  switch (coroutineState as CoroutineState) {
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
        isCompleted: true,
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


