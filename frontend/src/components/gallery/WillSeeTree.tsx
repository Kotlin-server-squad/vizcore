import { Chip } from '@heroui/react'
import { getStateColors } from '@/lib/coroutine-state-colors'
import type { WillSee, WillSeeCoroutine } from '@/content/gallery/will-see'

/** "waiting for children" for WAITING_FOR_CHILDREN. */
const stateText = (state: string) => state.toLowerCase().replaceAll('_', ' ')

interface Node {
  coroutine: WillSeeCoroutine
  children: Node[]
}

/** Rows in tree order, each at most one level deeper than the one above, become nested nodes. */
function toTree(rows: WillSeeCoroutine[]): Node[] {
  const roots: Node[] = []
  const path: Node[] = []
  for (const coroutine of rows) {
    const node = { coroutine, children: [] }
    path.length = coroutine.depth
    const parent = path[coroutine.depth - 1]
    if (parent) parent.children.push(node)
    else roots.push(node)
    path.push(node)
  }
  return roots
}

/**
 * "What you will see": the session tree at one moment of the demo, drawn with
 * the app's own state icons and colours. A static picture, not a live view.
 * Children are nested lists inside their parent's item, so the hierarchy also
 * reaches screen readers.
 */
export function WillSeeTree({ picture }: { picture: WillSee }) {
  return (
    <figure className="m-0">
      <ul aria-label="What you will see" className="flex flex-col gap-1">
        {toTree(picture.coroutines).map((node) => (
          <TreeItem key={node.coroutine.label} node={node} />
        ))}
      </ul>
      <figcaption className="mt-2 text-sm text-default-500">{picture.moment}</figcaption>
    </figure>
  )
}

function TreeItem({ node }: { node: Node }) {
  const { label, state } = node.coroutine
  const colors = getStateColors(state)
  return (
    <li>
      <div className="flex items-center gap-2 rounded-medium border border-divider bg-content1 px-3 py-1.5">
        <colors.Icon className={`h-4 w-4 shrink-0 ${colors.text}`} />
        <span className="font-mono text-sm">{label}</span>
        <span className="flex-1" />
        <Chip size="sm" variant="flat" color={colors.chipColor}>
          {stateText(state)}
        </Chip>
      </div>
      {node.children.length > 0 && (
        <ul className="mt-1 flex flex-col gap-1 pl-5">
          {node.children.map((child) => (
            <TreeItem key={child.coroutine.label} node={child} />
          ))}
        </ul>
      )}
    </li>
  )
}
