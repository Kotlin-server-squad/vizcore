import { Button } from '@heroui/react'
import { FiPlay } from 'react-icons/fi'
import { OrderStoryPicture } from './OrderStoryPicture'

interface HomeHeroProps {
  /** With sessions to show, the hero gives up its picture so the list sits right under it. */
  compact: boolean
  /** The checkout demo is being started. */
  isStarting: boolean
  /** Another demo is starting; one start at a time. */
  isDisabled: boolean
  onWatch: () => void
  onConnect: () => void
}

/**
 * The front door's hero: what vizcore shows, in one sentence, and the two ways
 * in. "Watch a checkout run" starts the order demo with one click.
 */
export function HomeHero({ compact, isStarting, isDisabled, onWatch, onConnect }: HomeHeroProps) {
  return (
    <section
      aria-labelledby="home-hero-title"
      className={compact ? 'flex flex-col gap-4' : 'grid items-center gap-8 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]'}
    >
      <div className={`flex min-w-0 flex-col ${compact ? 'gap-3' : 'gap-5'}`}>
        <h1
          id="home-hero-title"
          className={
            compact
              ? 'text-2xl font-bold leading-tight sm:text-3xl'
              : 'text-3xl font-bold leading-tight tracking-tight sm:text-4xl lg:text-5xl'
          }
        >
          See what your Kotlin coroutines are doing.
        </h1>
        <p className={`max-w-2xl text-default-600 ${compact ? 'text-base' : 'text-lg'}`}>
          Who started whom, who waits for what, what failed and what was cancelled because of it. Live, from a demo or
          from your own app.
        </p>
        <div className="flex flex-wrap gap-3">
          <Button
            color="primary"
            size={compact ? 'md' : 'lg'}
            startContent={isStarting ? undefined : <FiPlay className="h-4 w-4" aria-hidden />}
            isLoading={isStarting}
            isDisabled={isDisabled}
            onPress={onWatch}
          >
            Watch a checkout run
          </Button>
          <Button variant="bordered" size={compact ? 'md' : 'lg'} onPress={onConnect}>
            Connect your app
          </Button>
        </div>
        <p className="text-sm text-default-500">About 15 seconds. Nothing to install. Runs on this server.</p>
      </div>
      {!compact && <OrderStoryPicture />}
    </section>
  )
}
