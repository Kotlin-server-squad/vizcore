/**
 * The session's first event's seq, as the server's snapshot shows it: when the server never trimmed the session, its
 * oldest held event (`oldestRetainedSeq`) is its first. Seqs also number resource and coroutine ids, so a complete
 * demo's events start at seq 3 or so: "after seq 1" is no sign of missing events, "after this seq" is. Undefined when
 * the server trimmed the session or does not say (then 1 is all that is known).
 *
 * The same reading as Part 9's `sessionFirstSeq` for the replay's note (#315), in its own file so both agree.
 */
export function sessionFirstSeq(
  session: { sessionId: string; trimmed?: boolean; oldestRetainedSeq?: number | null } | null | undefined,
): number | undefined {
  return session && session.trimmed === false ? (session.oldestRetainedSeq ?? undefined) : undefined
}
