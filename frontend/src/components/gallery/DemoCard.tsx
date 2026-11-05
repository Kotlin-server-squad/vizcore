import { Button, Card, CardBody, CardHeader, Chip, button } from '@heroui/react'
import { Link } from '@tanstack/react-router'
import { FiBookOpen, FiClock, FiZap } from 'react-icons/fi'
import { demoSlug } from '@/lib/demo-slug'
import type { Scenario } from '@/types/api'
import type { DemoGuide } from '@/content/gallery/types'

const LEVEL_COLOR = { beginner: 'success', intermediate: 'warning', advanced: 'danger' } as const

interface DemoCardProps {
  scenario: Scenario
  groupLabel: string
  /** The demo's "How it works" content, when it has one: adds the level, the tags and a link to its page. */
  guide?: DemoGuide
  isStarting: boolean
  /** Another demo is starting; one start at a time. */
  isDisabled?: boolean
  onRun: () => void
}

/** One demo in the Gallery: what it shows, how long it runs, and Run. */
export function DemoCard({ scenario, groupLabel, guide, isStarting, isDisabled = false, onRun }: DemoCardProps) {
  return (
    <Card data-testid="demo-card" data-demo-id={scenario.id} className="flex flex-col">
      <CardHeader className="flex items-start justify-between gap-2 pb-0">
        <h3 className="text-base font-semibold">{scenario.name}</h3>
        {guide && (
          <Chip size="sm" variant="flat" color={LEVEL_COLOR[guide.level]}>
            {guide.level}
          </Chip>
        )}
      </CardHeader>
      <CardBody className="flex flex-1 flex-col justify-between gap-4">
        <div className="flex flex-col gap-2">
          <p className="text-sm text-default-600">{scenario.description}</p>
          {guide && guide.tags.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {guide.tags.map((tag) => (
                <Chip key={tag} size="sm" variant="dot">
                  {tag}
                </Chip>
              ))}
            </div>
          )}
          {scenario.duration && (
            <div className="flex items-center gap-1 text-xs text-default-500">
              <FiClock className="h-3 w-3" />
              <span>{scenario.duration}</span>
            </div>
          )}
        </div>
        <div className="flex gap-2">
          {guide && (
            // A router Link styled as a button, so it keeps its link role and typed params.
            <Link
              to="/gallery/$slug"
              params={{ slug: demoSlug(scenario.id) }}
              aria-label={`How it works: ${scenario.name}`}
              className={button({ variant: 'bordered', fullWidth: true })}
            >
              <FiBookOpen className="h-4 w-4" aria-hidden />
              How it works
            </Link>
          )}
          <Button
            color="primary"
            variant="flat"
            startContent={isStarting ? undefined : <FiZap className="h-4 w-4" />}
            isLoading={isStarting}
            isDisabled={isDisabled}
            onPress={onRun}
            // Names repeat across groups (two Producer-Consumers).
            aria-label={`Run ${scenario.name} (${groupLabel})`}
            fullWidth
          >
            Run
          </Button>
        </div>
      </CardBody>
    </Card>
  )
}
