import type { DemoGuide } from './types'

/**
 * The "How it works" guides for the Sync: Semaphore demos, written from the
 * demos' real code in `backend/.../scenarios/SyncScenarios.kt`: the coroutine
 * names, the order of the steps and the times (in seconds at normal speed). The
 * Kotlin snippets are simplified, but keep the demo's structure. The only
 * `// <number>` comments are step markers; durations read `// takes 2.5 s`.
 *
 * A coroutine waiting for a permit shows as suspended in the tree, as one in
 * delay() does. Evidence → Locks and permits shows each semaphore's permits in
 * use and its wait queue. It never names a holder it cannot know: between the
 * semaphore's snapshots a holder reads "may hold a … permit · holder unknown".
 */
export const SEMAPHORE_GUIDES: DemoGuide[] = [
  {
    demoId: 'sync/semaphore/connection-pool',
    level: 'beginner',
    tags: ['Semaphore', 'withPermit', 'connection pool'],
    story:
      'An online shop\'s backend talks to its database over a pool of 3 connections: more at once would overload the database. At lunchtime a request comes in every half second, nine in all. Each needs a connection for its query, then renders its page without one.',
    code: `val pool = Semaphore(3)                           // db-connections

launch {                                          // shop-backend
    for (request in lunchtimeRequests) {
        delay(500)
        launch {                                  // show-basket … loyalty-points
            pool.withPermit {                     // 1
                runQuery(request)                 // 2, takes 1.5 to 3.5 s
            }                                     // 3
            renderPage()                          // 4, takes 1 s
        }
    }
}`,
    steps: [
      {
        title: 'Three connections, nine requests',
        text: 'Semaphore(3) hands out 3 permits. withPermit takes one, runs the block and gives it back, even if the block fails. show-basket (0.5 s), search-shoes (1 s) and order-history (1.5 s) each find a connection free.',
      },
      {
        title: 'The others wait, suspended',
        text: 'track-parcel comes at 2 s: all three connections are in use, so it waits at withPermit, and so do the five requests after it. In the tree a waiting request shows as suspended, just like one running its query: the tree cannot tell them apart. Evidence → Locks and permits shows db-connections with 0 / 3 available and the requests queued for it. It does not guess who holds a connection: between the semaphore\'s snapshots a holder reads "may hold a db-connections permit · holder unknown".',
      },
      {
        title: 'First come, first served',
        text: 'Each connection given back goes to the request that has waited longest: track-parcel gets one at 3 s, write-review at 4 s, then checkout (5 s), wish-list (5.5 s), store-hours (6 s) and loyalty-points (7.5 s), after waiting 3 s.',
      },
      {
        title: 'Render without the connection',
        text: 'A request gives its connection back before it renders the page (1 s), so the next query does not wait for that. Never more than three queries run at once; the last page is rendered at 11 s.',
      },
    ],
    keyIdea:
      'A Semaphore with n permits lets at most n coroutines into a section at once: here three queries on three connections. withPermit always gives the permit back. A coroutine waiting for a permit is suspended, not blocking a thread, and waiters get permits in the order they asked. Hold the permit only for the work that needs it.',
  },
  {
    demoId: 'sync/semaphore/rate-limiter',
    level: 'beginner',
    tags: ['Semaphore', 'async', 'rate limit'],
    story:
      'A trip planner shows the weather for the eight cities of a trip. The weather service answers at most two calls at a time and turns the rest away, so the app limits itself: every call goes through a semaphore with two permits. The planner asks for all eight forecasts at once and draws the page when they are in.',
    code: `val weatherApi = Semaphore(2)                     // weather-api

launch {                                          // trip-planner
    val forecasts = cities.map { city ->          // 1
        async {                                   // weather-rome … weather-krakow
            weatherApi.withPermit {               // 2
                fetchWeather(city)   // takes 2 to 3 s
            }                                     // 3
        }
    }
    launch {                                      // trip-page
        val all = forecasts.map { it.await() }    // 4
        drawPage(all)               // takes 1.5 s
    }
}`,
    steps: [
      {
        title: 'Eight calls at once',
        text: 'The planner starts an async call for every city, all at 0 s, and trip-page to collect them. Without a limit all eight calls would reach the weather service together.',
      },
      {
        title: 'Two permits, two calls',
        text: 'Rome and Paris take the two permits. The other six wait at withPermit; in the tree they show as suspended, just like the two calls that are fetching. Evidence → Locks and permits shows weather-api with 0 / 2 available and six calls queued. It does not guess who holds a permit: between the semaphore\'s snapshots a holder reads "may hold a weather-api permit · holder unknown".',
      },
      {
        title: 'The next call as soon as one answers',
        text: 'A permit goes to the next waiting call the moment a call answers, not in rounds of two: Vienna at 2.5 s, Prague at 3 s, Lisbon at 4.5 s, Oslo at 6 s, Split at 7 s and Krakow at 8 s.',
      },
      {
        title: 'Await every forecast',
        text: 'trip-page awaits the forecasts in city order, suspended while it waits. Krakow\'s, the last, is in at 10.5 s, and the page with all eight cities is drawn at 12 s.',
      },
    ],
    keyIdea:
      'A Semaphore limits how many calls run at once; async still starts one coroutine per city. For eight cities that is fine. For thousands of items, a fixed number of workers reading a Channel keeps the number of coroutines small as well.',
    mistakeId: 'COROUTINE_EXPLOSION',
  },
  {
    demoId: 'sync/semaphore/file-processor',
    level: 'intermediate',
    tags: ['Semaphore', 'I/O', 'parallelism'],
    story:
      'Before a tax return goes to the accountant, an app compresses the nine files of the tax folder. The disk is fastest with two files at a time; more at once would only make every file slower. One file, the bank statements, is much bigger than the rest.',
    code: `val disk = Semaphore(2)                           // disk

launch {                                          // tax-folder
    for (file in taxFolder) {
        launch {                                  // payslips.pdf … doctor-bills.pdf
            disk.withPermit {                     // 1
                for (part in file.parts) {
                    compress(part)                // 2, takes 1 to 3 s
                }
            }                                     // 3
            upload(file)                          // 4, takes 1 s
        }
    }
}`,
    steps: [
      {
        title: 'Nine files, two permits',
        text: 'All nine files start at 0 s. payslips.pdf and bank-statements.pdf take the two permits of disk; the other seven wait at withPermit, suspended in the tree like the two that are compressing. Evidence → Locks and permits shows disk with 0 / 2 available and seven files queued; between the semaphore\'s snapshots a holder reads "may hold a disk permit · holder unknown".',
      },
      {
        title: 'A big file holds its permit long',
        text: 'bank-statements.pdf is compressed in three parts (3, 3 and 1.5 s) and holds its permit from 0 to 7.5 s. A semaphore does not care how long a permit is held: meanwhile the small files have only the other permit.',
      },
      {
        title: 'The other permit keeps moving',
        text: 'A permit goes to the next file the moment a file is done, not in rounds: receipts.zip gets it at 1.5 s, insurance.pdf at 4 s, donations.pdf at 5 s. Four small files go through while the bank statements are still compressing.',
      },
      {
        title: 'Upload without the disk',
        text: 'Each file gives the disk back before it uploads (1 s), so uploading does not keep the next file waiting. The last file is uploaded at 13 s.',
      },
    ],
    keyIdea:
      'A Semaphore limits how many files use the disk at once, whatever their size. Each permit is handed on the moment a file is done, so one big file slows only its own permit, not the whole folder. Give the permit back as soon as the work that needs the disk is done.',
  },
  {
    demoId: 'sync/semaphore/resource-timeout',
    level: 'intermediate',
    tags: ['Semaphore', 'withTimeoutOrNull', 'finally'],
    story:
      'A clothes shop has two fitting rooms. Shoppers wait for one, but nobody waits for ever: after 3 s a shopper gives up, hangs the clothes back and leaves. Seven shoppers come in, six of them within 3 s.',
    code: `val rooms = Semaphore(2)                          // fitting-rooms

launch {                                          // anna … gina
    var gotRoom = false
    try {
        withTimeoutOrNull(3000) {                 // 1
            rooms.acquire()
            gotRoom = true                        // 2
        }
        if (gotRoom) tryOn()     // takes 3 to 3.5 s
    } finally {
        if (gotRoom) rooms.release()              // 3
    }
    if (gotRoom) payAtTheTill() else leave()      // 4
}`,
    steps: [
      {
        title: 'Wait at most 3 s',
        text: 'withTimeoutOrNull(3000) waits up to 3 s for a room. Anna (0.5 s) and Ben (1 s) find one free. Carla, Dan, Eva and Finn come between 1.5 and 3 s and wait, suspended in the tree, like the shoppers trying clothes on. Evidence → Locks and permits shows fitting-rooms with 0 / 2 available and who is queued; between the semaphore\'s snapshots a holder reads "may hold a fitting-rooms permit · holder unknown".',
      },
      {
        title: 'Remember the room inside the block',
        text: 'gotRoom is set inside the timeout block, right after acquire(). A timeout can fire just after the room is taken, and withTimeoutOrNull then returns null although the shopper has a room. Judging by the result alone, the shopper would leave and the room would never come back.',
      },
      {
        title: 'Give the room back in finally',
        text: 'Whoever got a room gives it back in finally, even if trying on fails or the shopper is cancelled. Carla gets Anna\'s room at 3.5 s, after waiting 2 s; Dan gets Ben\'s at 4.5 s; Gina, who came at 6 s, gets Carla\'s at 7 s.',
      },
      {
        title: 'Giving up is not failing',
        text: 'Eva\'s 3 s are up at 5.5 s and Finn\'s at 6 s, with both rooms still taken. Their waits end without a room: in the tree they go from suspended back to running, not to cancelled, and they leave the queue and the shop. Five shoppers tried clothes on and two left; the shop is done at 11.5 s.',
      },
    ],
    keyIdea:
      'withTimeoutOrNull around acquire() puts a limit on the wait for a permit. Record that you got the permit inside the block and release it in finally: a timeout right after acquire() returns null although the permit is held. A coroutine whose wait times out is resumed, not cancelled, and carries on.',
  },
  {
    demoId: 'sync/semaphore/producer-consumer',
    level: 'advanced',
    tags: ['Semaphore', 'bounded buffer', 'Mutex'],
    story:
      'A baker puts fresh loaves on a cooling rack with room for 3, and a shop assistant takes them off one at a time and sells them. The baker bakes a loaf a second; the assistant needs 2.5 s to sell one. So the rack fills up and the baker has to wait for a free place, and at the start the assistant has to wait for a loaf.',
    code: `val freePlaces = Semaphore(3)                     // free-places
val loavesReady = Semaphore(3, acquiredPermits = 3)  // loaves-ready
val rack = Mutex()                                // rack

launch {                                          // baker
    repeat(8) { loaf ->
        bake(loaf)               // takes 1 to 1.5 s
        freePlaces.acquire()                      // 1
        rack.withLock { onRack.addLast(loaf) }    // 2
        loavesReady.release()                     // 3
    }
}
launch {                                          // shop-assistant
    repeat(8) {
        loavesReady.acquire()
        val loaf = rack.withLock { onRack.removeFirst() }
        freePlaces.release()                      // 3
        sell(loaf)                                // 4, takes 2.5 s
    }
}`,
    steps: [
      {
        title: 'Two counters for one rack',
        text: 'free-places counts the empty places, 3 at the start; loaves-ready counts the loaves on the rack, none at the start. A semaphore cannot start with 0 permits, so loaves-ready starts with all 3 taken. The baker takes a free place before it puts a loaf down, and the assistant takes a ready loaf before it reaches for one.',
      },
      {
        title: 'The rack itself needs a lock',
        text: 'The semaphores only count. Both coroutines change the list of loaves, so a small lock, rack, guards it for a moment each time.',
      },
      {
        title: 'Each gives the other a permit',
        text: 'For every loaf it puts down, the baker gives a permit to loaves-ready; for every loaf it takes, the assistant gives a permit back to free-places. Each permit is taken by one coroutine and given back by the other, so Evidence → Locks and permits cannot say who holds these permits and reads "holder unknown". loaves-ready even starts with 3 permits that nobody holds.',
      },
      {
        title: 'Wait when full, wait when empty',
        text: 'The assistant waits for the first loaf until 1 s. The baker is faster: the rack is full at 5 s, and at 7.5 s and again at 9.5 s the baker waits for a free place, suspended, until the assistant takes a loaf (8.5 s, 11 s). The baker is done at 11 s; the assistant sells the eighth loaf at 21 s. Never more than 3 loaves are on the rack, and they are sold in the order baked.',
      },
    ],
    keyIdea:
      'Two semaphores make a bounded buffer: one counts the free places, the other the ready items. The producer waits when there is no free place, the consumer when nothing is ready, and a small lock guards the buffer itself. In everyday Kotlin a Channel(capacity = 3) does all of this for you, as in the Producer-Consumer pattern.',
  },
]
