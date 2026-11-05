import type { DemoGuide } from './types'

/**
 * The "How it works" guides for the Patterns demos, written from the demos'
 * real code in `backend/.../scenarios/PatternScenarios.kt`: the coroutine
 * names, the order of the steps and the times (in seconds at normal speed).
 * The Kotlin snippets are simplified, but keep the demo's structure. The only
 * `// <number>` comments are step markers; durations read `// takes 1.5 s`.
 *
 * The guides do not say how the tree shows a coroutine that waits in a channel
 * send or receive; the session's Channels panel shows those waits.
 */
export const PATTERNS_GUIDES: DemoGuide[] = [
  {
    demoId: 'patterns/retry',
    level: 'intermediate',
    tags: ['retry', 'exponential backoff', 'coroutineScope'],
    story:
      'A weather app loads today\'s forecast, but the weather server is overloaded: it turns down the first three tries with HTTP 503. The app does not give up at once, and it does not hammer the server either. It waits before each new try, twice as long each time: 0.75 s, then 1.5 s, then 3 s. The fourth try gets the forecast.',
    code: `suspend fun <T> retry(times: Int, initialDelay: Long, block: suspend () -> T): T {
    var backoff = initialDelay
    repeat(times - 1) {
        try {
            return block()                        // 1
        } catch (e: HttpError) {                  // 2
            delay(backoff)                        // 3
            backoff *= 2
        }
    }
    return block()                                // 4
}

launch {                                          // load-forecast
    val forecast = retry(times = 4, initialDelay = 750) {
        fetchForecast()      // takes 1.5 s: attempt-1 … attempt-4
    }
    show(forecast)           // takes 1 s
}`,
    steps: [
      {
        title: 'A try',
        text: 'load-forecast asks the server for the forecast: 0.5 s to connect, 1 s to wait for the answer. The demo runs each try in a coroutine of its own, attempt-1 to attempt-4, so you can see every try in the tree. In an app you would just call fetchForecast().',
      },
      {
        title: 'The server says no',
        text: 'The first three tries get HTTP 503, so attempt-1, attempt-2 and attempt-3 fail. The demo wraps each try in coroutineScope, which hands the error back to the retry loop. The loop catches it, so load-forecast does not fail.',
      },
      {
        title: 'Wait, twice as long each time',
        text: 'Before the next try the app waits 0.75 s, then 1.5 s, then 3 s. In the tree, load-forecast is suspended while it waits. The growing pause gives the overloaded server time to recover.',
      },
      {
        title: 'The last try gets it',
        text: 'attempt-4 starts at 9.75 s and gets the forecast at 11.25 s. The app shows it for 1 s and is done at about 12.3 s. Had the fourth try failed too, its error would have gone up to load-forecast.',
      },
    ],
    keyIdea:
      'Retry only errors that may go away, such as HTTP 503. Wait longer after each failure, and stop after a fixed number of tries. Catch that error type only: a CancellationException must pass through, so that closing the screen stops the retries.',
  },
  {
    demoId: 'patterns/producer-consumer',
    level: 'intermediate',
    tags: ['producer-consumer', 'channel', 'close'],
    story:
      'At a coffee bar the cashier takes orders and puts each one on the order rail, which has room for three. The barista takes the next order from the rail and makes the drink. Each of them works at their own pace. In the morning rush the orders come faster than the drinks; later, in a quiet spell, the barista is faster than the customers.',
    code: `suspend fun coffeeBar() = coroutineScope {        // coffee-bar
    val orders = Channel<String>(capacity = 3)    // 1
    launch {                                      // cashier
        for (n in 1..10) {
            takeOrder()      // takes 0.6 s in the rush, later 3 s
            orders.send("order-$n")               // 2
        }
        orders.close()                            // 4
    }
    launch {                                      // barista
        for (order in orders) {                   // 3
            makeDrink(order)      // takes 1 to 2 s
        }
    }
}`,
    steps: [
      {
        title: 'The order rail',
        text: 'The rail is a channel with room for three orders. The cashier only sends to it, the barista only receives from it. No list is shared between them: each order passes from one coroutine to the other.',
      },
      {
        title: 'The rush: the rail fills up',
        text: 'Six orders come in within 4 s, faster than the barista makes drinks. While the rail has room, send() puts the order there and the cashier goes on. At 2.5 s the rail is full, so with order 6 the cashier has to wait for a free place, from 3.7 s to 4.7 s.',
      },
      {
        title: 'The quiet spell: the barista waits',
        text: 'Now an order comes every 3 s, and the drinks take 1 s. The barista empties the rail and then waits in receive() for the next order, from 12.7 s and again from 14.7 s. The Channels panel shows who is waiting and how full the rail is.',
      },
      {
        title: 'Closed',
        text: 'After order 10 the cashier calls close(). The barista makes the last drink, finds the rail closed and empty, and the loop ends at about 17.7 s.',
      },
    ],
    keyIdea:
      'A channel between a producer and a consumer lets each run at its own pace. A full channel makes the producer wait, an empty one makes the consumer wait, and close() tells the consumer that nothing more is coming.',
    mistakeId: 'SHARED_MUTABLE_STATE',
  },
  {
    demoId: 'patterns/fan-out-fan-in',
    level: 'intermediate',
    tags: ['fan-out', 'fan-in', 'channel', 'workers'],
    story:
      'A phone makes a holiday album from nine photos. Three resizers share the work: each takes the next photo when it is free (fan-out). One album coroutine collects the finished photos from all three through a single channel (fan-in), in the order they are done.',
    code: `suspend fun makeAlbum() = coroutineScope {        // make-album
    val photos = Channel<String>(Channel.BUFFERED)
    val resized = Channel<String>(Channel.BUFFERED)
    launch { pickPhotos(photos) }   // pick-photos, takes 0.6 s a photo
    val resizers = List(3) {                      // resizer-1 … resizer-3
        launch {
            for (photo in photos) {               // 1
                resized.send(resize(photo))       // 2
            }
        }
    }
    launch {                                      // album
        for (photo in resized) add(photo)         // 3
    }
    resizers.joinAll()
    resized.close()                               // 4
}`,
    steps: [
      {
        title: 'Fan-out: three resizers, one channel',
        text: 'pick-photos sends nine photos into one channel, one every 0.6 s, and closes it after the last. Three resizers read from it. Each photo goes to exactly one resizer: the one that is free.',
      },
      {
        title: 'Each at its own speed',
        text: 'A photo takes 1 to 3 s to resize. A resizer sends each finished photo into a second channel, resized, and takes the next photo.',
      },
      {
        title: 'Fan-in: one album collects them',
        text: 'The album reads resized and adds each photo in 1 s, in the order the photos are finished: photo 2 comes first, then 1, 3, 5 and 4. Finished photos queue in resized while the album is busy.',
      },
      {
        title: 'Close the results',
        text: 'The resizers are done at about 7.8 s. make-album then closes resized, so the album\'s loop ends after the last photo, at about 11.7 s. Without that close() the album would wait for ever.',
      },
    ],
    keyIdea:
      'Fan-out: several coroutines read one channel, and each value goes to exactly one of them. Fan-in: several coroutines send into one channel that a single coroutine reads. Close the results channel once every sender is done.',
    mistakeId: 'LEAKED_COROUTINE',
  },
  {
    demoId: 'patterns/supervisor',
    level: 'intermediate',
    tags: ['supervisorScope', 'failure', 'isolation'],
    story:
      'At night the phone backs up three things side by side: photos, contacts and messages. After 4 s the backup server is out of space for the messages and answers HTTP 507. That should not stop the photos and the contacts, and under a supervisor it does not.',
    code: `launch {                                          // nightly-backup
    supervisorScope {                             // 1
        launch { backUpPhotos() }      // takes 12.5 s
        launch { backUpContacts() }    // takes 8 s
        launch {                                  // backup-messages
            backUpMessages()           // takes 4 s
            throw HttpError(507)                  // 2
        }
    }                                             // 3
    launch { sendReport() }                       // 4
}`,
    steps: [
      {
        title: 'Three backups under a supervisor',
        text: 'nightly-backup starts the three backups inside supervisorScope, and they run side by side. supervisorScope returns only when all three have ended; until then nightly-backup shows as active in the tree.',
      },
      {
        title: 'The message backup fails',
        text: 'At 4 s the server answers HTTP 507: it is out of space. backup-messages throws, and the coroutine fails.',
      },
      {
        title: 'The others keep going',
        text: 'Under supervisorScope a failed child cancels neither its siblings nor its parent. The contacts finish at 8 s and the photos at 12.5 s. Under coroutineScope the failure would have cancelled both, as in the Exception demo.',
      },
      {
        title: 'The report',
        text: 'Then nightly-backup sends a report, "2 of 3 backed up", and completes at about 13.5 s. It completes rather than fails: the failure stayed with backup-messages.',
      },
    ],
    keyIdea:
      'Under supervisorScope, or a SupervisorJob, a child fails on its own: its failure cancels neither its siblings nor its parent. Use it for parts that do not depend on each other, and handle each child\'s failure, for example with a CoroutineExceptionHandler.',
    mistakeId: 'UNHANDLED_EXCEPTION',
  },
  {
    demoId: 'patterns/circuit-breaker',
    level: 'advanced',
    tags: ['circuit breaker', 'resilience', 'supervisorScope'],
    story:
      'A shop charges each order through a payment provider, but the provider is down until 12 s. While it is down, each charge waits 1.5 s for nothing and fails. A circuit breaker notices the failures: it stops calling the provider for a while and fails orders at once, then carefully tries again.',
    code: `class CircuitBreaker(val threshold: Int, val openFor: Long) {
    suspend fun <T> call(block: suspend () -> T): T {
        val opened = openedAt
        if (opened != null && now() - opened < openFor) {
            throw CircuitOpen()                   // 2
        }
        // after openFor, the next call is a trial  // 3
        return try {
            block().also { failures = 0; openedAt = null }  // 4
        } catch (e: HttpError) {
            failures++
            if (opened != null || failures >= threshold) openedAt = now()  // 1
            throw e
        }
    }
}

launch {                                          // checkout
    supervisorScope {
        for (n in 1..8) {
            launch {                              // order-1 … order-8
                breaker.call { charge(n) }        // charge-n
            }.join()
            delay(2000)              // the next customer
        }
    }
}`,
    steps: [
      {
        title: 'Three failures open it',
        text: 'Orders 1, 2 and 3 each call the provider (charge-1 to charge-3). Each charge waits 1.5 s and fails with HTTP 503, and so does its order. After the third failure in a row, at 8.5 s, the breaker opens. The orders run under supervisorScope, so a failed order does not stop the checkout.',
      },
      {
        title: 'Open: orders fail at once',
        text: 'For the next 5 s the breaker does not call the provider at all. Orders 4 (at 10.5 s) and 5 (at 12.5 s) fail at once, with no charge below them in the tree. No customer waits 1.5 s for an answer that will not come, and the provider gets time to recover.',
      },
      {
        title: 'Half-open: one trial',
        text: 'At 14.5 s the 5 s are over. The breaker lets the next order through as a trial: order 6 calls the provider again.',
      },
      {
        title: 'The trial succeeds: closed',
        text: 'The provider is back, so charge-6 succeeds in 1 s and the breaker closes. Orders 7 and 8 are paid as usual, and the checkout is done at about 21.5 s. Had the trial failed, the breaker would have opened again for 5 s.',
      },
    ],
    keyIdea:
      'A circuit breaker stops calling a service that keeps failing. Callers fail fast instead of waiting, and the service gets time to recover. After a while, one trial call checks whether the service is back.',
  },
]
