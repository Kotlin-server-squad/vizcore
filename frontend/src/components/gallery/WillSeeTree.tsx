import { Chip } from '@heroui/react'
import { getStateColors } from '@/lib/coroutine-state-colors'
import type { WillSee } from '@/content/gallery/will-see'

/** "waiting for children" for WAITING_FOR_CHILDREN. */
const stateText = (state: string) => state.toLowerCase().replaceAll('_', ' ')

/**
 * "What you will see": the session tree at one moment of the demo, drawn with
 * the app's own state icons and colours. A static picture, not a live view.
 */
export function WillSeeTree({ picture }: { picture: WillSee }) {
  return (
    <figure className="m-0">
      <ul aria-label="What you will see" className="flex flex-col gap-1">
        {picture.coroutines.map((c) => {
          const colors = getStateColors(c.state)
          return (
            <li
              key={c.label}
              className="flex items-center gap-2 rounded-medium border border-divider bg-content1 px-3 py-1.5"
              style={{ marginLeft: `${c.depth * 1.25}rem` }}
            >
              <colors.Icon className={`h-4 w-4 shrink-0 ${colors.text}`} />
              <span className="font-mono text-sm">{c.label}</span>
              <span className="flex-1" />
              <Chip size="sm" variant="flat" color={colors.chipColor}>
                {stateText(c.state)}
              </Chip>
            </li>
          )
        })}
      </ul>
      <figcaption className="mt-2 text-sm text-default-500">{picture.moment}</figcaption>
    </figure>
  )
}
