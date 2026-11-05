import { BASICS_GUIDES } from './basics'
import { CHANNELS_GUIDES } from './channels'
import { FLOW_GUIDES } from './flow'
import { PATTERNS_GUIDES } from './patterns'
import type { DemoGuide } from './types'

/** "How it works" guides by demo id. A demo without one still has its card and Run. */
export const GUIDES: Record<string, DemoGuide> = Object.fromEntries(
  [...BASICS_GUIDES, ...CHANNELS_GUIDES, ...FLOW_GUIDES, ...PATTERNS_GUIDES].map((guide) => [guide.demoId, guide]),
)
