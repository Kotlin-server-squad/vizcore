import { useCapabilities } from '@/hooks/use-capabilities'
import { isPublicDemoVisitor } from '@/lib/public-demo'

/** Whether to show the page as a public demo's visitor sees it (see `isPublicDemoVisitor`). */
export function usePublicDemo(): boolean {
  const { data: capabilities } = useCapabilities()
  return isPublicDemoVisitor(capabilities)
}
