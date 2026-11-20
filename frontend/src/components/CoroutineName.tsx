import { Fragment } from 'react'
import { splitCoroutineName, wrapPieces } from '@/lib/coroutine-name'

/** The text with a line-break opportunity between its pieces, so a long name wraps between words, not inside one. */
function breakable(text: string) {
  return wrapPieces(text).map((piece, i) => (
    <Fragment key={i}>
      {i > 0 && <wbr />}
      {piece}
    </Fragment>
  ))
}

/**
 * A coroutine's name on a graph card, in every look: "Class.method" as the class on a small muted line and the
 * method on its own line; a name with no class is the one line. The method wraps at camelCase boundaries and
 * separators, over two lines; it breaks inside a word, and is cut, only when that is not enough. The full name is the
 * title of both parts (a look may lay the parts out without their wrapper's box).
 */
export function CoroutineName({ label }: { label: string }) {
  const { owner, name } = splitCoroutineName(label)
  return (
    <>
      {owner !== null && (
        <span
          data-look-part="node-name-owner"
          className="block truncate text-xs font-normal leading-4 text-default-500"
          title={label}
        >
          {owner}
          {/* Kept in the text, so the card's text is still the whole name. */}
          <span className="sr-only">.</span>
        </span>
      )}
      <span data-look-part="node-name-method" className="line-clamp-2 [overflow-wrap:anywhere]" title={label}>
        {breakable(name)}
      </span>
    </>
  )
}
