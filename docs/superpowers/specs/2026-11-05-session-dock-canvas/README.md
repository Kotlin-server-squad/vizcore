# Session dock: saved design boards

A copy of the boards drawn on the design canvas "vizcore session view redesign"
(https://claude.ai/artifact/LoVDqCRuELHtFUaQbqK1xh) on 2026-11-05, for
[the session dock design](../2026-11-05-session-dock-design.md).

The files are Design-canvas components: the markup plus a `renderVals()` block at the bottom that holds the
data each board shows. They render on the canvas, not on their own; they are kept here so the design and its
history stay with the code.

| File | Canvas page | What it shows |
|---|---|---|
| `G0-Map.dc.html` | 1 · The proposal | How the page is built: header, main view (Tree / Timeline), dock tabs, what opens first |
| `G2-Story.dc.html` | 1 · The proposal | A learner: a Gallery demo in story mode, predict then reveal |
| `G1-App.dc.html` | 1 · The proposal | A developer: a real app opens on Problems, coroutines grouped by name |
| `G3-Timeline.dc.html` | 1 · The proposal | The Timeline view, with what a coroutine holds as a band above its state |
| `S1-Finished.dc.html`, `S1-Live.dc.html` | 2 · Quick wins first | Part 1: today's page with the tree first and honest labels |
| `S2-*.dc.html` | 3 · All explorations | The first dock drafts: live, failed, replay |
| `D-Story.dc.html`, `E-Timeline.dc.html`, `F-Problems.dc.html` | 3 · All explorations | The bolder directions before they were combined into the dock |
| `canvas.json` | | The canvas layout, pages and notes (Codex's reviews, the roadmap) |

The four earliest boards (today's page to scale, options A, B and C) were drawn in an earlier session and
are kept on the canvas, page 3.

All numbers on the boards are illustrative. The E-commerce boards assume the paced Combined demo (lock held
1.5 s, payment 3 s, done at 12.0 s); `shop-api` is an invented example app.
