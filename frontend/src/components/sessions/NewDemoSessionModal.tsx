import { useMemo } from 'react'
import {
  Button,
  Modal,
  ModalBody,
  ModalContent,
  ModalHeader,
  Spinner,
} from '@heroui/react'
import { FiClock, FiPlay } from 'react-icons/fi'
import { useScenarios } from '@/hooks/use-scenarios'
import { groupDemos } from '@/lib/demo-groups'
import { useStartDemo } from '@/hooks/use-start-demo'
import type { Scenario } from '@/types/api'
import { DemoStartAlert } from '../demos/DemoStartAlert'
import { ErrorState } from '../ErrorState'

/**
 * "New demo session" — the re-hosted scenario picker (D-2).
 *
 * Scenarios stopped being a destination, but starting a demo session is still a
 * real action, so the picker moves into the sessions home as a modal. Starting
 * one is `useStartDemo`, shared with the Gallery.
 */
export function NewDemoSessionModal({
  isOpen,
  onClose,
}: {
  isOpen: boolean
  onClose: () => void
}) {
  const { data, isLoading, isPending, error: scenariosError, refetch: refetchScenarios } = useScenarios()
  const { start, preparing, startError } = useStartDemo(onClose)

  const groups = useMemo(() => groupDemos(data?.scenarios ?? []), [data?.scenarios])

  // No per-row group chip — the section heading already says it, and
  // repeating it on every row in the group is noise.
  const Row = ({ scenario, group }: { scenario: Scenario; group: string }) => (
    <div
      data-demo-row
      className="flex items-center gap-3 rounded-medium border border-default-200 p-3"
    >
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="truncate font-medium" title={scenario.description}>
            {scenario.name}
          </span>
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
        // Names can repeat across groups (two Producer-Consumers), and 32 bare
        // "Start" buttons are indistinguishable to a screen reader.
        aria-label={`Start ${scenario.name} (${group})`}
      >
        Start
      </Button>
    </div>
  )

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="3xl" scrollBehavior="inside">
      <ModalContent>
        <ModalHeader className="flex flex-col gap-1">
          <span>New demo session</span>
          <span className="text-sm font-normal text-default-500">
            Runs inside vizcore — nothing of yours is involved.
          </span>
        </ModalHeader>
        <ModalBody className="pb-6">
          <DemoStartAlert startError={startError} />
          {isLoading || isPending === true ? (
            <div className="flex justify-center py-12">
              <Spinner size="sm" />
            </div>
          ) : scenariosError && !data ? (
            <ErrorState
              error={scenariosError}
              subject="The scenario list"
              onRetry={() => void refetchScenarios()}
              bare
            />
          ) : (
            <div className="flex flex-col gap-4">
              {groups.map((group) => (
                <section key={group.key} className="flex flex-col gap-2">
                  <h3 className="text-xs font-semibold uppercase tracking-wide text-default-500">
                    {group.label}
                  </h3>
                  <div className="grid gap-2 sm:grid-cols-2">
                    {group.demos.map((s) => (
                      <Row key={s.id} scenario={s} group={group.label} />
                    ))}
                  </div>
                </section>
              ))}
            </div>
          )}
        </ModalBody>
      </ModalContent>
    </Modal>
  )
}
