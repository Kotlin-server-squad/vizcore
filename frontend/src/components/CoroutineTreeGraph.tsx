import type React from 'react'
import { useMemo, useState } from 'react'
import { motion, AnimatePresence, LayoutGroup } from 'framer-motion'
import { TransformWrapper, TransformComponent } from 'react-zoom-pan-pinch'
import { Button, Chip } from '@heroui/react'
import type { CoroutineNode } from '@/types/api'
import { buildCoroutineTree } from '@/lib/utils'
import { useAnimationSlot } from '@/lib/animation-throttle'
import { getStateColors, isActiveState } from '@/lib/coroutine-state-colors'
import { layoutSpring, connectorSpring } from '@/lib/layout-transition'
import { FiLoader, FiZoomIn, FiZoomOut, FiMaximize, FiLock, FiUnlock } from 'react-icons/fi'

interface CoroutineTreeGraphProps {
  coroutines: CoroutineNode[]
  /**
   * Optional/additive (D-05/D-06). When provided, each graph node card becomes
   * a clickable/focusable button that fires `onSelect(node.id)` on click or
   * Enter/Space. Omitted in replay/shared views (nodes stay presentational).
   */
  onSelect?: (id: string) => void
  /** Id of the currently selected node; drives the `ring-2 ring-primary` highlight. */
  selectedNodeId?: string | null
}

interface TreeNode extends CoroutineNode {
  children: TreeNode[]
}

// Re-alias for local readability
const isRunning = isActiveState

export function CoroutineTreeGraph({ coroutines, onSelect, selectedNodeId }: CoroutineTreeGraphProps) {
  const tree = useMemo(() => buildCoroutineTree(coroutines), [coroutines])
  const activeCount = useMemo(
    () => coroutines.filter(c => isRunning(c.state)).length,
    [coroutines]
  )
  const [isLocked, setIsLocked] = useState(true)

  if (coroutines.length === 0) {
    return (
      <div className="flex min-h-[400px] items-center justify-center text-default-400">
        No coroutines in this session yet.
      </div>
    )
  }

  return (
    <div className="relative min-h-[600px] w-full border border-default-200 rounded-lg bg-default-50">
      <TransformWrapper
        initialScale={1}
        minScale={0.3}
        maxScale={2}
        centerOnInit
        limitToBounds={false}
        panning={{ disabled: isLocked }}
        wheel={{ step: 0.1, disabled: isLocked }}
        doubleClick={{ disabled: isLocked }}
      >
        {({ zoomIn, zoomOut, resetTransform }) => (
          <>
            {/* Pan/Zoom Controls */}
            <div className="absolute top-4 right-4 z-10 flex gap-2">
              {/* Lock/Unlock Button */}
              <Button
                isIconOnly
                size="sm"
                variant={isLocked ? 'flat' : 'solid'}
                color={isLocked ? 'warning' : 'success'}
                onPress={() => setIsLocked(!isLocked)}
                title={isLocked ? 'Unlock Pan/Zoom' : 'Lock Pan/Zoom'}
              >
                {isLocked ? <FiLock /> : <FiUnlock />}
              </Button>
              
              {/* Divider */}
              <div className="w-px bg-default-300" />
              
              {/* Zoom Controls */}
              <Button
                isIconOnly
                size="sm"
                variant="flat"
                onPress={() => zoomIn()}
                isDisabled={isLocked}
                title="Zoom In"
              >
                <FiZoomIn />
              </Button>
              <Button
                isIconOnly
                size="sm"
                variant="flat"
                onPress={() => zoomOut()}
                isDisabled={isLocked}
                title="Zoom Out"
              >
                <FiZoomOut />
              </Button>
              <Button
                isIconOnly
                size="sm"
                variant="flat"
                onPress={() => resetTransform()}
                isDisabled={isLocked}
                title="Reset View"
              >
                <FiMaximize />
              </Button>
            </div>

            {/* Top-left indicators */}
            <div className="absolute top-4 left-4 z-10 flex flex-col gap-2">
              {/* Real-time activity indicator */}
              {activeCount > 0 && (
                <motion.div
                  className="flex items-center gap-2 rounded-full bg-primary/10 px-4 py-2 text-sm font-semibold text-primary backdrop-blur-sm"
                  initial={{ opacity: 0, y: -20 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -20 }}
                >
                  <motion.div
                    className="h-2 w-2 rounded-full bg-primary"
                    animate={{
                      scale: [1, 1.5, 1],
                      opacity: [1, 0.5, 1],
                    }}
                    transition={{
                      duration: 1.5,
                      repeat: Infinity,
                      ease: 'easeInOut',
                    }}
                  />
                  <span>{activeCount} coroutine{activeCount > 1 ? 's' : ''} actively running</span>
                </motion.div>
              )}
              
              {/* Lock status indicator */}
              <AnimatePresence>
                {isLocked && (
                  <motion.div
                    initial={{ opacity: 0, x: -20 }}
                    animate={{ opacity: 1, x: 0 }}
                    exit={{ opacity: 0, x: -20 }}
                  >
                    <Chip
                      startContent={<FiLock />}
                      variant="flat"
                      color="warning"
                      size="sm"
                      className="backdrop-blur-sm"
                    >
                      Pan/Zoom Locked
                    </Chip>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>

            <TransformComponent
              wrapperStyle={{ width: '100%', height: '100%', minHeight: '600px' }}
              contentStyle={{ width: '100%', height: '100%' }}
            >
              <LayoutGroup>
                <div className="flex flex-col items-center gap-16 p-8 min-h-[600px]">
                  <AnimatePresence mode="popLayout" initial={false}>
                    {tree.map((node, index) => (
                      <TreeNodeComponent
                        key={node.id}
                        node={node}
                        isRoot
                        level={0}
                        siblingIndex={index}
                        onSelect={onSelect}
                        selectedNodeId={selectedNodeId}
                      />
                    ))}
                  </AnimatePresence>
                </div>
              </LayoutGroup>
            </TransformComponent>
          </>
        )}
      </TransformWrapper>
    </div>
  )
}

interface TreeNodeComponentProps {
  node: TreeNode
  isRoot?: boolean
  level: number
  siblingIndex: number
  onSelect?: (id: string) => void
  selectedNodeId?: string | null
}

function TreeNodeComponent({
  node,
  isRoot = false,
  level,
  siblingIndex,
  onSelect,
  selectedNodeId,
}: TreeNodeComponentProps) {
  const colors = getStateColors(node.state)
  const hasChildren = node.children.length > 0
  const shouldAnimate = useAnimationSlot()
  // Use FiLoader for ACTIVE (rotation animation) — override the default FiPlay
  const StateIcon = node.state === 'ACTIVE' ? FiLoader : colors.Icon

  const WrapperComponent = shouldAnimate ? motion.div : 'div'
  const CardComponent = shouldAnimate ? motion.div : 'div'

  return (
    <motion.div layout layoutId={`node-container-${node.id}`} transition={layoutSpring} className="flex flex-col items-center">
      {/* Connection line from parent - simplified */}
      {!isRoot && (
        <div className="relative flex flex-col items-center">
          {shouldAnimate ? (
            <motion.div
              layout
              className={`h-12 w-0.5 ${colors.line} rounded-full`}
              initial={{ scaleY: 0 }}
              animate={{ scaleY: 1 }}
              transition={{ layout: connectorSpring, delay: level * 0.1, duration: 0.3 }}
            />
          ) : (
            <div className={`h-12 w-0.5 ${colors.line} rounded-full`} />
          )}
          {/* Animated flow particles for running states */}
          {shouldAnimate && isRunning(node.state) && (
            <motion.div
              className="absolute left-1/2 h-1.5 w-1.5 rounded-full bg-primary"
              style={{ marginLeft: '-3px' }}
              animate={{
                y: [0, 48],
                opacity: [0, 1, 0],
              }}
              transition={{
                duration: node.state === 'WAITING_FOR_CHILDREN' ? 2.5 : 1.5,
                repeat: Infinity,
                ease: 'easeInOut',
              }}
            />
          )}
        </div>
      )}

      {/* Node Card */}
      <WrapperComponent
        {...(shouldAnimate
          ? {
              key: `wrapper-${node.id}`,
              layout: true,
              layoutId: `node-card-${node.id}`,
              initial: { scale: 0, opacity: 0 },
              animate: { scale: 1, opacity: 1 },
              exit: { scale: 0.8, opacity: 0 },
              transition: {
                layout: layoutSpring,
                delay: level * 0.1 + siblingIndex * 0.05,
                type: 'spring',
                stiffness: 200,
                damping: 20,
              },
            }
          : {})}
        className="relative z-10"
      >
        <CardComponent
          {...(shouldAnimate
            ? {
                key: `card-${node.id}-${node.state}`,
                whileHover: { scale: 1.05 },
                initial: false,
                animate: isRunning(node.state)
                  ? {
                      boxShadow: [
                        '0 10px 40px -12px rgba(99, 102, 241, 0.3)',
                        '0 10px 40px -12px rgba(99, 102, 241, 0.6)',
                        '0 10px 40px -12px rgba(99, 102, 241, 0.3)',
                      ],
                    }
                  : {},
                transition: isRunning(node.state)
                  ? {
                      duration: 2,
                      repeat: Infinity,
                      ease: 'easeInOut',
                    }
                  : {},
              }
            : {})}
          className={
            selectedNodeId === node.id
              ? 'relative rounded-2xl border-2 bg-content1 p-6 shadow-lg transition-all hover:shadow-2xl ring-2 ring-primary ' +
                colors.border
              : onSelect
                ? 'relative rounded-2xl border-2 bg-content1 p-6 shadow-lg transition-all hover:shadow-2xl cursor-pointer ' +
                  colors.border
                : 'relative rounded-2xl border-2 bg-content1 p-6 shadow-lg transition-all hover:shadow-2xl ' +
                  colors.border
          }
          style={{ minWidth: '280px' }}
          {...(onSelect
            ? {
                role: 'button',
                tabIndex: 0,
                'aria-label': `Open source for ${node.label || node.id}`,
                onClick: () => onSelect(node.id),
                onKeyDown: (e: React.KeyboardEvent) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onSelect(node.id)
                  }
                },
              }
            : {})}
        >
          {/* Animated Background for Running States */}
          {shouldAnimate ? (
            <AnimatePresence>
              {isRunning(node.state) && (
                <motion.div
                  className={`absolute inset-0 rounded-2xl ${colors.bgTint}`}
                  initial={{ opacity: 0 }}
                  animate={{
                    opacity: node.state === 'WAITING_FOR_CHILDREN' ? [0.2, 0.4, 0.2] : [0.3, 0.6, 0.3],
                  }}
                  exit={{ opacity: 0 }}
                  transition={{
                    duration: node.state === 'WAITING_FOR_CHILDREN' ? 3 : 2,
                    repeat: Infinity,
                    ease: 'easeInOut',
                  }}
                />
              )}
            </AnimatePresence>
          ) : (
            isRunning(node.state) && (
              <div className={`absolute inset-0 rounded-2xl ${colors.bgTint} opacity-30`} />
            )
          )}

          <div className="relative z-10">
            {/* Header with Icon and Label */}
            <div className="mb-3 flex items-center gap-3">
              {shouldAnimate ? (
                <motion.div
                  key={`icon-${node.id}-${node.state}`}
                  className={`${colors.text} flex h-10 w-10 items-center justify-center rounded-full ${colors.iconBg}`}
                  initial={false}
                  animate={
                    node.state === 'ACTIVE'
                      ? {
                          rotate: 360,
                          scale: [1, 1.1, 1],
                        }
                      : node.state === 'WAITING_FOR_CHILDREN'
                      ? {
                          scale: [1, 1.15, 1],
                        }
                      : {}
                  }
                  transition={
                    node.state === 'ACTIVE'
                      ? {
                          rotate: { duration: 2, repeat: Infinity, ease: 'linear' },
                          scale: { duration: 1, repeat: Infinity, ease: 'easeInOut' },
                        }
                      : node.state === 'WAITING_FOR_CHILDREN'
                      ? {
                          scale: { duration: 2, repeat: Infinity, ease: 'easeInOut' },
                        }
                      : {}
                  }
                >
                  <StateIcon className="h-5 w-5" />
                </motion.div>
              ) : (
                <div
                  className={`${colors.text} flex h-10 w-10 items-center justify-center rounded-full ${colors.iconBg}`}
                >
                  <StateIcon className="h-5 w-5" />
                </div>
              )}
              <div className="flex-1">
                <div className="text-lg font-bold">{node.label || node.id}</div>
                <div className="text-xs text-default-500">
                  {node.state}
                  {node.state === 'ACTIVE' && shouldAnimate && (
                    <motion.span
                      className="ml-2 inline-block"
                      animate={{ opacity: [1, 0.5, 1] }}
                      transition={{ duration: 1.5, repeat: Infinity }}
                    >
                      ●
                    </motion.span>
                  )}
                  {node.state === 'ACTIVE' && !shouldAnimate && (
                    <span className="ml-2 inline-block">●</span>
                  )}
                </div>
              </div>
            </div>

            {/* Details */}
            {shouldAnimate ? (
            <AnimatePresence mode="wait">
              <motion.div
                className="space-y-1 text-sm"
                key={node.state}
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: 4 }}
                transition={{ duration: 0.2 }}
              >
                <div className="flex items-center gap-2 text-default-600">
                  <span className="font-semibold">ID:</span>
                  <span className="font-mono text-xs">{node.id}</span>
                </div>
                <div className="flex items-center gap-2 text-default-600">
                  <span className="font-semibold">Job:</span>
                  <span className="font-mono text-xs">{node.jobId}</span>
                </div>
                <div className="flex items-center gap-2 text-default-600">
                  <span className="font-semibold">Scope:</span>
                  <span className="font-mono text-xs">{node.scopeId}</span>
                </div>
                {node.parentId && (
                  <div className="flex items-center gap-2 text-default-600">
                    <span className="font-semibold">Parent:</span>
                    <span className="font-mono text-xs">{node.parentId}</span>
                  </div>
                )}
              </motion.div>
            </AnimatePresence>
            ) : (
              <div className="space-y-1 text-sm">
                <div className="flex items-center gap-2 text-default-600">
                  <span className="font-semibold">ID:</span>
                  <span className="font-mono text-xs">{node.id}</span>
                </div>
                <div className="flex items-center gap-2 text-default-600">
                  <span className="font-semibold">Job:</span>
                  <span className="font-mono text-xs">{node.jobId}</span>
                </div>
                <div className="flex items-center gap-2 text-default-600">
                  <span className="font-semibold">Scope:</span>
                  <span className="font-mono text-xs">{node.scopeId}</span>
                </div>
                {node.parentId && (
                  <div className="flex items-center gap-2 text-default-600">
                    <span className="font-semibold">Parent:</span>
                    <span className="font-mono text-xs">{node.parentId}</span>
                  </div>
                )}
              </div>
            )}

            {/* Progress Bar for Active State */}
            {shouldAnimate ? (
              <AnimatePresence>
                {node.state === 'ACTIVE' && (
                  <motion.div
                    className="mt-3 h-1 overflow-hidden rounded-full bg-default-200"
                    initial={{ opacity: 0, scaleX: 0 }}
                    animate={{ opacity: 1, scaleX: 1 }}
                    exit={{ opacity: 0, scaleX: 0 }}
                    transition={{ duration: 0.2 }}
                  >
                    <motion.div
                      className="h-full bg-primary"
                      animate={{
                        x: ['-100%', '100%'],
                      }}
                      transition={{
                        duration: 1.5,
                        repeat: Infinity,
                        ease: 'linear',
                      }}
                      style={{ width: '50%' }}
                    />
                  </motion.div>
                )}
              </AnimatePresence>
            ) : (
              node.state === 'ACTIVE' && (
                <div className="mt-3 h-1 overflow-hidden rounded-full bg-default-200">
                  <div className="h-full bg-primary" style={{ width: '50%' }} />
                </div>
              )
            )}
          </div>
        </CardComponent>
      </WrapperComponent>

      {/* Children */}
      {hasChildren && (
        <>
          {node.children.length === 1 ? (
            /* Single child - simple vertical connection */
            <>
              <div className="relative flex flex-col items-center">
                <motion.div
                  layout
                  className={`h-16 w-0.5 ${colors.line} rounded-full`}
                  initial={{ scaleY: 0 }}
                  animate={{ scaleY: 1 }}
                  transition={{ layout: connectorSpring, delay: (level + 1) * 0.1, duration: 0.3 }}
                />
                {/* Relationship label */}
                <motion.div
                  className="absolute left-full ml-3 top-1/2 -translate-y-1/2 whitespace-nowrap rounded-lg bg-default-100/80 backdrop-blur-sm px-3 py-1 text-xs font-medium text-default-700 border border-default-200"
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: (level + 1) * 0.1 + 0.3 }}
                >
                  1 child
                </motion.div>
                {/* Animated flow particles */}
                {(node.state === 'ACTIVE' || node.state === 'WAITING_FOR_CHILDREN') && (
                  <motion.div
                    className="absolute left-1/2 h-1.5 w-1.5 rounded-full bg-primary"
                    style={{ marginLeft: '-3px' }}
                    animate={{
                      y: [0, 64],
                      opacity: [0, 1, 0],
                    }}
                    transition={{
                      duration: 1.5,
                      repeat: Infinity,
                      ease: 'easeInOut',
                      delay: 0.5,
                    }}
                  />
                )}
              </div>
              <TreeNodeComponent
                key={node.children[0]!.id}
                node={node.children[0]!}
                level={level + 1}
                siblingIndex={0}
                onSelect={onSelect}
                selectedNodeId={selectedNodeId}
              />
            </>
          ) : (
            /* Multiple children - branching structure */
            <>
              {/* Main connector line down from parent */}
              <div className="relative flex flex-col items-center">
                <motion.div
                  layout
                  className={`h-8 w-0.5 ${colors.line} rounded-full`}
                  initial={{ scaleY: 0 }}
                  animate={{ scaleY: 1 }}
                  transition={{ layout: connectorSpring, delay: (level + 1) * 0.1, duration: 0.3 }}
                />
                {/* Relationship label */}
                <motion.div
                  className="absolute left-full ml-3 top-1/2 -translate-y-1/2 whitespace-nowrap rounded-lg bg-default-100/80 backdrop-blur-sm px-3 py-1 text-xs font-medium text-default-700 border border-default-200"
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: (level + 1) * 0.1 + 0.3 }}
                >
                  {node.children.length} children
                </motion.div>
              </div>

              {/* Branching structure */}
              <div className="relative flex flex-col items-center" style={{ width: `${node.children.length * 320}px` }}>
                {/* Horizontal line connecting to all children */}
                <motion.div
                  layout
                  className="relative w-full h-0.5 bg-default-300 rounded-full"
                  style={{
                    width: `${(node.children.length - 1) * 320}px`,
                  }}
                  initial={{ scaleX: 0 }}
                  animate={{ scaleX: 1 }}
                  transition={{ layout: connectorSpring, delay: (level + 1) * 0.1 + 0.2, duration: 0.4 }}
                />
                
                {/* Junction point at center */}
                <motion.div
                  className="absolute top-0 left-1/2 -translate-x-1/2 h-2 w-2 rounded-full bg-default-400"
                  initial={{ scale: 0 }}
                  animate={{ scale: 1 }}
                  transition={{ delay: (level + 1) * 0.1 + 0.3, type: 'spring' }}
                />
                
                {/* Vertical lines down to each child */}
                <div className="relative w-full h-16 flex">
                  {node.children.map((child, i) => (
                    <div key={i} className="flex-1 flex justify-center relative">
                      <motion.div
                        className={`w-0.5 h-16 ${getStateColors(child.state).line} rounded-full`}
                        initial={{ scaleY: 0, transformOrigin: 'top' }}
                        animate={{ scaleY: 1 }}
                        transition={{ delay: (level + 1) * 0.1 + 0.4 + i * 0.05 }}
                      />
                      {/* Flow animation for active children */}
                      {(child.state === 'ACTIVE' || child.state === 'WAITING_FOR_CHILDREN') && (
                        <motion.div
                          className="absolute top-0 left-1/2 h-1.5 w-1.5 rounded-full bg-primary"
                          style={{ marginLeft: '-3px' }}
                          animate={{
                            y: [0, 64],
                            opacity: [0, 1, 0],
                          }}
                          transition={{
                            duration: 1.5,
                            repeat: Infinity,
                            ease: 'easeInOut',
                            delay: i * 0.3,
                          }}
                        />
                      )}
                    </div>
                  ))}
                </div>
              </div>

              {/* Child nodes */}
              <div className="flex gap-8" style={{ width: `${node.children.length * 320}px` }}>
                <AnimatePresence mode="popLayout" initial={false}>
                  {node.children.map((child, index) => (
                    <div key={child.id} className="flex-1 flex justify-center">
                      <TreeNodeComponent
                        node={child}
                        level={level + 1}
                        siblingIndex={index}
                        onSelect={onSelect}
                        selectedNodeId={selectedNodeId}
                      />
                    </div>
                  ))}
                </AnimatePresence>
              </div>
            </>
          )}
        </>
      )}
    </motion.div>
  )
}
