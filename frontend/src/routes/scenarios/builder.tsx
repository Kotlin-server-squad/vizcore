import { createFileRoute } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { ScenarioBuilder } from '@/components/ScenarioBuilder'
import { PublicDemoNote } from '@/components/sessions/PublicDemoNote'
import { usePublicDemo } from '@/hooks/use-public-demo'

export const Route = createFileRoute('/scenarios/builder')({
  component: ScenarioBuilderPage,
})

/** Exported so it can be rendered directly in tests without the generated file-route instance. */
export function ScenarioBuilderPage() {
  // A public demo server runs the built-in demos only: a visitor's own scenario is refused.
  const publicDemo = usePublicDemo()
  return (
    <Layout>
      {publicDemo ? (
        <div className="container-custom flex flex-col gap-3 py-8">
          <h1 className="text-xl font-semibold">Custom scenarios are not available here</h1>
          <PublicDemoNote />
        </div>
      ) : (
        <ScenarioBuilder />
      )}
    </Layout>
  )
}
