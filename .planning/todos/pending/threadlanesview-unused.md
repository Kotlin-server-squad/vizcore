---
created: 2026-06-12
source: 01-REVIEW triage round (frontend fixer follow-up note)
severity: info
---
# ThreadLanesView.tsx is unmounted and lacks isLive wiring

frontend/src/components/ThreadLanesView.tsx is not rendered anywhere and calls useThreadLanesByDispatcher(sessionId) with default isLive=false. Delete it or wire it (with isLive prop) when/if it gets mounted.
