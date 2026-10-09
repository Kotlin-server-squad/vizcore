import { createFileRoute, redirect } from '@tanstack/react-router'
import { Button, Card, CardBody, CardHeader, Spinner, Chip } from '@heroui/react'
import { FiPlay, FiPlusCircle, FiClock, FiZap } from 'react-icons/fi'
import { Layout } from '@/components/Layout'
import { useScenarios } from '@/hooks/use-scenarios'
import { useCreateSession } from '@/hooks/use-sessions'
import { useState, useMemo } from 'react'
import { useNavigate } from '@tanstack/react-router'
import type { Scenario } from '@/types/api'

export const Route = createFileRoute('/scenarios/')({
  // Scenarios is an action reachable from the sessions home, not a place (D-2).
  // The URL still resolves so old links do not 404.
  beforeLoad: () => {
    throw redirect({ to: '/' })
  },
  component: ScenariosPage,
})

function ScenariosPage() {
  const { data, isLoading } = useScenarios()
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [preparingScenario, setPreparingScenario] = useState<string | null>(null)

  // Separate scenarios by category
  const { realisticScenarios, basicScenarios } = useMemo(() => {
    if (!data?.scenarios) return { realisticScenarios: [], basicScenarios: [] }

    const realistic = data.scenarios.filter(s => s.category === 'realistic')
    const basic = data.scenarios.filter(s => s.category !== 'realistic')

    return { realisticScenarios: realistic, basicScenarios: basic }
  }, [data?.scenarios])

  const handlePrepare = async (scenarioId: string, scenarioName: string) => {
    setPreparingScenario(scenarioId)
    try {
      // Create a new session for the scenario
      const result = await createSession.mutateAsync(`scenario-${scenarioName}`)
      // Navigate to the session with scenario info
      navigate({
        to: '/sessions/$sessionId',
        params: { sessionId: result.sessionId },
        search: { scenarioId, scenarioName }
      })
    } finally {
      setPreparingScenario(null)
    }
  }

  const ScenarioCard = ({ scenario, featured = false }: { scenario: Scenario, featured?: boolean }) => (
    <Card
      key={scenario.id}
      className={featured ? 'border-2 border-primary/50 bg-primary/5' : ''}
    >
      <CardHeader className="flex flex-col items-start gap-2">
        <div className="flex w-full items-center justify-between">
          <h3 className="text-xl font-semibold">{scenario.name}</h3>
          {featured && (
            <Chip color="primary" variant="flat" size="sm" startContent={<FiZap className="w-3 h-3" />}>
              Real-world
            </Chip>
          )}
        </div>
        {scenario.duration && (
          <div className="flex items-center gap-1 text-sm text-default-500">
            <FiClock className="w-3 h-3" />
            <span>{scenario.duration}</span>
          </div>
        )}
      </CardHeader>
      <CardBody>
        <p className="mb-4 text-default-600 text-sm">{scenario.description}</p>
        <Button
          color={featured ? 'primary' : 'default'}
          variant={featured ? 'solid' : 'bordered'}
          startContent={<FiPlay />}
          onPress={() => handlePrepare(scenario.id, scenario.name)}
          isLoading={preparingScenario === scenario.id}
        >
          Prepare Scenario
        </Button>
      </CardBody>
    </Card>
  )

  return (
    <Layout>
      <div className="container-custom py-8">
        <div className="mb-8 flex items-center justify-between">
          <h1 className="text-3xl font-bold">Scenarios</h1>
          <Button
            color="success"
            startContent={<FiPlusCircle />}
            onPress={() => navigate({ to: '/scenarios/builder' })}
            size="lg"
          >
            Build Custom Scenario
          </Button>
        </div>

        {isLoading ? (
          <div className="flex items-center justify-center py-20">
            <Spinner size="lg" />
          </div>
        ) : !data?.scenarios || data.scenarios.length === 0 ? (
          <Card>
            <CardBody>
              <p className="text-center text-default-500">No scenarios available.</p>
            </CardBody>
          </Card>
        ) : (
          <div className="space-y-8">
            {/* Realistic Scenarios Section */}
            {realisticScenarios.length > 0 && (
              <section>
                <div className="mb-4">
                  <h2 className="text-2xl font-bold flex items-center gap-2">
                    <FiZap className="text-primary" />
                    Real-World Scenarios
                  </h2>
                  <p className="text-default-500 mt-1">
                    Realistic service simulations with 2-5 second delays for learning.
                    Watch how coroutines handle API calls, database operations, and parallel notifications.
                  </p>
                </div>
                <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
                  {realisticScenarios.map(scenario => (
                    <ScenarioCard key={scenario.id} scenario={scenario} featured />
                  ))}
                </div>
              </section>
            )}

            {/* Basic Scenarios Section */}
            {basicScenarios.length > 0 && (
              <section>
                <div className="mb-4">
                  <h2 className="text-2xl font-bold">Basic Patterns</h2>
                  <p className="text-default-500 mt-1">
                    Fundamental coroutine patterns: nesting, parallelism, cancellation, and exception handling.
                  </p>
                </div>
                <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
                  {basicScenarios.map(scenario => (
                    <ScenarioCard key={scenario.id} scenario={scenario} />
                  ))}
                </div>
              </section>
            )}
          </div>
        )}
      </div>
    </Layout>
  )
}

