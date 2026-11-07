import { formatDurationMs } from '@/lib/format-duration'
import type { LookResultRow, LookResults } from './results'

const dash = '—'

function switchedTo(row: LookResultRow, labels: Map<string, string>) {
  const where = Object.entries(row.switchedTo)
  if (where.length === 0) return null
  return `to ${where.map(([look, n]) => `${labels.get(look) ?? look} ${n}`).join(', ')}`
}

/** The look experiment's results: the decision in plain English, one row per look, and the comments. */
export function LookResultsView({ results }: { results: LookResults }) {
  const labels = new Map(results.looks.map(r => [r.look, r.label]))
  const { rule } = results
  return (
    <div className="flex flex-col gap-4">
      <div
        role="status"
        className={`rounded-large border p-4 text-base font-medium ${
          results.decision.status === 'WINNER' ? 'border-success bg-success-50' : 'border-default-200 bg-content1'
        }`}
      >
        {results.decision.text}
      </div>
      {results.warning && (
        <p role="alert" className="rounded-medium border border-warning bg-warning-50 p-3 text-sm">
          {results.warning}
        </p>
      )}
      <ul className="list-disc pl-5 text-sm text-default-600">
        {!results.enabled && <li>The experiment is off: nothing new is recorded. These are the results so far.</li>}
        {results.storage === 'memory' && (
          <li>The results are kept in memory and are lost when the server restarts (no database configured).</li>
        )}
        {results.day !== null && (
          <li>
            Day {results.day} of {rule.maxDays}.
          </li>
        )}
      </ul>

      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="text-tiny uppercase text-default-500">
            <tr>
              <th scope="col" className="p-2">Look</th>
              <th scope="col" className="p-2">Assigned</th>
              <th scope="col" className="p-2">Chose</th>
              <th scope="col" className="p-2">Sessions seen</th>
              <th scope="col" className="p-2">Median time</th>
              <th scope="col" className="p-2">Switched away</th>
              <th scope="col" className="p-2">Votes</th>
              <th scope="col" className="p-2">Average</th>
            </tr>
          </thead>
          <tbody>
            {results.looks.map(row => (
              <tr key={row.look} className="border-t border-default-200 align-top">
                <th scope="row" className="p-2 font-medium">
                  {row.label}
                  {!row.inSplit && <div className="text-tiny font-normal text-default-500">not in the split</div>}
                </th>
                <td className="p-2">{row.assigned}</td>
                <td className="p-2">{row.chose}</td>
                <td className="p-2">{row.sessionsSeen}</td>
                <td className="p-2">{row.medianTimeMs === null ? dash : formatDurationMs(row.medianTimeMs)}</td>
                <td className="p-2">
                  {row.switchAwayRate === null ? dash : `${Math.round(row.switchAwayRate * 100)} %`}
                  {switchedTo(row, labels) && (
                    <div className="text-tiny text-default-500">{switchedTo(row, labels)}</div>
                  )}
                </td>
                <td className="p-2">{row.votes}</td>
                <td className="p-2">{row.averageRating === null ? dash : row.averageRating.toFixed(1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <section aria-label="The decision rule" className="text-sm text-default-600">
        <h2 className="mb-1 font-medium text-foreground">The decision rule</h2>
        <p>
          The experiment runs until every look has at least {rule.minVotesPerLook} ratings, or {rule.maxDays} days
          have passed since its first visitor. Then the best average rating wins, unless people switch away from it
          more than {rule.clearlyWorseSwitchAwayPoints} percentage points more often than from the runner-up (then
          the runner-up wins). When the top two averages are within {rule.tooCloseRating}, it is too close: keep the
          top two and run longer.
        </p>
      </section>

      {results.looks.map(row => (
        <section key={row.look} aria-label={`Comments on ${row.label}`}>
          <h2 className="mb-1 font-medium">
            Comments on {row.label} ({row.comments.length})
          </h2>
          {row.comments.length === 0 ? (
            <p className="text-sm text-default-500">No comments yet.</p>
          ) : (
            <ul className="flex flex-col gap-1 text-sm">
              {row.comments.map((c, i) => (
                <li key={`${c.at}-${i}`} className="rounded-medium border border-default-200 p-2">
                  <span className="mr-2 text-tiny text-default-500">
                    {c.rating} / 5 · {new Date(c.at).toLocaleDateString()}
                  </span>
                  {c.comment}
                </li>
              ))}
            </ul>
          )}
        </section>
      ))}
    </div>
  )
}
