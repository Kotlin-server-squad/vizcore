/**
 * ExportMenu — ADR-018 export dropdown (EXPT-01 / EXPT-02 / D-22).
 *
 * Renders a HeroUI Dropdown (mirrors ReplayController's Dropdown idiom) with:
 *   - "Export view as PNG"   (FiImage)   — always
 *   - "Export graph as SVG"  (FiCode)    — only when the active panel has an
 *                                           `<svg>` root (findSvgRoot, D-21/OQ-2)
 *   - "Export events as JSON"(FiFileText)— always
 *   - divider
 *   - "Record replay as video"(FiVideo)  — always; DISABLED with a tooltip when
 *                                           MediaRecorder/codec unsupported (D-25).
 *
 * Each export handler builds the locked filename, runs the matching lib export,
 * then surfaces a success/error toast (ADR-018 copy). The Record item is a
 * placeholder here: its `onRecord` prop is wired by plan 02-08.
 */

import { useCallback, useState } from 'react'
import {
  Button,
  Dropdown,
  DropdownTrigger,
  DropdownMenu,
  DropdownItem,
  Tooltip,
} from '@heroui/react'
import { FiDownload, FiImage, FiCode, FiFileText, FiVideo } from 'react-icons/fi'
import { exportToPng } from '@/lib/export-png'
import { exportToSvg, findSvgRoot } from '@/lib/export-svg'
import { exportEventsToJson } from '@/lib/export-json'
import {
  buildExportFilename,
  type ExportPanel,
} from '@/lib/export-filename'
import { toastSuccess, toastError } from '@/lib/toast'

const VIDEO_UNSUPPORTED_COPY =
  'Video recording is not supported in this browser'

/** Locked D-25 codec cascade — null when none supported (Safari → disabled). */
function isVideoRecordingSupported(): boolean {
  if (typeof MediaRecorder === 'undefined') return false
  const isTypeSupported = MediaRecorder.isTypeSupported
  if (typeof isTypeSupported !== 'function') return true
  return [
    'video/webm;codecs=vp9',
    'video/webm;codecs=vp8',
    'video/webm',
  ].some((t) => isTypeSupported(t))
}

export interface ExportMenuProps {
  /** Getter for the active visualization panel element (captured lazily). */
  getPanelEl: () => HTMLElement | null
  /** Session identifier used in the filename + PNG header. */
  sessionId: string
  /** Session display name shown in the PNG info header (D-08). */
  sessionName: string
  /** The normalized event array exported as JSON (D-22) + header count. */
  events: readonly unknown[]
  /** The active panel key (tree/graph/threads/timeline/events/replay). */
  panel: ExportPanel
  /**
   * Scripted-recording trigger — wired by plan 02-08. When omitted the Record
   * item is enabled (if supported) but its press is a no-op placeholder.
   */
  onRecord?: () => void
}

export function ExportMenu({
  getPanelEl,
  sessionId,
  sessionName,
  events,
  panel,
  onRecord,
}: ExportMenuProps) {
  const [isExporting, setIsExporting] = useState(false)

  const panelEl = getPanelEl()
  const svgRoot = panelEl ? findSvgRoot(panelEl) : null
  const videoSupported = isVideoRecordingSupported()

  const runExport = useCallback(
    async (fn: () => void | Promise<void>, filename: string) => {
      setIsExporting(true)
      try {
        await fn()
        toastSuccess(`Exported ${filename}`)
      } catch (error) {
        const reason = error instanceof Error ? error.message : 'unknown error'
        toastError(
          `Export failed — ${reason}. Try again, or use PNG export for this panel.`
        )
      } finally {
        setIsExporting(false)
      }
    },
    []
  )

  const handlePng = useCallback(() => {
    const el = getPanelEl()
    if (!el) return
    const filename = buildExportFilename(sessionId, panel, 'png')
    void runExport(
      () => exportToPng(el, filename, { sessionName, eventCount: events.length }),
      filename
    )
  }, [getPanelEl, sessionId, panel, sessionName, events.length, runExport])

  const handleSvg = useCallback(() => {
    const el = getPanelEl()
    const svg = el ? findSvgRoot(el) : null
    if (!svg) return
    const filename = buildExportFilename(sessionId, panel, 'svg')
    void runExport(() => exportToSvg(svg, filename), filename)
  }, [getPanelEl, sessionId, panel, runExport])

  const handleJson = useCallback(() => {
    const filename = buildExportFilename(sessionId, panel, 'json')
    void runExport(() => exportEventsToJson(events, filename), filename)
  }, [sessionId, panel, events, runExport])

  const handleRecord = useCallback(() => {
    onRecord?.()
  }, [onRecord])

  // Build the items list; SVG item is conditional (D-21).
  const items = [
    <DropdownItem
      key="png"
      startContent={<FiImage aria-hidden />}
      data-testid="export-png"
      onPress={handlePng}
    >
      Export view as PNG
    </DropdownItem>,
    ...(svgRoot
      ? [
          <DropdownItem
            key="svg"
            startContent={<FiCode aria-hidden />}
            data-testid="export-svg"
            onPress={handleSvg}
          >
            Export graph as SVG
          </DropdownItem>,
        ]
      : []),
    <DropdownItem
      key="json"
      startContent={<FiFileText aria-hidden />}
      data-testid="export-json"
      onPress={handleJson}
    >
      Export events as JSON
    </DropdownItem>,
    <DropdownItem
      key="record"
      showDivider
      startContent={<FiVideo aria-hidden />}
      data-testid="export-record"
      isReadOnly={!videoSupported}
      className={!videoSupported ? 'opacity-50 cursor-not-allowed' : undefined}
      onPress={videoSupported ? handleRecord : undefined}
    >
      {videoSupported ? (
        'Record replay as video'
      ) : (
        <Tooltip content={VIDEO_UNSUPPORTED_COPY}>
          <span aria-label={VIDEO_UNSUPPORTED_COPY}>Record replay as video</span>
        </Tooltip>
      )}
    </DropdownItem>,
  ]

  return (
    <Dropdown>
      <DropdownTrigger>
        <Button
          size="sm"
          variant="flat"
          isLoading={isExporting}
          startContent={!isExporting ? <FiDownload aria-hidden /> : undefined}
          aria-label="Export"
          data-testid="export-menu-trigger"
        >
          Export
        </Button>
      </DropdownTrigger>
      <DropdownMenu
        aria-label="Export options"
        disabledKeys={
          isExporting
            ? ['png', 'svg', 'json', 'record']
            : videoSupported
              ? []
              : ['record']
        }
      >
        {items}
      </DropdownMenu>
    </Dropdown>
  )
}
