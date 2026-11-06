# The look experiment

The session page has four looks: **Classic** (today's), **A · Compact**, **B · Calm** and **C · Bold**. Rather
than one person choosing, the look experiment lets the people who use your vizcore help decide: new visitors
get a look at random, the server counts a few anonymous facts per look, people can rate the look they use, and
a results page applies a fixed decision rule.

It is **off by default**. Nothing on this page happens until the operator sets `LOOK_EXPERIMENT_ENABLED=true`.

## Switching it on and off

| Variable | Default | |
|---|---|---|
| `LOOK_EXPERIMENT_ENABLED` | `false` | `true` runs the experiment. `false`: no look is assigned, nothing is recorded, and every page is as without the experiment (a look a person picked in ⋯ → Look stays theirs, as always) |
| `LOOK_EXPERIMENT` | `classic:25,a:25,b:25,c:25` | New visitors' share of each look. Change it, restart, done: no release needed |
| `LOOK_SURVEY_URL` | _(empty)_ | Optional link to your own outside survey, shown as "Tell us more" with `look=<id>` added |

Both Compose files pass the three variables on. They are read at startup; a restart applies a change.

## What a visitor sees

- A browser that has never picked a look gets one at random, by the weights, and keeps it (it is remembered
  in the browser). The first time, a small note says it can switch in ⋯ → Look; × closes it.
- A browser that already picked a look keeps it. A link with `?look=b` still wins.
- After a few minutes of use in a look, or on a later visit, a small card asks for a rating of that look,
  1 to 5, with an optional comment (at most 500 characters). × closes it. ⋯ → **Rate this look** opens it any
  time; a new rating replaces the earlier one for that look.
- Only the session page takes part. Shared views (`/shared/…`), the other pages, exports and replays do not.

## What is collected

Per look, and nothing else:

- **assigned / chose**: how the browser came to its first look: at random, or because it had (or was linked
  to) one already;
- **seen**: a session page was shown in the look;
- **time in look**: how long the session page was visible in the look (collected in the browser and sent in
  batches, the last one when the page is hidden or closed);
- **switched away**: a change of look in ⋯ → Look, from which look to which;
- **rating** and the optional **comment**.

Each record carries only a **random anonymous id**, a UUID the browser makes for itself and keeps in its
local storage (`vizcore-look-experiment`). It is not derived from anything about the person or the machine.

**Not collected**: IP addresses, the user agent, session ids or names, what a session contains, user names,
which features are used, clicks or anything else. Comments are free text: they are stored as written, and are
shown on the results page.

## Where it is kept

On your vizcore server only. With `STORAGE_TYPE=database` it is kept in four tables (`look_visitors`,
`look_exposure`, `look_switches`, `look_votes`, migration `V3__look_experiment.sql`); otherwise in memory, and
lost on restart. No outside service is called. The only outside link is the optional survey, which opens only
when a person clicks it.

At most 50,000 browsers are counted; after that, new ones are not. Each browser can send at most 30 batches
and 10 ratings a minute.

To delete the data: in database mode, empty the four tables; in memory mode, restart.

## Access

The results and every write follow the same rules as the sessions list: when the server has API keys or users,
they need a credential; when it has none, they are open, as the rest of the API. The writes accept only the
anonymous events and ratings above, and nothing while the experiment is off.

## Results

Open `/experiments/looks` (or `GET /api/experiments/looks/results`). One row per look: visitors assigned and
chose, session pages seen, the median visible time per browser, the share of browsers who switched away and
where they went, the number of ratings and their average, and the comments, newest first. While there is too
little data the page says so.

### The decision rule

The constants live in one place, `LookDecisionRule` (backend `experiment/LookDecisionRule.kt`):

1. The experiment runs until **every look has at least 30 ratings**, or **14 days** have passed since its first
   visitor. Every look counts, whatever its weight.
2. Then the look with the **best average rating** wins,
3. unless people switch away from it **clearly more often**: more than **10 percentage points** above the
   runner-up's switch-away rate. Then the runner-up wins.
4. When the top two averages are **within 0.3**, the result is "too close": keep the top two and run longer.

The page states the result in plain English, for example "Not decided yet: B · Calm has 12 of 30 votes, day 4
of 14."
