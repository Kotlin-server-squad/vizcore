import { useEffect, useState } from 'react'
import { storedLookChoice } from '../look-context'
import type { LookId } from '../registry'
import { loadExperimentConfig, type ExperimentConfig } from './api'
import { decideEntry } from './assign'
import { enqueueEvent } from './queue'
import { readExperimentState, updateExperimentState } from './storage'

/**
 * On the session page, before its LookProvider: asks the server whether the
 * experiment runs and, the first time a browser opens a session page while it
 * does, decides how it joins (a link or an earlier pick: "chose"; else a look
 * at random by the weights: "assigned", kept in the experiment's own storage).
 *
 * `assigned` stays null until the server has said the experiment is on, so a
 * browser with a stored assignment shows Classic, exactly as without the
 * experiment, while it is off.
 */
export function useLookExperimentEntry(
  link: string | undefined,
  /** Each session page asks again (answered from the cache once the server has answered). */
  sessionId: string
): {
  config: ExperimentConfig | null
  assigned: LookId | null
} {
  // The link as each session page opened: the page drops ?look= from its address once read.
  const [opened, setOpened] = useState({ sessionId, link })
  if (opened.sessionId !== sessionId || (link !== undefined && link !== opened.link)) {
    setOpened({ sessionId, link })
  }
  const linkAtOpen = opened.sessionId === sessionId ? opened.link : link
  const [config, setConfig] = useState<ExperimentConfig | null>(null)
  const [assigned, setAssigned] = useState<LookId | null>(null)

  useEffect(() => {
    let alive = true
    void loadExperimentConfig().then(answer => {
      if (!alive) return
      if (answer.enabled) {
        let state = readExperimentState()
        if (!state.entry) {
          const entry = decideEntry({
            link: linkAtOpen,
            choice: storedLookChoice(),
            weights: answer.weights,
            random: Math.random,
          })
          state = updateExperimentState(s => ({
            ...s,
            entry,
            assigned: entry.kind === 'assigned' ? entry.look : s.assigned,
          }))
          enqueueEvent(state.anonId, { type: 'entry', look: entry.look, kind: entry.kind })
        }
        setAssigned(state.assigned ?? null)
      }
      setConfig(answer)
    })
    return () => {
      alive = false
    }
  }, [linkAtOpen, sessionId])

  return { config, assigned }
}
