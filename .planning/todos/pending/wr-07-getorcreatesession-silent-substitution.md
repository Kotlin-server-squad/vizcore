---
created: 2026-06-12
source: 01-REVIEW.md WR-07
severity: warning
---
# getOrCreateSession silently substitutes a new session for unknown ids

Decide: should GET/POST against a nonexistent sessionId 404 instead of silently creating a fresh session? Silent creation masked the gallery first-load race (UAT round 2). Needs API design decision before changing behavior.
