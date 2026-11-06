import { useEffect, useRef, useState } from 'react'
import { Card, CardBody, CardHeader, Chip, Divider } from '@heroui/react'
import { motion, AnimatePresence } from 'framer-motion'
import { FiRadio, FiHash } from 'react-icons/fi'
import { useChannelEvents } from '@/hooks/use-channel-events'
import { ChannelBufferGauge } from './ChannelBufferGauge'
import { ChannelTimeline } from './ChannelTimeline'
import { ChannelProducerConsumer } from './ChannelProducerConsumer'
import type { VizEvent } from '@/types/api'

interface ChannelPanelProps {
  sessionId: string
  /** The events the page shows (live, paused or at the replay cursor); the stored history when absent. */
  events?: readonly VizEvent[]
  /** The channel the page selected (S-2). While set it is the one shown: the page holds one selection. */
  focusChannelId?: string | null
  /**
   * Picking a channel here selects it on the page (S-2). Without it (replay,
   * the shared view) the pick stays local to this panel.
   */
  onSelectChannel?: (channelId: string) => void
  /** Changes with every selection, so selecting the same channel again scrolls to it again. */
  revealKey?: unknown
}

export function ChannelPanel({
  sessionId,
  events,
  focusChannelId = null,
  onSelectChannel,
  revealKey,
}: ChannelPanelProps) {
  const { channels, eventsByChannel, bufferStates } = useChannelEvents(sessionId, events)
  const [pickedChannelId, setPickedChannelId] = useState<string | null>(null)
  const pick = (channelId: string) => {
    setPickedChannelId(channelId)
    onSelectChannel?.(channelId)
  }

  // Bring the page's channel into view and focus it (a keyboard user lands on it); its section was unfolded
  // in the same render.
  const listRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!focusChannelId) return
    const el = listRef.current?.querySelector(`[data-testid="channel-selector-${CSS.escape(focusChannelId)}"]`)
    el?.scrollIntoView?.({ block: 'nearest' })
    if (el instanceof HTMLElement) el.focus({ preventScroll: true })
  }, [focusChannelId, revealKey])

  // The page's selection wins; then the last pick here; then the first channel.
  const wanted = focusChannelId ?? pickedChannelId
  const effectiveSelectedId =
    wanted && channels.some((c) => c.channelId === wanted)
      ? wanted
      : channels.length > 0
        ? channels[0]!.channelId
        : null

  if (channels.length === 0) {
    return (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.4 }}
      >
        <Card className="mt-2" data-testid="channel-panel-empty">
          <CardBody>
            <div className="text-center text-default-400 py-8">
              <FiRadio className="w-8 h-8 mx-auto mb-3 opacity-50" />
              <p className="text-lg font-semibold mb-2">No Channel Activity</p>
              <p className="text-sm">
                Channel events will appear here once send/receive operations occur.
              </p>
            </div>
          </CardBody>
        </Card>
      </motion.div>
    )
  }

  const selectedChannel = channels.find((c) => c.channelId === effectiveSelectedId) ?? null
  const selectedEvents = effectiveSelectedId
    ? eventsByChannel.get(effectiveSelectedId) ?? []
    : []
  const selectedBuffer = effectiveSelectedId
    ? bufferStates.get(effectiveSelectedId) ?? null
    : null

  return (
    <motion.div
      className="space-y-4 mt-2"
      data-testid="channel-panel"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.4 }}
    >
      {/* Channel list */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <FiHash className="w-4 h-4" />
            <h3 className="font-semibold">Channels ({channels.length})</h3>
          </div>
        </CardHeader>
        <CardBody>
          <div ref={listRef} className="flex flex-wrap gap-2" data-testid="channel-list">
            {channels.map((ch, index) => {
              const isSelected = ch.channelId === effectiveSelectedId
              const evtCount = eventsByChannel.get(ch.channelId)?.length ?? 0
              return (
                <motion.button
                  key={ch.channelId}
                  onClick={() => pick(ch.channelId)}
                  className={`flex items-center gap-2 px-3 py-2 rounded-lg border transition-colors ${
                    isSelected
                      ? 'border-primary bg-primary/10'
                      : 'border-default-200 hover:bg-default-100'
                  }${ch.channelId === focusChannelId ? ' ring-2 ring-primary' : ''}`}
                  data-testid={`channel-selector-${ch.channelId}`}
                  // The page's selection (S-2), ringed like a selected lock.
                  data-focused={ch.channelId === focusChannelId ? 'true' : 'false'}
                  aria-current={ch.channelId === focusChannelId ? 'true' : undefined}
                  aria-pressed={isSelected}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.3, delay: index * 0.05 }}
                  whileHover={{ scale: 1.05 }}
                  whileTap={{ scale: 0.95 }}
                >
                  <div
                    className={`w-2 h-2 rounded-full ${
                      ch.isClosed ? 'bg-danger' : 'bg-success'
                    }`}
                  />
                  <span className="text-sm font-mono">
                    {ch.name || ch.channelId}
                  </span>
                  <Chip size="sm" variant="flat">
                    {evtCount}
                  </Chip>
                </motion.button>
              )
            })}
          </div>
        </CardBody>
      </Card>

      {/* Selected channel detail */}
      <AnimatePresence mode="wait">
        {selectedChannel && (
          <motion.div
            key={effectiveSelectedId}
            className="space-y-4"
            initial={{ opacity: 0, x: 20 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -20 }}
            transition={{ duration: 0.3 }}
          >
            {/* Buffer gauge */}
            <Card>
              <CardHeader>
                <h4 className="font-semibold">
                  Buffer State: {selectedChannel.name || selectedChannel.channelId}
                </h4>
              </CardHeader>
              <CardBody>
                <ChannelBufferGauge
                  currentSize={selectedBuffer?.currentSize ?? selectedChannel.currentSize}
                  capacity={selectedBuffer?.capacity ?? selectedChannel.capacity}
                  channelType={selectedChannel.channelType}
                  isClosed={selectedChannel.isClosed}
                />
              </CardBody>
            </Card>

            {/* Producer / Consumer flow diagram */}
            <Card>
              <CardHeader>
                <h4 className="font-semibold">Producer / Consumer Flow</h4>
              </CardHeader>
              <CardBody>
                <ChannelProducerConsumer channel={selectedChannel} />
              </CardBody>
            </Card>

            {/* Timeline */}
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between w-full">
                  <h4 className="font-semibold">Event Timeline</h4>
                  <Chip size="sm" variant="flat" color="primary">
                    {selectedEvents.length} events
                  </Chip>
                </div>
              </CardHeader>
              <Divider />
              <CardBody>
                <ChannelTimeline events={selectedEvents} />
              </CardBody>
            </Card>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  )
}
