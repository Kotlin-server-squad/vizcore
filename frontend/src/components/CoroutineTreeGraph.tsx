import type React from 'react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import {
  TransformWrapper,
  TransformComponent,
  type ReactZoomPanPinchContentRef,
  type ReactZoomPanPinchRef,
} from 'react-zoom-pan-pinch'
import { Button } from '@heroui/react'
import type { CoroutineNode } from '@/types/api'
import type { ActivityLine } from '@/lib/activity'
import { buildCoroutineTree } from '@/lib/utils'
import { useAnimationSlot } from '@/lib/animation-throttle'
import { getStateColors, isActiveState, stateLabel } from '@/lib/coroutine-state-colors'
import { chooseRowWidth, layoutTree, type TreeLayout } from '@/lib/tree-layout'
import { useLook, useLookCard } from '@/looks/look-context'
import { groupMix, type NameGroup } from '@/lib/name-groups'
import { FiLoader, FiZoomIn, FiZoomOut, FiMaximize2, FiMinimize2, FiCrosshair } from 'react-icons/fi'

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
  /** Each coroutine's second line: what it waits for or holds (S-6). */
  activity?: ReadonlyMap<string, ActivityLine>
  /**
   * Bring this card into view (Find, S-12): centred at the current zoom, or zoomed in to a readable size. Done once
   * per `key`; a pan or zoom afterwards is the user's.
   */
  reveal?: { id: string; key: number }
  /** Children per parent in the whole view, before the cap: a parent whose children were capped says "2 of 262". */
  childTotals?: ReadonlyMap<string, number>
  /** Siblings grouped by name (S-12), by their group node's id: those nodes draw as group cards. */
  groups?: ReadonlyMap<string, NameGroup>
  /** Expand or collapse a group (its card's button). */
  onToggleGroup?: (id: string) => void
}

interface TreeNode extends CoroutineNode {
  children: TreeNode[]
}

// Re-alias for local readability
const isRunning = isActiveState

/**
 * Spacing the layout reserves between cards. The card size is the look's
 * (`useLookCard`, Classic's 280 × 240 outside the session page); the card is
 * drawn at exactly that size.
 */
const H_GAP = 40
const V_GAP = 64
/** Space around the tree inside the canvas. */
const PADDING = 32
/** Fitted content starts below the toolbar so the controls never cover the first card. */
const TOOLBAR_INSET = 40
/** Zooming out goes down to this, or further when that is what showing the whole tree takes. */
const ZOOM_OUT_FLOOR = 0.2
/** A press that moves the view less than this (px) is a click, not a pan. */
const DRAG_THRESHOLD = 4
/** One click of the zoom buttons. */
const ZOOM_STEP = 1.25
/** Ctrl/⌘ + wheel zooms by e^(−deltaY px × this): one mouse notch (100 px) is ×1.22, a pinch event a few per mille. */
const WHEEL_ZOOM_RATE = 0.002
/** A wheel that counts in lines (Firefox, some mice) moves about this many px per line. */
const WHEEL_LINE_PX = 33

/** A wheel event's vertical delta in pixels, whichever unit the device reports it in. */
function wheelDeltaPx(e: WheelEvent, pageHeight: number): number {
  if (e.deltaMode === 1) return e.deltaY * WHEEL_LINE_PX
  if (e.deltaMode === 2) return e.deltaY * pageHeight
  return e.deltaY
}
/** Many separate roots get more columns, but never so many that fitting them shrinks cards below this. */
const MIN_READABLE_SCALE = 0.5
const MAX_SCALE = 2
/** Row width used before the canvas has been measured (and in tests). */
const DEFAULT_ROW_WIDTH = 1200

export function CoroutineTreeGraph({
  coroutines,
  onSelect,
  selectedNodeId,
  activity,
  reveal,
  childTotals,
  groups,
  onToggleGroup,
}: CoroutineTreeGraphProps) {
  const tree = useMemo(() => buildCoroutineTree(coroutines) as TreeNode[], [coroutines])
  // The badge counts what the state bar calls Active (CREATED or ACTIVE); a parent
  // waiting for its children is not running its body (spec S-6).
  const activeCount = useMemo(
    () => coroutines.filter(c => c.state === 'ACTIVE' || c.state === 'CREATED').length,
    [coroutines]
  )
  const nodesById = useMemo(() => {
    const map = new Map<string, TreeNode>()
    const walk = (n: TreeNode) => {
      map.set(n.id, n)
      n.children.forEach(walk)
    }
    tree.forEach(walk)
    return map
  }, [tree])

  const transformRef = useRef<ReactZoomPanPinchContentRef>(null)
  const [canvasSize, setCanvasSize] = useState({ width: 0, height: 0 })
  const canvasWidth = canvasSize.width
  const [isFullscreen, setIsFullscreen] = useState(false)
  // Fit the tree to the canvas until the user pans or zooms; "Fit to view" goes back to that.
  const [followFit, setFollowFit] = useState(true)
  // The look's card size; the layout reserves exactly this box for each card.
  const { width: cardWidth, height: cardHeight, fitMinZoom } = useLookCard()
  // "Fit to view" was pressed: fit the whole tree even below the look's fit floor.
  const [fitWhole, setFitWhole] = useState(false)
  const { look } = useLook()

  const layout = useMemo<TreeLayout>(
    () =>
      layoutTree(tree, {
        nodeWidth: cardWidth,
        nodeHeight: cardHeight,
        hGap: H_GAP,
        vGap: V_GAP,
        maxRowWidth: Math.max(
          cardWidth,
          chooseRowWidth(
            tree.length,
            canvasSize.width - 2 * PADDING,
            canvasSize.height - 2 * PADDING - TOOLBAR_INSET,
            { nodeWidth: cardWidth, nodeHeight: cardHeight, hGap: H_GAP, vGap: V_GAP, minReadableScale: MIN_READABLE_SCALE },
            DEFAULT_ROW_WIDTH,
          ),
        ),
      }),
    [tree, canvasSize, cardWidth, cardHeight]
  )

  // Ctrl/⌘ + wheel (and a trackpad pinch, which arrives as Ctrl + wheel) zooms around the cursor;
  // a plain wheel keeps scrolling the page. Not the library's wheel zoom: that adds a fixed amount
  // per event, which from a very small fitted scale is a jump of many times.
  const onWheelRef = useRef<(e: WheelEvent) => void>(() => {})

  // Track the canvas size: rows of roots wrap to it, and fitting scales to it. A callback
  // ref, because the canvas only mounts once there is something to draw.
  const detachCanvas = useRef<(() => void) | null>(null)
  const canvasRef = useCallback((el: HTMLDivElement | null) => {
    detachCanvas.current?.()
    detachCanvas.current = null
    if (!el) return
    const onWheel = (e: WheelEvent) => onWheelRef.current(e)
    // not passive: a zooming wheel must not also scroll or zoom the page
    el.addEventListener('wheel', onWheel, { passive: false })
    let observer: ResizeObserver | null = null
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(([entry]) => {
        if (entry) {
          const width = Math.round(entry.contentRect.width)
          const height = Math.round(entry.contentRect.height)
          setCanvasSize(prev => (prev.width === width && prev.height === height ? prev : { width, height }))
        }
      })
      observer.observe(el)
    }
    detachCanvas.current = () => {
      el.removeEventListener('wheel', onWheel)
      observer?.disconnect()
    }
  }, [])

  // The scale that shows the whole tree: its whole width however small that makes it, so a very
  // broad tree still fits, and its whole height down to half size (a long list of roots is
  // scrolled through rather than shrunk until it cannot be read).
  const contentWidth = layout.width + 2 * PADDING
  const contentHeight = layout.height + 2 * PADDING
  const fitScale =
    canvasWidth > 0
      ? Math.min(
          1,
          canvasWidth / contentWidth,
          Math.max(MIN_READABLE_SCALE, (canvasSize.height - TOOLBAR_INSET) / contentHeight),
        )
      : 1

  const minScale = Math.min(ZOOM_OUT_FLOOR, fitScale)
  // The view fit() last set: following stops once the view differs from it.
  const fitted = useRef<{ x: number; y: number; scale: number } | null>(null)

  // The look's fit floor (card.ts fitMinZoom; Classic has none): below it the
  // cards are too small to read, so the fit keeps the floor and frames the root
  // and the top of the tree. "Fit to view" still shows the whole tree.
  const floored = fitMinZoom !== undefined && !fitWhole && fitScale < fitMinZoom
  const firstRoot = layout.nodes.get(tree[0]?.id ?? '')

  const fit = useCallback(() => {
    const api = transformRef.current
    if (!api || canvasWidth === 0) return
    let view = { x: (canvasWidth - contentWidth * fitScale) / 2, y: TOOLBAR_INSET, scale: fitScale }
    if (floored && fitMinZoom !== undefined) {
      const scale = fitMinZoom
      const rootCentre = PADDING + (firstRoot?.x ?? 0) + cardWidth / 2
      // Centre the root, without leaving empty canvas beside the tree when it is wider than the view.
      const centred = canvasWidth / 2 - rootCentre * scale
      const x =
        contentWidth * scale <= canvasWidth
          ? (canvasWidth - contentWidth * scale) / 2
          : Math.min(0, Math.max(canvasWidth - contentWidth * scale, centred))
      view = { x, y: TOOLBAR_INSET, scale }
    }
    fitted.current = view
    api.setTransform(view.x, view.y, view.scale, 0)
  }, [canvasWidth, contentWidth, fitScale, floored, fitMinZoom, firstRoot?.x, cardWidth])

  useEffect(() => {
    if (followFit) fit()
  }, [followFit, fit, layout.height])

  // Find's reveal (S-12): centre the card once per key, at the look's readable zoom at least, and again while the
  // canvas settles (restoring the dock resizes it) until the user pans or zooms.
  const revealedKey = useRef<number | null>(null)
  const revealHeld = useRef(false)
  useEffect(() => {
    const api = transformRef.current
    if (!reveal || !api || canvasWidth === 0) return
    if (revealedKey.current === reveal.key && !revealHeld.current) return
    const pos = layout.nodes.get(reveal.id)
    if (!pos) return
    revealedKey.current = reveal.key
    revealHeld.current = true
    setFollowFit(false)
    const scale = Math.min(MAX_SCALE, Math.max(api.state.scale, fitMinZoom ?? MIN_READABLE_SCALE))
    const cx = PADDING + pos.x + cardWidth / 2
    const cy = PADDING + pos.y + cardHeight / 2
    api.setTransform(canvasWidth / 2 - cx * scale, canvasSize.height / 2 - cy * scale, scale, 0)
  }, [reveal, layout, canvasWidth, canvasSize.height, fitMinZoom, cardWidth, cardHeight])

  // Entering or leaving full screen changes the canvas size: fit again either way.
  const setFullscreen = useCallback((on: boolean) => {
    setIsFullscreen(on)
    setFollowFit(true)
  }, [])

  useEffect(() => {
    if (!isFullscreen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setFullscreen(false)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [isFullscreen, setFullscreen])

  const toggleFullscreen = () => setFullscreen(!isFullscreen)
  const fitToView = () => {
    revealHeld.current = false
    setFollowFit(true)
    // The fit that follows this render shows the whole tree (the effect below re-fits on the change).
    setFitWhole(true)
    if (!floored) fit()
  }
  const stopFollowing = () => setFollowFit(false)

  // Pans, wheel and pinch zoom, and the zoom buttons all end up here. A gesture that leaves the
  // view as fitted (a click, or zooming out past the whole-tree view) keeps following the tree.
  const onTransform = (_ref: ReactZoomPanPinchRef, view: { positionX: number; positionY: number; scale: number }) => {
    const f = fitted.current
    if (!f) return
    if (
      Math.abs(view.positionX - f.x) > DRAG_THRESHOLD ||
      Math.abs(view.positionY - f.y) > DRAG_THRESHOLD ||
      Math.abs(view.scale - f.scale) > f.scale * 1e-3
    ) {
      stopFollowing()
    }
  }

  // Zoom by [factor] keeping the canvas point [cx], [cy] where it is. Done here rather than with the
  // library's zoomIn/zoomOut, which round the target scale to two decimals and so cannot step
  // from a very small fit.
  // Applied at once, like every view change here (and pan inertia is off): an animated transform
  // in the library computes from where an earlier one had got to, and a later zero-length one does
  // not cancel it, so quick clicks would lose steps and a Fit could be undone afterwards.
  const zoomAt = (factor: number, cx: number, cy: number) => {
    const api = transformRef.current
    if (!api) return
    revealHeld.current = false
    const { positionX, positionY, scale } = api.state
    const next = Math.min(MAX_SCALE, Math.max(minScale, scale * factor))
    if (Math.abs(next - scale) <= scale * 1e-6) return
    api.setTransform(cx - ((cx - positionX) * next) / scale, cy - ((cy - positionY) * next) / scale, next, 0)
  }
  const zoomBy = (factor: number) => zoomAt(factor, canvasSize.width / 2, canvasSize.height / 2)
  onWheelRef.current = (e: WheelEvent) => {
    if (!(e.ctrlKey || e.metaKey)) return
    e.preventDefault()
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
    zoomAt(Math.exp(-wheelDeltaPx(e, rect.height) * WHEEL_ZOOM_RATE), e.clientX - rect.left, e.clientY - rect.top)
  }

  // A press only counts as a pan once the view has moved: until then it stays a click on a
  // card, and fitting keeps following the tree. A drag that ends on a card does not select it.
  const panStart = useRef<{ x: number; y: number } | null>(null)
  const dragged = useRef(false)
  const onPanningStart = (ref: ReactZoomPanPinchRef) => {
    revealHeld.current = false
    panStart.current = { x: ref.state.positionX, y: ref.state.positionY }
    dragged.current = false
  }
  const onPanning = (ref: ReactZoomPanPinchRef) => {
    const start = panStart.current
    if (!start || dragged.current) return
    if (Math.hypot(ref.state.positionX - start.x, ref.state.positionY - start.y) > DRAG_THRESHOLD) {
      dragged.current = true
    }
  }
  const selectOnClick = onSelect
    ? (id: string) => {
        if (dragged.current) {
          dragged.current = false
          return
        }
        onSelect(id)
      }
    : undefined

  if (coroutines.length === 0) {
    return (
      <div className="flex min-h-[400px] items-center justify-center text-default-400">
        No coroutines in this session yet.
      </div>
    )
  }

  return (
    <div
      ref={canvasRef}
      data-testid="graph-canvas"
      // Whose cards these are: the look's card.css keys on it. On the graph, not only on <html>,
      // so an export (which sets the page's look aside) keeps the cards that fit their size.
      data-card-look={look}
      data-fullscreen={isFullscreen ? 'true' : 'false'}
      // HTML cards over an SVG layer: export it as a picture, not as one of its svgs
      data-export-raster="true"
      // The main view sizes the canvas (S-1): it fills that box, however small
      // the dock leaves it — a minimum of its own would overflow the box.
      className={
        isFullscreen
          ? 'fixed inset-0 z-50 overflow-hidden bg-default-50'
          : 'relative h-full w-full overflow-hidden rounded-lg border border-default-200 bg-default-50'
      }
    >
      <TransformWrapper
        ref={transformRef}
        initialScale={1}
        minScale={minScale}
        maxScale={MAX_SCALE}
        limitToBounds={false}
        wheel={{ disabled: true }}
        // no zooming past minScale/maxScale and springing back: past the whole-tree view is nowhere
        disablePadding
        doubleClick={{ disabled: true }}
        velocityAnimation={{ disabled: true }}
        autoAlignment={{ disabled: true }}
        onPanningStart={onPanningStart}
        onPanning={onPanning}
        onTransform={onTransform}
      >
        {() => (
          <>
            {/* Controls */}
            <div className="absolute top-3 right-3 z-20 flex items-center gap-2">
              <span className="hidden rounded-md bg-default-100/80 px-2 py-1 text-xs text-default-500 backdrop-blur-sm md:inline">
                Drag to pan · Ctrl/⌘ + scroll to zoom
              </span>
              <Button
                isIconOnly
                size="sm"
                variant="flat"
                aria-label="Zoom in"
                title="Zoom in"
                onPress={() => zoomBy(ZOOM_STEP)}
              >
                <FiZoomIn />
              </Button>
              <Button
                isIconOnly
                size="sm"
                variant="flat"
                aria-label="Zoom out"
                title="Zoom out"
                onPress={() => zoomBy(1 / ZOOM_STEP)}
              >
                <FiZoomOut />
              </Button>
              <Button isIconOnly size="sm" variant="flat" aria-label="Fit to view" title="Fit to view" onPress={fitToView}>
                <FiCrosshair />
              </Button>
              <Button
                isIconOnly
                size="sm"
                variant="flat"
                aria-label={isFullscreen ? 'Exit full screen' : 'Full screen'}
                title={isFullscreen ? 'Exit full screen (Esc)' : 'Full screen'}
                onPress={toggleFullscreen}
              >
                {isFullscreen ? <FiMinimize2 /> : <FiMaximize2 />}
              </Button>
            </div>

            {/* Real-time activity indicator */}
            {activeCount > 0 && (
              <div className="absolute top-3 left-3 z-20 flex items-center gap-2 rounded-full bg-primary/10 px-4 py-2 text-sm font-semibold text-primary backdrop-blur-sm">
                <span className="h-2 w-2 rounded-full bg-primary" />
                <span>{activeCount} active</span>
              </div>
            )}

            <TransformComponent
              wrapperStyle={{ width: '100%', height: '100%' }}
              contentStyle={{ width: layout.width + 2 * PADDING, height: layout.height + 2 * PADDING }}
            >
              <div className="relative" style={{ width: layout.width + 2 * PADDING, height: layout.height + 2 * PADDING }}>
                <GraphEdges layout={layout} nodesById={nodesById} groups={groups} />
                {[...layout.nodes].map(([id, pos]) => {
                  const node = nodesById.get(id)!
                  const group = groups?.get(id)
                  return (
                    <div
                      key={id}
                      data-testid={`graph-node-${id}`}
                      // The state, for a look's CSS to colour the card by (Classic: unused); a group is no one state.
                      data-state={group ? 'GROUP' : node.state}
                      className="absolute"
                      style={{ left: pos.x + PADDING, top: pos.y + PADDING, width: cardWidth, height: cardHeight }}
                    >
                      {group ? (
                        <GroupCard
                          group={group}
                          onSelect={onSelect}
                          onClickSelect={selectOnClick}
                          selected={selectedNodeId === node.id}
                          onToggle={onToggleGroup}
                        />
                      ) : (
                        <NodeCard
                          node={node}
                          isRoot={!node.parentId || !nodesById.has(node.parentId)}
                          onSelect={onSelect}
                          onClickSelect={selectOnClick}
                          selected={selectedNodeId === node.id}
                          activity={activity?.get(id)}
                        />
                      )}
                      {node.children.length > 0 && (
                        <div
                          data-look-part="node-children"
                          className="absolute left-1/2 z-10 ml-3 whitespace-nowrap rounded-lg border border-default-200 bg-default-100/80 px-2 py-0.5 text-xs font-medium text-default-700 backdrop-blur-sm"
                          style={{ top: cardHeight + 6 }}
                        >
                          {childrenLabel(node.children.length, childTotals?.get(node.id))}
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
            </TransformComponent>
          </>
        )}
      </TransformWrapper>
    </div>
  )
}

/** "2 children", or "2 of 262 children" when the cap left some out. */
function childrenLabel(drawn: number, total: number | undefined): string {
  if (total !== undefined && total > drawn) return `${drawn.toLocaleString('en-US')} of ${total.toLocaleString('en-US')} children`
  return drawn === 1 ? '1 child' : `${drawn} children`
}

/** Every parent→child line, drawn from the same positions as the cards so a line always meets its card. */
function GraphEdges({
  layout,
  nodesById,
  groups,
}: {
  layout: TreeLayout
  nodesById: Map<string, TreeNode>
  groups?: ReadonlyMap<string, NameGroup>
}) {
  const shouldAnimate = useAnimationSlot()
  return (
    <svg
      data-testid="graph-edges"
      className="pointer-events-none absolute left-0 top-0"
      width={layout.width + 2 * PADDING}
      height={layout.height + 2 * PADDING}
      aria-hidden="true"
    >
      <g transform={`translate(${PADDING} ${PADDING})`}>
        {layout.edges.map(edge => {
          // A line into a group is neutral: a group is no one state.
          const child = groups?.has(edge.childId) ? undefined : nodesById.get(edge.childId)
          const running = !!child && isRunning(child.state)
          return (
            <path
              key={`${edge.parentId}->${edge.childId}`}
              data-testid="graph-edge"
              data-child={edge.childId}
              d={edge.path}
              fill="none"
              stroke={child ? getStateColors(child.state).hue : 'currentColor'}
              strokeOpacity={0.7}
              strokeWidth={2}
              strokeLinejoin="round"
              strokeDasharray={running ? '6 4' : undefined}
            >
              {running && shouldAnimate && (
                <animate attributeName="stroke-dashoffset" from="20" to="0" dur="1s" repeatCount="indefinite" />
              )}
            </path>
          )
        })}
      </g>
    </svg>
  )
}

interface NodeCardProps {
  node: TreeNode
  /** Drawn without a parent line: a root, or a coroutine whose parent was never captured. */
  isRoot: boolean
  /** Keyboard activation. */
  onSelect?: (id: string) => void
  /** Click activation: ignores the click that ends a drag. */
  onClickSelect?: (id: string) => void
  selected: boolean
  /** What it waits for or holds (S-6). */
  activity?: ActivityLine
}

function NodeCard({ node, isRoot, onSelect, onClickSelect, selected, activity }: NodeCardProps) {
  const colors = getStateColors(node.state)
  const shouldAnimate = useAnimationSlot()
  // Use FiLoader for ACTIVE (rotation animation) — override the default FiPlay
  const StateIcon = node.state === 'ACTIVE' ? FiLoader : colors.Icon
  const running = isRunning(node.state)

  const className =
    'relative h-full overflow-hidden rounded-2xl border-2 bg-content1 p-4 shadow-lg transition-shadow hover:shadow-2xl ' +
    colors.border +
    (selected ? ' ring-2 ring-primary' : onSelect ? ' cursor-pointer' : '')

  return (
    <div
      data-look-part="node"
      className={className}
      {...(onSelect
        ? {
            role: 'button',
            tabIndex: 0,
            'aria-label': `Open source for ${node.label || node.id}`,
            onClick: () => (onClickSelect ?? onSelect)(node.id),
            onKeyDown: (e: React.KeyboardEvent) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onSelect(node.id)
              }
            },
          }
        : {})}
    >
      {/* Tint for running states */}
      {shouldAnimate ? (
        <AnimatePresence>
          {running && (
            <motion.div
              data-look-part="node-tint"
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
        running && <div data-look-part="node-tint" className={`absolute inset-0 rounded-2xl ${colors.bgTint} opacity-30`} />
      )}

      <div data-look-part="node-body" className="relative z-10">
        {/* Header with Icon and Label */}
        <div data-look-part="node-head" className="mb-3 flex items-center gap-3">
          {shouldAnimate && node.state === 'ACTIVE' ? (
            <motion.div
              data-look-part="node-icon"
              className={`${colors.text} flex h-10 w-10 shrink-0 items-center justify-center rounded-full ${colors.iconBg}`}
              animate={{ rotate: 360 }}
              transition={{ rotate: { duration: 2, repeat: Infinity, ease: 'linear' } }}
            >
              <StateIcon className="h-5 w-5" />
            </motion.div>
          ) : (
            <div
              data-look-part="node-icon"
              className={`${colors.text} flex h-10 w-10 shrink-0 items-center justify-center rounded-full ${colors.iconBg}`}
            >
              <StateIcon className="h-5 w-5" />
            </div>
          )}
          <div data-look-part="node-text" className="min-w-0 flex-1">
            {/* Names like dashboard.notifications.unread have no spaces: let them break anywhere, over two lines. */}
            <div
              data-look-part="node-name"
              className="line-clamp-2 text-base font-bold leading-6 [overflow-wrap:anywhere]"
              title={node.label || node.id}
            >
              {node.label || node.id}
            </div>
            <div data-look-part="node-state" className="text-xs text-default-500">
              {stateLabel(node.state)}
              {node.state === 'ACTIVE' && <span className="ml-2 inline-block">●</span>}
            </div>
            {/* What it waits for or holds (S-6): a wait in the Suspended colour, a hold neutral. */}
            {activity && (
              <div
                data-testid="activity-line"
                data-look-part="node-activity"
                className={activity.waiting ? 'truncate text-xs text-warning' : 'truncate text-xs text-default-600'}
                title={activity.text}
              >
                {activity.text}
              </div>
            )}
          </div>
        </div>

        {/* Details */}
        <div data-look-part="node-details" className="space-y-1 text-sm">
          <DetailRow label="ID" value={node.id} />
          <DetailRow label="Job" value={node.jobId} />
          <DetailRow label="Scope" value={node.scopeId} />
          {/* A drawn line already shows the parent; say it only when the parent is not on the graph. */}
          {isRoot && node.parentId && <DetailRow label="Parent" value={node.parentId} />}
        </div>

        {/* Progress Bar for Active State */}
        {node.state === 'ACTIVE' && (
          <div data-look-part="node-progress" className="mt-3 h-1 overflow-hidden rounded-full bg-default-200">
            {shouldAnimate ? (
              <motion.div
                className="h-full bg-primary"
                animate={{ x: ['-100%', '100%'] }}
                transition={{ duration: 1.5, repeat: Infinity, ease: 'linear' }}
                style={{ width: '50%' }}
              />
            ) : (
              <div className="h-full bg-primary" style={{ width: '50%' }} />
            )}
          </div>
        )}
      </div>
    </div>
  )
}

interface GroupCardProps {
  group: NameGroup
  onSelect?: (id: string) => void
  onClickSelect?: (id: string) => void
  selected: boolean
  onToggle?: (id: string) => void
}

/** Siblings grouped by name (S-12): one card for them, its states in words, and Expand / Collapse. */
function GroupCard({ group, onSelect, onClickSelect, selected, onToggle }: GroupCardProps) {
  const size = group.memberIds.length
  return (
    <div
      data-look-part="node"
      data-kind="group"
      className={
        'relative h-full overflow-hidden rounded-2xl border-2 border-dashed border-default-400 bg-content1 p-4 shadow-lg' +
        (selected ? ' ring-2 ring-primary' : onSelect ? ' cursor-pointer' : '')
      }
      {...(onSelect
        ? {
            role: 'button',
            tabIndex: 0,
            'aria-label': `Group ${group.label}: ${size} coroutines`,
            onClick: () => (onClickSelect ?? onSelect)(group.id),
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
      <div data-look-part="node-body" className="relative z-10 space-y-1">
        <div data-look-part="group-name" className="truncate font-mono text-base font-bold" title={group.label}>
          {group.label}
        </div>
        <div data-look-part="group-count" className="text-xs text-default-500">
          {`group of ${size.toLocaleString('en-US')}`}
        </div>
        <div data-look-part="group-mix" className="text-xs text-default-600">
          {groupMix(group.counts)}
        </div>
        {group.below > 0 && (
          <div data-look-part="group-below" className="text-xs text-default-500">
            {`+${group.below.toLocaleString('en-US')} below them`}
          </div>
        )}
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
            className="mt-1 rounded-medium border border-default-300 px-2 py-0.5 text-xs text-default-700 hover:bg-default-100"
          >
            {group.expanded ? 'Collapse' : 'Expand'}
          </button>
        )}
      </div>
    </div>
  )
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center gap-2 text-default-600">
      <span className="font-semibold">{label}:</span>
      <span className="truncate font-mono text-xs" title={value}>
        {value}
      </span>
    </div>
  )
}
