import { createFileRoute, useNavigate } from '@tanstack/react-router'
import {
  Button,
  Card,
  CardBody,
  CardHeader,
  Chip,
  Spinner,
  Tab,
  Tabs,
} from '@heroui/react'
import {
  FiPlay,
  FiLayers,
  FiLock,
  FiGitBranch,
  FiZap,
} from 'react-icons/fi'
import { motion } from 'framer-motion'
import { hoverLift, tapPress } from '@/lib/animation-variants'
import { Layout } from '@/components/Layout'
import { useCreateSession } from '@/hooks/use-sessions'
import { useState } from 'react'

export const Route = createFileRoute('/gallery/')({
  component: GalleryPage,
})

// ---------------------------------------------------------------------------
// Curated scenario definitions
// ---------------------------------------------------------------------------

interface GalleryScenario {
  id: string
  name: string
  description: string
  category: GalleryCategory
  difficulty: 'beginner' | 'intermediate' | 'advanced'
  /** The backend scenario ID to invoke via the API */
  scenarioId: string
  tags: string[]
}

type GalleryCategory = 'basic' | 'patterns' | 'flow' | 'sync' | 'channel'

const CATEGORIES: { key: GalleryCategory; label: string; icon: React.ReactNode }[] = [
  { key: 'basic', label: 'Basic', icon: <FiPlay className="h-4 w-4" /> },
  { key: 'patterns', label: 'Patterns', icon: <FiGitBranch className="h-4 w-4" /> },
  { key: 'flow', label: 'Flow', icon: <FiZap className="h-4 w-4" /> },
  { key: 'sync', label: 'Sync', icon: <FiLock className="h-4 w-4" /> },
  { key: 'channel', label: 'Channel', icon: <FiLayers className="h-4 w-4" /> },
]

export const GALLERY_SCENARIOS: GalleryScenario[] = [
  // ===================== BASIC =====================
  {
    id: 'gallery-nested', name: 'Nested Coroutines',
    description: 'Parent coroutine spawns children. Observe structured concurrency: the parent waits for all children before completing.',
    category: 'basic', difficulty: 'beginner', scenarioId: 'nested',
    tags: ['nested', 'structured concurrency', 'parent-child'],
  },
  {
    id: 'gallery-parallel', name: 'Parallel Execution',
    description: 'Multiple coroutines running in parallel across dispatchers.',
    category: 'basic', difficulty: 'beginner', scenarioId: 'parallel',
    tags: ['parallel', 'concurrent'],
  },
  {
    id: 'gallery-deep-nesting', name: 'Deep Nesting',
    description: 'Deep hierarchy of nested coroutines (5 levels). Stress-tests the tree view.',
    category: 'basic', difficulty: 'beginner', scenarioId: 'deep-nesting',
    tags: ['deep', 'hierarchy', 'tree'],
  },
  {
    id: 'gallery-cancellation', name: 'Cancellation',
    description: 'Cancel a running coroutine and watch CancellationException propagate through the hierarchy.',
    category: 'basic', difficulty: 'intermediate', scenarioId: 'cancellation',
    tags: ['cancel', 'exception', 'lifecycle'],
  },
  {
    id: 'gallery-exception', name: 'Exception Handling',
    description: 'Demonstrates exception tracking, failure states, and error propagation in structured concurrency.',
    category: 'basic', difficulty: 'intermediate', scenarioId: 'exception',
    tags: ['exception', 'error', 'failure'],
  },
  {
    id: 'gallery-mixed', name: 'Mixed Sequential/Parallel',
    description: 'Combination of sequential and parallel execution patterns.',
    category: 'basic', difficulty: 'intermediate', scenarioId: 'mixed',
    tags: ['sequential', 'parallel', 'mixed'],
  },
  {
    id: 'gallery-dispatcher', name: 'Dispatcher Switching',
    description: 'Work across Dispatchers.Default and Dispatchers.IO. Feeds the Dispatcher Overview — see dispatcher selection and thread assignment.',
    category: 'basic', difficulty: 'intermediate', scenarioId: 'dispatcher',
    tags: ['dispatcher', 'threads', 'io', 'default'],
  },

  // ===================== PATTERNS =====================
  {
    id: 'gallery-retry', name: 'Retry with Backoff',
    description: 'Retry a failing operation 3 times with exponential backoff (100ms, 200ms, 400ms).',
    category: 'patterns', difficulty: 'intermediate', scenarioId: 'patterns/retry',
    tags: ['retry', 'backoff', 'resilience'],
  },
  {
    id: 'gallery-producer-consumer', name: 'Producer-Consumer',
    description: 'Producer sends items to a buffered channel, consumer processes them with delay. Shows backpressure.',
    category: 'patterns', difficulty: 'intermediate', scenarioId: 'patterns/producer-consumer',
    tags: ['producer', 'consumer', 'channel'],
  },
  {
    id: 'gallery-fan-out-fan-in', name: 'Fan-Out / Fan-In',
    description: 'One producer, 3 workers processing in parallel, results collected via channel.',
    category: 'patterns', difficulty: 'intermediate', scenarioId: 'patterns/fan-out-fan-in',
    tags: ['fan-out', 'fan-in', 'workers'],
  },
  {
    id: 'gallery-supervisor', name: 'Supervisor Pattern',
    description: 'One child fails but siblings continue. Shows error isolation with structured concurrency.',
    category: 'patterns', difficulty: 'intermediate', scenarioId: 'patterns/supervisor',
    tags: ['supervisor', 'error', 'isolation'],
  },
  {
    id: 'gallery-circuit-breaker', name: 'Circuit Breaker',
    description: 'After N consecutive failures, stop trying for a cooldown period. Resilience pattern.',
    category: 'patterns', difficulty: 'advanced', scenarioId: 'patterns/circuit-breaker',
    tags: ['circuit breaker', 'resilience', 'cooldown'],
  },
  {
    id: 'gallery-order-processing', name: 'Order Processing',
    description: 'E-commerce: validation, inventory, payment, DB, parallel notifications (~15s).',
    category: 'patterns', difficulty: 'advanced', scenarioId: 'order-processing',
    tags: ['realistic', 'e-commerce', 'parallel'],
  },
  {
    id: 'gallery-user-registration', name: 'User Registration',
    description: 'Registration: validation, DB check, create user, parallel setup, notifications (~20s).',
    category: 'patterns', difficulty: 'advanced', scenarioId: 'user-registration',
    tags: ['realistic', 'registration', 'parallel'],
  },
  {
    id: 'gallery-report-generation', name: 'Report Generation',
    description: 'Data pipeline: parallel API fetches, aggregation, PDF, parallel delivery (~25s).',
    category: 'patterns', difficulty: 'advanced', scenarioId: 'report-generation',
    tags: ['realistic', 'pipeline', 'data'],
  },

  // ===================== SYNC =====================
  {
    id: 'gallery-mutex-bank', name: 'Mutex: Bank Transfer',
    description: 'Two accounts with mutexes. Transfers require both locks in consistent order to prevent deadlock.',
    category: 'sync', difficulty: 'intermediate', scenarioId: 'sync/mutex/bank-transfer',
    tags: ['mutex', 'lock ordering', 'atomicity'],
  },
  {
    id: 'gallery-mutex-cache', name: 'Mutex: Read-Through Cache',
    description: 'Cache protected by mutex. Multiple coroutines read; cache miss triggers a write under lock.',
    category: 'sync', difficulty: 'intermediate', scenarioId: 'sync/mutex/cache',
    tags: ['mutex', 'cache', 'read-through'],
  },
  {
    id: 'gallery-semaphore-pool', name: 'Semaphore: Connection Pool',
    description: 'Limit concurrent DB connections using a Semaphore. Observe permits acquired and released.',
    category: 'sync', difficulty: 'intermediate', scenarioId: 'sync/semaphore/connection-pool',
    tags: ['semaphore', 'permits', 'pool'],
  },
  {
    id: 'gallery-semaphore-limiter', name: 'Semaphore: Rate Limiter',
    description: 'Limit concurrent API calls with a Semaphore. See how requests queue when limit is reached.',
    category: 'sync', difficulty: 'intermediate', scenarioId: 'sync/semaphore/rate-limiter',
    tags: ['semaphore', 'rate limit', 'throttle'],
  },
  {
    id: 'gallery-ecommerce', name: 'Combined: E-Commerce',
    description: 'Full e-commerce order with mutex for inventory and semaphore for payment gateway.',
    category: 'sync', difficulty: 'advanced', scenarioId: 'sync/combined/ecommerce',
    tags: ['mutex', 'semaphore', 'combined'],
  },
  {
    id: 'gallery-deadlock', name: 'Deadlock Detection',
    description: 'Two coroutines each hold a lock and wait for the other. Visualizer detects the deadlock.',
    category: 'sync', difficulty: 'advanced', scenarioId: 'sync/mutex/deadlock-demo',
    tags: ['deadlock', 'mutex', 'detection'],
  },

  // ===================== FLOW =====================
  {
    id: 'gallery-flow-simple', name: 'Simple Flow',
    description: 'Emit 5 values from a cold Flow and collect them. See FlowCreated, FlowValueEmitted, FlowCollectionCompleted.',
    category: 'flow', difficulty: 'beginner', scenarioId: 'flow/simple',
    tags: ['flow', 'emit', 'collect', 'cold'],
  },
  {
    id: 'gallery-flow-operators', name: 'Flow Operators',
    description: 'Chain map and filter operators. Watch values transform and get filtered through the pipeline.',
    category: 'flow', difficulty: 'intermediate', scenarioId: 'flow/operators',
    tags: ['flow', 'map', 'filter', 'operators'],
  },
  {
    id: 'gallery-stateflow', name: 'StateFlow',
    description: 'Mutable state with 2 observers. See StateFlowValueChanged events as the counter increments.',
    category: 'flow', difficulty: 'intermediate', scenarioId: 'flow/stateflow',
    tags: ['stateflow', 'state', 'observers'],
  },
  {
    id: 'gallery-sharedflow', name: 'SharedFlow',
    description: 'Broadcast events to 2 subscribers. See SharedFlowEmission and SharedFlowSubscription events.',
    category: 'flow', difficulty: 'advanced', scenarioId: 'flow/sharedflow',
    tags: ['sharedflow', 'broadcast', 'subscribers'],
  },

  // ===================== CHANNEL =====================
  {
    id: 'gallery-channel-rendezvous', name: 'Channel Rendezvous',
    description: 'Zero-buffer channel — sender and receiver must meet. Shows suspension on both sides.',
    category: 'channel', difficulty: 'beginner', scenarioId: 'channel-rendezvous',
    tags: ['channel', 'rendezvous', 'suspension'],
  },
  {
    id: 'gallery-channel-buffered', name: 'Buffered Channel',
    description: 'Buffered channel (capacity 3). Producer sends 5 items, consumer starts after delay.',
    category: 'channel', difficulty: 'intermediate', scenarioId: 'channel-buffered',
    tags: ['channel', 'buffer', 'backpressure'],
  },
  {
    id: 'gallery-channel-fan-out', name: 'Channel Fan-Out',
    description: 'One producer, 3 workers compete to receive. Each value goes to exactly one worker.',
    category: 'channel', difficulty: 'intermediate', scenarioId: 'channel-fan-out',
    tags: ['channel', 'fan-out', 'workers'],
  },
]

// ---------------------------------------------------------------------------
// Difficulty badge colors
// ---------------------------------------------------------------------------

const DIFFICULTY_COLOR: Record<GalleryScenario['difficulty'], 'success' | 'warning' | 'danger'> = {
  beginner: 'success',
  intermediate: 'warning',
  advanced: 'danger',
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

function GalleryPage() {
  const navigate = useNavigate()
  const createSession = useCreateSession()
  const [runningId, setRunningId] = useState<string | null>(null)
  const [activeCategory, setActiveCategory] = useState<GalleryCategory>('basic')

  const filteredScenarios = GALLERY_SCENARIOS.filter(
    (s) => s.category === activeCategory,
  )

  const handleRun = async (scenario: GalleryScenario) => {
    setRunningId(scenario.id)
    try {
      // Create session, then navigate to session page with run params.
      // The session page shows "Run Scenario" button — user clicks to start.
      const session = await createSession.mutateAsync(`gallery-${scenario.name.replace(/[/\\]/g, "-")}`)

      navigate({
        to: '/sessions/$sessionId',
        params: { sessionId: session.sessionId },
        search: {
          scenarioId: scenario.scenarioId,
          scenarioName: scenario.name,
        },
      })
    } catch (error) {
      // Session creation errors are surfaced via TanStack Query state;
      // log navigation failures so a broken flow is not silent.
      console.error('Gallery run failed:', error)
    } finally {
      setRunningId(null)
    }
  }

  return (
    <Layout>
      <div className="container-custom py-8">
        {/* Header */}
        <div className="mb-8">
          <h1 className="mb-2 text-3xl font-bold">Example Gallery</h1>
          <p className="max-w-2xl text-default-600">
            Curated coroutine scenarios organized by topic. Pick one, hit{' '}
            <strong>Run</strong>, and explore the visualization.
          </p>
        </div>

        {/* Category Tabs */}
        <Tabs
          aria-label="Scenario categories"
          selectedKey={activeCategory}
          onSelectionChange={(key) => setActiveCategory(key as GalleryCategory)}
          color="primary"
          variant="bordered"
          classNames={{ tabList: 'mb-6' }}
        >
          {CATEGORIES.map((cat) => (
            <Tab
              key={cat.key}
              title={
                <div className="flex items-center gap-2">
                  {cat.icon}
                  <span>{cat.label}</span>
                </div>
              }
            />
          ))}
        </Tabs>

        {/* Scenario Grid */}
        {filteredScenarios.length === 0 ? (
          <Card>
            <CardBody>
              <p className="text-center text-default-500">
                No scenarios in this category yet.
              </p>
            </CardBody>
          </Card>
        ) : (
          <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {filteredScenarios.map((scenario) => {
              const isRunning = runningId === scenario.id
              return (
                <motion.div
                  key={scenario.id}
                  whileHover={hoverLift}
                  whileTap={tapPress}
                >
                <Card className="flex flex-col">
                  <CardHeader className="flex flex-col items-start gap-2 pb-0">
                    <div className="flex w-full items-center justify-between">
                      <h3 className="text-lg font-semibold">
                        {scenario.name}
                      </h3>
                      <Chip
                        size="sm"
                        variant="flat"
                        color={DIFFICULTY_COLOR[scenario.difficulty]}
                      >
                        {scenario.difficulty}
                      </Chip>
                    </div>
                  </CardHeader>
                  <CardBody className="flex flex-1 flex-col justify-between gap-4">
                    <div>
                      <p className="mb-3 text-sm text-default-600">
                        {scenario.description}
                      </p>
                      <div className="flex flex-wrap gap-1">
                        {scenario.tags.map((tag) => (
                          <Chip key={tag} size="sm" variant="dot">
                            {tag}
                          </Chip>
                        ))}
                      </div>
                    </div>
                    <Button
                      color="primary"
                      variant="flat"
                      startContent={
                        isRunning ? (
                          <Spinner size="sm" />
                        ) : (
                          <FiZap className="h-4 w-4" />
                        )
                      }
                      isDisabled={isRunning || runningId !== null}
                      onPress={() => handleRun(scenario)}
                      className="mt-auto"
                      fullWidth
                    >
                      {isRunning ? 'Running...' : 'Run'}
                    </Button>
                  </CardBody>
                </Card>
                </motion.div>
              )
            })}
          </div>
        )}

        {/* Footer hint */}
        <div className="mt-10 flex items-center justify-center gap-2 text-sm text-default-400">
          <FiZap className="h-4 w-4" />
          <span>
            Each scenario creates a new session and executes the coroutine
            pattern on the backend.
          </span>
        </div>
      </div>
    </Layout>
  )
}
