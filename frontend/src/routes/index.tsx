import { useEffect, useState } from 'react'
import { createFileRoute } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { SessionsHome } from '@/components/sessions/SessionsHome'
import { ConnectWizard } from '@/components/connect/ConnectWizard'
import { NewDemoSessionModal } from '@/components/sessions/NewDemoSessionModal'
import { CompareOverlay } from '@/components/sessions/CompareOverlay'
import { DemoStartAlert } from '@/components/demos/DemoStartAlert'
import { HomeHero } from '@/components/home/HomeHero'
import { RealLifeStories } from '@/components/home/RealLifeStories'
import { ORDER_STORY_DEMO } from '@/content/home/order-story'
import { useScenarios } from '@/hooks/use-scenarios'
import { useSessions } from '@/hooks/use-sessions'
import { useStartDemo } from '@/hooks/use-start-demo'
import { rememberHadApps, rememberedHadApps } from '@/lib/front-door-memory'
import { deriveSessionKind } from '@/lib/session-kind'
import { usePublicDemo } from '@/hooks/use-public-demo'

export const Route = createFileRoute('/')({
  component: HomePage,
})

/**
 * The product's front door. It shows what vizcore is for before it asks for
 * anything: a hero whose main button starts the order demo with one click, a
 * still picture of that story, the Real life demos with both their endings,
 * and the badged sessions list (D-1).
 *
 * A visitor whose app is in the list came for it: the hero drops its picture
 * and the list sits right under it, above the stories. Without one the list
 * comes last. Demo sessions do not count: with auth off the list is the whole
 * server's, and other visitors' demo runs must not take the front door away.
 * Nothing runs on page load; a demo starts only on a click.
 *
 * Exported so it can be rendered directly in tests without binding to the
 * generated file-route instance.
 */
export function HomePage() {
  const [wizardOpen, setWizardOpen] = useState(false)
  const [demoOpen, setDemoOpen] = useState(false)
  const [compareOpen, setCompareOpen] = useState(false)
  // A public demo's visitor cannot connect an app: no Connect, no wizard.
  const publicDemo = usePublicDemo()

  // One demo start for the whole page: the hero and the cards take turns.
  const demoStart = useStartDemo()
  const { start, preparing, preparingVariant, startError } = demoStart
  const { data: catalog } = useScenarios()
  // As the server lists it once the list is in; the same demo by id before that.
  const checkout = catalog?.scenarios.find((s) => s.id === ORDER_STORY_DEMO.id) ?? ORDER_STORY_DEMO
  const startingCheckout = preparing === checkout.id && !preparingVariant

  const { data: sessions } = useSessions()
  // Until the list is in, as it was on the last visit: the page must not jump
  // under a returning visitor when it loads.
  const [hadApps] = useState(rememberedHadApps)
  const hasApps = sessions ? sessions.some((s) => deriveSessionKind(s) !== 'demo') : hadApps
  useEffect(() => {
    if (sessions) rememberHadApps(hasApps)
  }, [sessions, hasApps])

  const sessionsList = (
    <SessionsHome
      onConnect={() => setWizardOpen(true)}
      onNewDemo={() => setDemoOpen(true)}
      // The dialog has its own start: not while this page's is under way.
      isNewDemoDisabled={preparing !== null}
      onCompare={() => setCompareOpen(true)}
      publicDemo={publicDemo}
    />
  )

  return (
    <Layout>
      <div className="vz-still-when-reduced container-custom flex flex-col gap-10 py-8">
        <HomeHero
          compact={hasApps}
          isStarting={startingCheckout}
          isDisabled={preparing !== null && !startingCheckout}
          onWatch={() => void start(checkout)}
          // A public demo's visitor cannot connect an app: the hero offers the demo only.
          onConnect={publicDemo ? undefined : () => setWizardOpen(true)}
        />
        {startError && <DemoStartAlert startError={startError} />}
        {hasApps && sessionsList}
        <RealLifeStories demoStart={demoStart} />
        {!hasApps && sessionsList}
      </div>
      {!publicDemo && <ConnectWizard isOpen={wizardOpen} onClose={() => setWizardOpen(false)} />}
      <NewDemoSessionModal isOpen={demoOpen} onClose={() => setDemoOpen(false)} />
      <CompareOverlay isOpen={compareOpen} onClose={() => setCompareOpen(false)} />
    </Layout>
  )
}
