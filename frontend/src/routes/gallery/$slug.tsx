import { createFileRoute } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { HowItWorksPage } from '@/components/gallery/HowItWorksPage'

/** "How it works" for one demo; the slug is its catalog id with `/` replaced by `-`. */
export const Route = createFileRoute('/gallery/$slug')({ component: HowItWorksRoute })

function HowItWorksRoute() {
  const { slug } = Route.useParams()
  return (
    <Layout>
      <HowItWorksPage slug={slug} />
    </Layout>
  )
}
