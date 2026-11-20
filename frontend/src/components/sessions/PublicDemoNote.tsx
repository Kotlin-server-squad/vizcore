import { RUN_YOUR_OWN_URL } from '@/lib/public-demo'

/**
 * Says, on a public demo server, what a visitor can do here and where to go to
 * connect their own app. Plain text in the theme's own colours, so it reads the
 * same in every look.
 */
export function PublicDemoNote({ className = '' }: { className?: string }) {
  return (
    <p
      role="note"
      className={`rounded-medium border border-default-200 bg-default-50 px-3 py-2 text-sm text-default-600 ${className}`}
    >
      <span className="font-semibold text-foreground">This is a public demo.</span> You can open the
      sessions here and run the built-in demos. To see your own app&apos;s coroutines, run vizcore on
      your own machine:{' '}
      <a
        href={RUN_YOUR_OWN_URL}
        target="_blank"
        rel="noreferrer"
        className="text-primary underline underline-offset-2"
      >
        how to run vizcore and connect your app
      </a>
      .
    </p>
  )
}
