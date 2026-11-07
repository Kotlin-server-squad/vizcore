import { useId, useState } from 'react'
import { Button } from '@heroui/react'
import { FiX } from 'react-icons/fi'
import type { LookId } from '../registry'
import { surveyLink } from './api'
import { MAX_COMMENT_LENGTH } from './constants'

interface LookVoteCardProps {
  look: LookId
  label: string
  /** This browser's earlier rating of the look, if any. */
  previous?: number
  surveyUrl: string | null
  /** Saves the rating; resolves true when the server kept it. */
  onSubmit: (rating: number, comment: string) => Promise<boolean>
  onClose: () => void
}

const RATINGS = [1, 2, 3, 4, 5] as const

/**
 * The small card that asks for a rating of the current look (1–5) and an
 * optional comment. It sits in a corner, says what it is for and closes with ×.
 */
export function LookVoteCard({
  look,
  label,
  previous,
  surveyUrl,
  onSubmit,
  onClose,
}: LookVoteCardProps) {
  const [rating, setRating] = useState<number | null>(previous ?? null)
  const [comment, setComment] = useState('')
  const [state, setState] = useState<'asking' | 'sending' | 'saved' | 'failed'>('asking')
  const titleId = useId()

  async function send() {
    if (rating === null) return
    setState('sending')
    setState((await onSubmit(rating, comment.trim())) ? 'saved' : 'failed')
  }

  return (
    <section
      aria-labelledby={titleId}
      role="region"
      data-testid="look-vote-card"
      className="pointer-events-auto rounded-large border border-default-200 bg-content1 p-3 text-sm text-foreground shadow-medium"
    >
      <div className="flex items-start gap-2">
        <h2 id={titleId} className="flex-1 font-medium">
          <span className="sr-only">Rate this look: </span>
          How do you like this look, <span className="whitespace-nowrap">{label}</span>?
        </h2>
        <Button
          size="sm"
          variant="light"
          isIconOnly
          aria-label="Close the rating"
          onPress={onClose}
          className="-mr-1 -mt-1 h-6 min-w-6"
        >
          <FiX aria-hidden />
        </Button>
      </div>
      {state === 'saved' ? (
        <p className="mt-2 text-default-600">
          Thanks, your rating is saved. You can change it any time in ⋯ → Rate this look.
        </p>
      ) : (
        <>
          <div
            role="radiogroup"
            aria-label="Your rating, 1 (not for me) to 5 (love it)"
            className="mt-2 flex gap-1"
          >
            {RATINGS.map(value => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={rating === value}
                aria-label={`${value} of 5`}
                onClick={() => setRating(value)}
                className={`h-8 flex-1 rounded-medium border text-sm transition-colors ${
                  rating === value
                    ? 'border-primary bg-primary text-primary-foreground'
                    : 'border-default-300 hover:bg-default-100'
                }`}
              >
                {value}
              </button>
            ))}
          </div>
          <div className="mt-1 flex justify-between text-tiny text-default-500">
            <span>not for me</span>
            <span>love it</span>
          </div>
          <label className="mt-2 block text-tiny text-default-500">
            A comment, if you like
            <textarea
              value={comment}
              maxLength={MAX_COMMENT_LENGTH}
              rows={2}
              onChange={e => setComment(e.target.value)}
              className="mt-1 block w-full resize-none rounded-medium border border-default-300 bg-transparent p-1.5 text-sm text-foreground"
            />
          </label>
          <div className="mt-0.5 text-right text-tiny text-default-400">
            {comment.length} / {MAX_COMMENT_LENGTH}
          </div>
          {state === 'failed' && (
            <p role="alert" className="mt-1 text-tiny text-danger">
              Your rating could not be saved. Try again.
            </p>
          )}
          <div className="mt-2 flex items-center justify-between gap-2">
            <span className="text-tiny text-default-500">Anonymous; stays on this server.</span>
            <Button
              size="sm"
              color="primary"
              isDisabled={rating === null}
              isLoading={state === 'sending'}
              onPress={() => void send()}
            >
              Send
            </Button>
          </div>
        </>
      )}
      {surveyUrl && (
        <a
          href={surveyLink(surveyUrl, look)}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-2 inline-block text-tiny text-primary underline"
        >
          Tell us more
        </a>
      )}
    </section>
  )
}
