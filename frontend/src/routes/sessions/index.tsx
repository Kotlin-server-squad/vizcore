import { createFileRoute } from '@tanstack/react-router'
import { useState } from 'react'
import { Layout } from '@/components/Layout'
import { SessionsSidebar } from '@/components/sessions/SessionsSidebar'
import { ConnectWizard } from '@/components/connect/ConnectWizard'
import { NewDemoSessionModal } from '@/components/sessions/NewDemoSessionModal'

export const Route = createFileRoute('/sessions/')({
  component: SessionsPage,
})

/**
 * The sessions home (Phase 08.5, Surface 003, PD-12). Renders the badged
 * sidebar-as-home (`SessionsSidebar`) — every row carries an unmistakable
 * LIVE/DEMO badge, live apps + demo scenarios grouped — and mounts the 3-step
 * `ConnectWizard`, opened by the sidebar's primary `+ Connect` action. The old
 * flat-grid `confirm()`-based create UI is gone; the create affordance is now the
 * wizard's copy-obvious connect flow.
 */
function SessionsPage() {
  const [wizardOpen, setWizardOpen] = useState(false)
  // The empty state's "Run a demo scenario instead" opens the picker in place
  // here too, so the CTA is never dead in either placement (#139).
  const [demoOpen, setDemoOpen] = useState(false)

  return (
    <Layout>
      <div className="container-custom py-8">
        <SessionsSidebar
          onConnect={() => setWizardOpen(true)}
          onNewDemo={() => setDemoOpen(true)}
        />
      </div>
      <ConnectWizard isOpen={wizardOpen} onClose={() => setWizardOpen(false)} />
      <NewDemoSessionModal isOpen={demoOpen} onClose={() => setDemoOpen(false)} />
    </Layout>
  )
}
