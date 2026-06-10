---
title: Retire standalone vizcor-be & vizcor-fe, redirect to monorepo
date: 2026-06-10
priority: medium
---

# Retire standalone repos → redirect to vizcore monorepo

Consolidation is verified complete (see note: `monorepo-consolidation-verified`).
The standalone repos are older subsets of the `vizcore` monorepo and should be retired.

## Constraint

No automatic GitHub redirect is possible: standalones live under `hermanngeorge15`,
the monorepo lives under the `Kotlin-server-squad` org. Auto-redirects only occur on
rename/transfer within the same owner. So "redirect" = archive + README banner.

## Actions

### `hermanngeorge15/vizcor-be`
- [ ] Add a banner to the top of `README.md`:
      `> ⚠️ This repository has moved. Development continues in the monorepo:`
      `> https://github.com/Kotlin-server-squad/vizcore (see backend/).`
- [ ] Commit the banner.
- [ ] Optionally pin an issue "Repo moved to vizcore" for visibility.
- [ ] Archive the repository (Settings → Archive this repository) so it becomes read-only.

### `hermanngeorge15/vizcor-fe`
- [ ] Add the same "moved" banner to `README.md` pointing to `vizcore/frontend/`.
- [ ] Commit the banner.
- [ ] Optionally pin a "Repo moved" issue.
- [ ] Archive the repository.

### Optional — true redirect via transfer
If a real auto-redirect is wanted, transfer the standalone repo into the
`Kotlin-server-squad` org first; GitHub then 301-redirects the old path. Only do this
if the standalone history needs to remain reachable under the old URL — otherwise
archive + banner is simpler and sufficient.

## Verification
- [ ] Visiting either old repo clearly directs readers to `Kotlin-server-squad/vizcore`.
- [ ] Both standalone repos are archived (read-only badge visible).
