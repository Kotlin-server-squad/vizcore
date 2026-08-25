import { useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { SessionsHome } from '@/components/sessions/SessionsHome'
import { ConnectWizard } from '@/components/connect/ConnectWizard'
import { NewDemoSessionModal } from '@/components/sessions/NewDemoSessionModal'
import { CompareOverlay } from '@/components/sessions/CompareOverlay'

export const Route = createFileRoute('/')({
  component: HomePage,
})

/**
 * The product's front door (D-1). Previously a marketing hero with feature
 * cards; now the badged sessions list, because the first screen should be the
 * way into the work rather than an advertisement for it.
 *
 * Exported so it can be rendered directly in tests without binding to the
 * generated file-route instance.
 */
export function HomePage() {
  const [wizardOpen, setWizardOpen] = useState(false)
  const [demoOpen, setDemoOpen] = useState(false)
  const [compareOpen, setCompareOpen] = useState(false)

  return (
    <Layout>
      <div className="container-custom py-8">
        <SessionsHome
          onConnect={() => setWizardOpen(true)}
          onNewDemo={() => setDemoOpen(true)}
          onCompare={() => setCompareOpen(true)}
        />
      </div>
      <ConnectWizard isOpen={wizardOpen} onClose={() => setWizardOpen(false)} />
      <NewDemoSessionModal isOpen={demoOpen} onClose={() => setDemoOpen(false)} />
      <CompareOverlay isOpen={compareOpen} onClose={() => setCompareOpen(false)} />
    </Layout>
  )
}
