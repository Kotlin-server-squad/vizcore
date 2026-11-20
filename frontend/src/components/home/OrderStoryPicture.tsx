import { ORDER_STORY, shortLabel, type StoryBar } from '@/content/home/order-story'

const percent = (seconds: number) => `${(seconds / ORDER_STORY.endSeconds) * 100}%`

const seconds = (value: number) => `${value} s`

/**
 * The order story as timeline bars: a still picture drawn from content. It asks
 * the server for nothing and starts no demo, so opening the page costs none of
 * a public server's few demo runs.
 */
export function OrderStoryPicture() {
  const [root] = ORDER_STORY.bars
  return (
    <figure className="m-0 flex min-w-0 flex-col gap-3 rounded-large border border-default-200 bg-content1 p-4 sm:p-5">
      <div className="flex flex-wrap justify-between gap-2 text-xs text-default-500">
        <span className="font-semibold text-foreground">{ORDER_STORY.title}</span>
        <span className="font-mono">0 s to {seconds(ORDER_STORY.endSeconds)}</span>
      </div>
      <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
        {ORDER_STORY.bars.map((bar) => (
          <StoryRow key={bar.label} bar={bar} />
        ))}
      </ul>
      <figcaption className="text-sm text-default-600">
        Four steps one after the other, then three at once. Why is{' '}
        <span className="font-mono text-foreground">{shortLabel(root!.label)}</span> still waiting after{' '}
        {seconds(root!.waitingFrom!)}?
      </figcaption>
    </figure>
  )
}

function StoryRow({ bar }: { bar: StoryBar }) {
  const busyTo = bar.waitingFrom ?? bar.to
  return (
    <li
      data-testid="order-story-bar"
      className="grid grid-cols-[minmax(0,7.5rem)_minmax(0,1fr)] items-center gap-3 sm:grid-cols-[minmax(0,9.5rem)_minmax(0,1fr)]"
    >
      <span className="truncate font-mono text-xs text-default-700" title={bar.label}>
        {shortLabel(bar.label)}
      </span>
      {/* The bars are the picture; the times are said in words for a screen reader. */}
      <span className="sr-only">
        {bar.waitingFrom === undefined
          ? `runs from ${seconds(bar.from)} to ${seconds(bar.to)}`
          : `runs from ${seconds(bar.from)} to ${seconds(bar.to)}, from ${seconds(bar.waitingFrom)} only waiting for its children`}
      </span>
      <div aria-hidden className="relative h-4 sm:h-5">
        <div
          className={`absolute inset-y-0 ${bar.waitingFrom === undefined ? 'rounded bg-warning' : 'rounded-l bg-primary'}`}
          style={{ left: percent(bar.from), width: percent(busyTo - bar.from) }}
        />
        {bar.waitingFrom !== undefined && (
          <div
            className="absolute inset-y-0 rounded-r bg-primary/40"
            style={{ left: percent(bar.waitingFrom), width: percent(bar.to - bar.waitingFrom) }}
          />
        )}
      </div>
    </li>
  )
}
