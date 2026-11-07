import { describe, it, expect } from 'vitest'
import { missingNote } from './missing-note'

describe('missingNote', () => {
  it('says nothing when every event is there', () => {
    expect(missingNote({ droppedCount: 0, firstSeq: 1, createdBefore: 0 })).toBeNull()
    expect(missingNote({ droppedCount: 0, firstSeq: null, createdBefore: 0 })).toBeNull()
  })

  it('says some intervals may be missing after a loss, a trimmed start or a creation before the events', () => {
    expect(missingNote({ droppedCount: 1, firstSeq: 1, createdBefore: 0 })).toBe(
      'Some intervals may be missing: 1 event was dropped under load.',
    )
    expect(missingNote({ droppedCount: 12, firstSeq: 1_204, createdBefore: 2 })).toBe(
      'Some intervals may be missing: 12 events were dropped under load; the events start at event 1,204; 2 coroutines were created before them.',
    )
    expect(missingNote({ droppedCount: 0, firstSeq: 1, createdBefore: 1 })).toBe(
      'Some intervals may be missing: 1 coroutine was created before the events.',
    )
  })
})
