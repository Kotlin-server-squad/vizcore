import type { DemoGuide } from './types'

/**
 * The "How it works" guide for the Combined demo (a mutex and a semaphore
 * together), written from the demo's real code in
 * `backend/.../scenarios/SyncScenarios.kt`: the coroutine names, the order of
 * the steps and the times (in seconds at normal speed). The Kotlin snippet is
 * simplified, but keeps the demo's structure. The only `// <number>` comments
 * are step markers; durations read `// takes 2.5 s`.
 */
export const COMBINED_GUIDES: DemoGuide[] = [
  {
    demoId: 'sync/combined/ecommerce',
    level: 'advanced',
    tags: ['Mutex', 'Semaphore', 'check-then-act'],
    story:
      'A web shop sells the last 3 pairs of a limited sneaker. Six customers order within 9 seconds. Each order must reserve a pair before it pays, and the payment provider takes at most two payments at a time. One card is declined.',
    code: `val stock = Mutex()                               // stock
val paymentGateway = Semaphore(2)                 // payment-gateway
var pairsLeft = 3

suspend fun order(customer: Customer) {           // order-anna … order-finn
    val reserved = stock.withLock {               // 1
        checkStock()                // takes 1 s
        (pairsLeft > 0).also { if (it) pairsLeft-- }
    }
    if (!reserved) return tellSoldOut()   // takes 1 s
    val paid = paymentGateway.withPermit {        // 2
        charge(customer.card)     // takes 2.5 s
    }
    if (!paid) stock.withLock { pairsLeft++ }     // 3
    sendConfirmation()                            // 4, takes 1 s
}`,
    steps: [
      {
        title: 'Check and reserve under one lock',
        text: 'An order checks the stock and takes a pair while it holds stock, so two orders can never both get the last pair. Anna locks it at 0.5 s; Ben (1 s), Carla (2 s) and Dan (3 s) each wait for it, suspended, while the order before them checks. By 3.5 s Anna, Ben and Carla have reserved the three pairs.',
      },
      {
        title: 'Two payments at a time',
        text: 'Paying takes a permit of payment-gateway. Anna pays from 1.5 s and Ben from 2.5 s; Carla is ready to pay at 3.5 s and waits until Anna is done at 4 s. In the tree an order waiting for the lock or a permit looks like one that is paying: both are suspended. Evidence → Locks and permits shows who holds stock and who waits for it, and payment-gateway with its permits in use and its queue; between the semaphore\'s snapshots a holder reads "may hold a payment-gateway permit · holder unknown".',
      },
      {
        title: 'A declined card puts the pair back',
        text: 'Dan checks from 3.5 to 4.5 s and finds none left: Ben\'s pair is reserved, though not yet paid for. Ben\'s card is declined at 5 s, so his order takes the stock lock again and puts the pair back.',
      },
      {
        title: 'Sold out, for real',
        text: 'Eva comes at 6 s and reserves Ben\'s pair; she pays from 7 s. Finn comes at 9 s and finds none left. Anna, Carla and Eva bought the three pairs, and none are left at 11 s.',
      },
    ],
    keyIdea:
      'Use each tool for its job. A Mutex makes checking and taking a pair one step, so no pair is sold twice; a Semaphore limits how many payments run at once. Hold the lock only for the stock, never across the slow payment, and undo a reservation under the same lock when the payment fails.',
    mistakeId: 'SHARED_MUTABLE_STATE',
  },
]
