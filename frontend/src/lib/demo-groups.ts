import type { Scenario } from '@/types/api'

/** Headings for a server that sends no `group` yet (before the full catalog). */
const CATEGORY_LABELS: Record<string, string> = {
  realistic: 'Real life',
  basic: 'Basics',
  channel: 'Channels',
  flow: 'Flow',
  pattern: 'Patterns',
  sync: 'Sync',
}

export interface DemoGroup {
  key: string
  label: string
  demos: Scenario[]
}

/**
 * Demos grouped as the server lists them: groups in order of first appearance,
 * demos in list order. Real-life demos come first when the server sends no groups.
 */
export function groupDemos(scenarios: Scenario[]): DemoGroup[] {
  const sorted = scenarios.some((s) => s.group)
    ? scenarios
    : [...scenarios].sort(
        (a, b) => Number(b.category === 'realistic') - Number(a.category === 'realistic'),
      )
  const groups = new Map<string, DemoGroup>()
  for (const scenario of sorted) {
    const category = scenario.category ?? 'basic'
    const key = scenario.group ?? category
    const label = scenario.groupLabel ?? CATEGORY_LABELS[category] ?? category
    const group = groups.get(key) ?? { key, label, demos: [] }
    group.demos.push(scenario)
    groups.set(key, group)
  }
  return [...groups.values()]
}
