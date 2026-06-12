---
created: 2026-06-12
source: 01-REVIEW.md WR-10
severity: warning
---
# vizLaunch emits lifecycle events from inside the coroutine body

Cancel-before-start produces terminal events with no Created/Started pair (validator-visible). Fix requires moving Created/Started emission outside the body (CoroutineStart.UNDISPATCHED or wrapper redesign). Coordinate with IN-05/vizAsync parity work.
