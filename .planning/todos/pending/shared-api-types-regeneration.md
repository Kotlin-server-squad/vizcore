---
created: 2026-06-12
source: 01-REVIEW WR-08 fix follow-up
severity: info
---
# Regenerate shared/api-types from the corrected OpenAPI spec

WR-08 fixed backend openapi/documentation.yaml (threads map, ms units, state enums), but shared/api-types generates from a local openapi.json copy. Sync the copy and rerun generation in a coordinated pass (will ripple into frontend types).
