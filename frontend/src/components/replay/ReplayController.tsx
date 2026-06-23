/**
 * ReplayController — control bar for event replay
 *
 * Provides play/pause, step-through, rewind, stop (seek-0 + paused),
 * jump-to-end, a speed selector, a progress slider, and an event counter.
 * ADR-017 keyboard shortcuts (Space/←/→/Home/End/1–4) drive the controller
 * while replay is active and are suppressed when focus is in a form field.
 *
 * A props-driven recording-state cluster (red pulsing dot + elapsed + Stop)
 * is shown while `isRecording`; the scripted recording pipeline (plan 02-08)
 * drives playback, so the playback controls are disabled in that mode.
 */

import { useEffect } from 'react'
import { Button, Slider, ButtonGroup, Dropdown, DropdownTrigger, DropdownMenu, DropdownItem } from '@heroui/react'
import { motion } from 'framer-motion'
import { FiPlay, FiPause, FiSkipBack, FiSkipForward, FiRotateCcw, FiSquare, FiFastForward } from 'react-icons/fi'
import { useReplayMotion } from '@/hooks/use-replay-motion'
import type { UseReplayReturn } from '@/hooks/use-replay'

interface ReplayControllerProps {
  replay: UseReplayReturn
  /** When true, render the recording cluster and disable playback controls. */
  isRecording?: boolean
  /** Elapsed recording time in ms (rendered as m:ss). */
  elapsedMs?: number
  /** Stop-and-discard the in-progress recording. */
  onStopRecording?: () => void
}

const SPEED_OPTIONS = [
  { key: '0.5', label: '0.5x' },
  { key: '1', label: '1x' },
  { key: '2', label: '2x' },
  { key: '5', label: '5x' },
]

/** ADR-017 number-key → speed mapping (1/2/3/4 → 0.5/1/2/5x). */
const SPEED_KEYS: Record<string, number> = {
  '1': 0.5,
  '2': 1,
  '3': 2,
  '4': 5,
}

/** Format an elapsed millisecond count as "m:ss" (e.g. 42000 → "0:42"). */
function formatElapsed(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}

/**
 * True when the currently focused element is a text-entry surface, so replay
 * shortcuts must be suppressed (T-02-13). Mirrors standard log-viewer behavior.
 */
function isFormFieldFocused(): boolean {
  const el = document.activeElement
  if (!el) return false
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  if ((el as HTMLElement).isContentEditable) return true
  return false
}

export function ReplayController({
  replay,
  isRecording = false,
  elapsedMs = 0,
  onStopRecording,
}: ReplayControllerProps) {
  const {
    isPlaying,
    currentIndex,
    speed,
    totalEvents,
    play,
    pause,
    stepForward,
    stepBack,
    reset,
    seekTo,
    setSpeed,
  } = replay

  const isEmpty = totalEvents === 0
  const lastIndex = Math.max(totalEvents - 1, 0)
  const controlsDisabled = isEmpty || isRecording
  const { progressWidth } = useReplayMotion({ currentIndex, totalEvents })

  const stop = () => {
    seekTo(0)
    pause()
  }
  const jumpToEnd = () => seekTo(lastIndex)

  // ADR-017 keyboard shortcuts — active only while the controller is mounted
  // (i.e. replay is active). Suppressed inside form fields; Space prevents
  // page scroll. When the Slider itself has focus its native key handling wins
  // (the Slider is an input-range, caught by isFormFieldFocused's INPUT guard).
  useEffect(() => {
    // The scripted recording pipeline owns playback; do not bind shortcuts.
    if (isRecording) return

    const handler = (e: KeyboardEvent) => {
      if (isFormFieldFocused()) return
      if (isEmpty) return

      switch (e.key) {
        case ' ':
          e.preventDefault()
          if (isPlaying) {
            pause()
          } else {
            play()
          }
          break
        case 'ArrowRight':
          e.preventDefault()
          stepForward()
          break
        case 'ArrowLeft':
          e.preventDefault()
          stepBack()
          break
        case 'Home':
          e.preventDefault()
          seekTo(0)
          break
        case 'End':
          e.preventDefault()
          seekTo(lastIndex)
          break
        case '1':
        case '2':
        case '3':
        case '4':
          setSpeed(SPEED_KEYS[e.key]!)
          break
      }
    }

    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [
    isRecording,
    isEmpty,
    isPlaying,
    lastIndex,
    play,
    pause,
    stepForward,
    stepBack,
    seekTo,
    setSpeed,
  ])

  return (
    <motion.div
      className="flex flex-col gap-3 rounded-lg bg-content1 p-4 shadow-sm"
      data-testid="replay-controller"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
    >
      {/* Progress slider */}
      <div className="flex items-center gap-3">
        <Slider
          aria-label="Replay progress"
          size="sm"
          step={1}
          minValue={0}
          maxValue={lastIndex}
          value={currentIndex}
          onChange={value => {
            const idx = Array.isArray(value) ? value[0] ?? 0 : value
            seekTo(idx)
          }}
          isDisabled={controlsDisabled}
          className="flex-1"
          color="primary"
        />
        <span className="min-w-[100px] text-right font-mono text-xs text-default-500">
          Event {isEmpty ? 0 : currentIndex + 1} / {totalEvents}
        </span>
      </div>

      {/* Controls row */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          {/* Recording cluster (D-23) — red dot + elapsed + Stop, lives in the
              controller chrome (never a panel overlay). */}
          {isRecording && (
            <div className="flex items-center gap-2" data-testid="recording-cluster">
              <span
                className="h-2 w-2 rounded-full bg-danger animate-pulse"
                aria-hidden
              />
              <span className="font-mono text-xs text-danger" data-testid="recording-elapsed">
                {formatElapsed(elapsedMs)}
              </span>
              <Button
                size="sm"
                color="danger"
                variant="flat"
                aria-label="Stop recording"
                onPress={onStopRecording}
              >
                Stop
              </Button>
            </div>
          )}

          <ButtonGroup size="sm" variant="flat">
            <Button
              isIconOnly
              aria-label="Rewind to start"
              onPress={reset}
              isDisabled={controlsDisabled}
            >
              <FiRotateCcw className="h-4 w-4" />
            </Button>
            <Button
              isIconOnly
              aria-label="Step back"
              onPress={stepBack}
              isDisabled={controlsDisabled || currentIndex === 0}
            >
              <FiSkipBack className="h-4 w-4" />
            </Button>
            <Button
              isIconOnly
              aria-label={isPlaying ? 'Pause' : 'Play'}
              color="primary"
              onPress={isPlaying ? pause : play}
              isDisabled={controlsDisabled}
            >
              {isPlaying ? (
                <FiPause className="h-4 w-4" />
              ) : (
                <FiPlay className="h-4 w-4" />
              )}
            </Button>
            <Button
              isIconOnly
              aria-label="Step forward"
              onPress={stepForward}
              isDisabled={controlsDisabled || currentIndex >= lastIndex}
            >
              <FiSkipForward className="h-4 w-4" />
            </Button>
            <Button
              isIconOnly
              aria-label="Jump to end"
              onPress={jumpToEnd}
              isDisabled={controlsDisabled || currentIndex >= lastIndex}
            >
              <FiFastForward className="h-4 w-4" />
            </Button>
            <Button
              isIconOnly
              aria-label="Stop replay"
              onPress={stop}
              isDisabled={controlsDisabled}
            >
              <FiSquare className="h-4 w-4" />
            </Button>
          </ButtonGroup>
        </div>

        {/* Speed selector */}
        <Dropdown>
          <DropdownTrigger>
            <Button size="sm" variant="bordered" isDisabled={isRecording}>
              {speed}x
            </Button>
          </DropdownTrigger>
          <DropdownMenu
            aria-label="Playback speed"
            selectionMode="single"
            selectedKeys={new Set([String(speed)])}
            onSelectionChange={keys => {
              const selected = Array.from(keys)[0]
              if (selected) setSpeed(Number(selected))
            }}
          >
            {SPEED_OPTIONS.map(opt => (
              <DropdownItem key={opt.key}>{opt.label}</DropdownItem>
            ))}
          </DropdownMenu>
        </Dropdown>
      </div>

      {/* Progress indicator bar — smoothly interpolated via spring MotionValue */}
      <div className="relative h-1.5 w-full overflow-hidden rounded-full bg-default-200">
        <motion.div
          className="h-full rounded-full bg-primary"
          style={{ width: progressWidth }}
        />
      </div>
    </motion.div>
  )
}
