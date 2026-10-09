import { createFileRoute, redirect, useNavigate, useSearch } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { ComparisonView } from '@/components/comparison/ComparisonView'

/** Search params for the shareable /compare URL (D-10). */
export interface CompareSearch {
  a?: string
  b?: string
}

/**
 * Validates and normalizes the `?a=&b=` search params. Non-string / blank
 * values are dropped so a malformed shared URL never seeds a phantom picker
 * (T-02-10).
 */
export function validateSearch(search: Record<string, unknown>): CompareSearch {
  const normalize = (v: unknown): string | undefined =>
    typeof v === 'string' && v.trim().length > 0 ? v : undefined
  return {
    a: normalize(search.a),
    b: normalize(search.b),
  }
}

/**
 * Compare is an action, not a destination (D-3) — but `/compare?a=&b=` is a
 * shareable URL, so only the *bare* form is retired. A shared comparison link,
 * or a half-filled one, still resolves.
 */
export function shouldRedirectBareCompare(search: CompareSearch): boolean {
  return !search.a && !search.b
}

export const Route = createFileRoute('/compare/')({
  validateSearch,
  beforeLoad: ({ search }: { search: CompareSearch }) => {
    if (shouldRedirectBareCompare(search)) {
      throw redirect({ to: '/' })
    }
  },
  component: ComparePage,
})

export function ComparePage() {
  // `strict: false` lets the same component mount under a standalone test route
  // without binding to the generated file-route instance.
  const { a, b } = useSearch({ strict: false }) as CompareSearch
  const navigate = useNavigate()

  // The URL is the single source of truth for selection (shareable). Changing
  // a picker rewrites the matching search param, preserving the other.
  const setA = (id: string | undefined) =>
    navigate({ to: '/compare', search: (prev) => ({ ...prev, a: id }) })
  const setB = (id: string | undefined) =>
    navigate({ to: '/compare', search: (prev) => ({ ...prev, b: id }) })

  return (
    <Layout>
      <div className="container-custom py-8 space-y-6">
        <h1 className="text-2xl font-bold">Compare Sessions</h1>
        <ComparisonView
          a={a}
          b={b}
          onAChange={setA}
          onBChange={setB}
          onBrowseSessions={() => navigate({ to: '/sessions' })}
        />
      </div>
    </Layout>
  )
}
