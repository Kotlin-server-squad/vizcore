import { useMemo, useState } from 'react'
import {
  Button,
  Modal,
  ModalBody,
  ModalContent,
  ModalHeader,
  Spinner,
} from '@heroui/react'
import { FiClock, FiPlay } from 'react-icons/fi'
import { useNavigate } from '@tanstack/react-router'
import { useScenarios } from '@/hooks/use-scenarios'
import { useCreateSession } from '@/hooks/use-sessions'
import type { Scenario } from '@/types/api'

/**
 * "New demo session" — the re-hosted scenario picker (D-2).
 *
 * Scenarios stopped being a destination, but starting a demo session is still a
 * real action, so the picker moves into the sessions home as a modal. The
 * create-and-navigate behaviour is lifted verbatim from the retired
 * `/scenarios` route: mint a `scenario-`-prefixed session (which is what
 * `deriveSessionKind` keys the DEMO badge off) then open it.
 */
export function NewDemoSessionModal({
  isOpen,
  onClose,
}: {
  isOpen: boolean
  onClose: () => void
}) {
  const { data, isLoading } = useScenarios()
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [preparing, setPreparing] = useState<string | null>(null)

  const { realistic, basic } = useMemo(() => {
    const all = data?.scenarios ?? []
    return {
      realistic: all.filter((s) => s.category === 'realistic'),
      basic: all.filter((s) => s.category !== 'realistic'),
    }
  }, [data?.scenarios])

  const start = async (scenario: Scenario) => {
    setPreparing(scenario.id)
    try {
      const result = await createSession.mutateAsync(`scenario-${scenario.name}`)
      onClose()
      navigate({
        to: '/sessions/$sessionId',
        params: { sessionId: result.sessionId },
        search: { scenarioId: scenario.id, scenarioName: scenario.name },
      })
    } finally {
      setPreparing(null)
    }
  }

  // No per-row "Real-world" chip — the section heading already says it, and
  // repeating it on every row in the group is noise.
  const Row = ({ scenario }: { scenario: Scenario }) => (
    <div className="flex items-center gap-3 rounded-medium border border-default-200 p-3">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium">{scenario.name}</span>
        </div>
        {scenario.duration && (
          <div className="mt-1 flex items-center gap-1 text-xs text-default-500">
            <FiClock className="h-3 w-3" />
            <span>{scenario.duration}</span>
          </div>
        )}
      </div>
      <Button
        color="primary"
        size="sm"
        startContent={<FiPlay />}
        isLoading={preparing === scenario.id}
        onPress={() => void start(scenario)}
      >
        Start
      </Button>
    </div>
  )

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="2xl" scrollBehavior="inside">
      <ModalContent>
        <ModalHeader className="flex flex-col gap-1">
          <span>New demo session</span>
          <span className="text-sm font-normal text-default-500">
            Runs inside vizcore — nothing of yours is involved.
          </span>
        </ModalHeader>
        <ModalBody className="pb-6">
          {isLoading ? (
            <div className="flex justify-center py-12">
              <Spinner size="sm" />
            </div>
          ) : (
            <div className="flex flex-col gap-4">
              {realistic.length > 0 && (
                <section className="flex flex-col gap-2">
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-default-500">
                    Real-world
                  </h3>
                  {realistic.map((s) => (
                    <Row key={s.id} scenario={s} />
                  ))}
                </section>
              )}
              {basic.length > 0 && (
                <section className="flex flex-col gap-2">
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-default-500">
                    Patterns
                  </h3>
                  {basic.map((s) => (
                    <Row key={s.id} scenario={s} />
                  ))}
                </section>
              )}
            </div>
          )}
        </ModalBody>
      </ModalContent>
    </Modal>
  )
}
