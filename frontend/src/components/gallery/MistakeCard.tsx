import { Button, Card, CardBody, CardHeader, Chip } from '@heroui/react'
import { FiAlertTriangle } from 'react-icons/fi'
import type { Mistake } from '@/content/gallery/types'

/**
 * One common mistake. Amber marks it as a potential problem, never red, and
 * only in the icon and border: amber text is too faint on a light background.
 * The id is the anchor How-it-works pages link to.
 */
export function MistakeCard({ mistake }: { mistake: Mistake }) {
  return (
    <Card id={mistake.id} data-testid="mistake-card" className="flex scroll-mt-20 flex-col border border-warning-300">
      <CardHeader className="flex flex-col items-start gap-1 pb-0">
        <div className="flex items-center gap-2">
          <FiAlertTriangle className="h-4 w-4 shrink-0 text-warning" aria-hidden />
          <h3 className="text-base font-semibold">{mistake.title}</h3>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <code className="font-mono text-xs text-default-500">{mistake.id}</code>
          {mistake.detected && (
            <Chip size="sm" variant="flat">
              Detected by the visualizer
            </Chip>
          )}
        </div>
      </CardHeader>
      <CardBody className="flex flex-1 flex-col justify-between gap-4">
        <div className="flex flex-col gap-2 text-sm">
          <p>
            <span className="font-semibold">What goes wrong: </span>
            <span className="text-default-600">{mistake.whatGoesWrong}</span>
          </p>
          <p>
            <span className="font-semibold">Do instead: </span>
            <span className="text-default-600">{mistake.fix}</span>
          </p>
        </div>
        <Button variant="bordered" isDisabled fullWidth>
          Demo coming
        </Button>
      </CardBody>
    </Card>
  )
}
