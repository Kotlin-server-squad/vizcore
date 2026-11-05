import { BASICS_GUIDES } from './basics'
import { FLOW_GUIDES } from './flow'
import type { DemoGuide } from './types'

/** "How it works" guides by demo id. A demo without one still has its card and Run. */
export const GUIDES: Record<string, DemoGuide> = Object.fromEntries([...BASICS_GUIDES, ...FLOW_GUIDES].map((guide) => [guide.demoId, guide]))
