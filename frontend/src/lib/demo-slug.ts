/** The URL slug of a demo: its catalog id with every `/` replaced by `-` (`flow/simple` → `flow-simple`). */
export function demoSlug(id: string): string {
  return id.replaceAll('/', '-')
}
