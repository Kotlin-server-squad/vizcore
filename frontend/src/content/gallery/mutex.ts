import type { DemoGuide } from './types'

/**
 * The "How it works" guides for the Sync: Mutex demos, written from the demos'
 * real code in `backend/.../scenarios/SyncScenarios.kt`: the coroutine names,
 * the order of the steps and the times (in seconds at normal speed). The Kotlin
 * snippets are simplified, but keep the demo's structure. The only
 * `// <number>` comments are step markers; durations read `// takes 1.5 s`.
 *
 * A coroutine waiting for a lock shows as suspended in the tree, as one in
 * delay() does; the session's Lock contention panel shows who holds each lock
 * and who is queued for it.
 */
export const MUTEX_GUIDES: DemoGuide[] = [
  {
    demoId: 'sync/mutex/counter',
    level: 'beginner',
    tags: ['Mutex', 'withLock', 'shared state'],
    story:
      'Three gates let visitors into a festival and keep one shared count of everyone inside. To count a visitor, a gate reads the count, shows the new number on the board, which takes 1.5 s, and writes it back. If two gates read the count at the same moment, both would write the same number, and one visitor would be lost. A lock lets only one gate at a time do the update.',
    code: `suspend fun countVisitors() = coroutineScope {      // count-visitors
    val lock = Mutex()                              // visitor-count
    var visitors = 0
    repeat(3) {                                     // gate-1 … gate-3
        launch {
            repeat(3) {
                scanTicket()         // takes 0.5 to 2.5 s
                lock.withLock {                     // 1
                    val seen = visitors             // 2
                    showOnBoard(seen + 1)   // 3, takes 1.5 s
                    visitors = seen + 1
                }
            }
        }
    }
}                                                   // 4`,
    steps: [
      {
        title: 'One gate at a time',
        text: 'withLock lets one coroutine in and makes the others wait until it leaves. gate-1 scans its first ticket and takes the lock at 0.5 s. gate-2 asks for it at 1 s and waits until gate-1 is done, at 2 s.',
      },
      {
        title: 'Read and write under the same lock',
        text: 'Between reading the count and writing it back, the gate spends 1.5 s on the board. Without the lock another gate could read the same count meanwhile, and both would write it plus one: a lost update. With the whole read-update-write inside withLock, no other gate can come in between.',
      },
      {
        title: 'The others wait, suspended',
        text: 'While one gate holds the lock for those 1.5 s, a gate that asks for it waits. In the tree a waiting gate shows as suspended, just like a gate scanning a ticket. It does not block a thread; it is resumed when the lock is its turn. The Lock contention panel shows which gate holds visitor-count and which gates are queued for it.',
      },
      {
        title: 'Nine visitors, nine counted',
        text: 'Only gate-1\'s first visitor finds the lock free; the other eight updates wait for it, at times two gates at once. The gates get the lock in the order they asked: 1, 2, 3, 1, 2, 3, 1, 2, 3. gate-1 is done at 11 s; gate-2 holds the lock until 12.5 s while gate-3 waits for it. The count is 9 when gate-3 is done, at 14 s.',
      },
    ],
    keyIdea:
      'A Mutex lets one coroutine at a time into a critical section. Put the whole read-modify-write inside withLock. A coroutine waiting for the lock is suspended, not blocking a thread. Keep the locked part short: here each update holds the lock for 1.5 s, so the gates spend much of their time waiting.',
    mistakeId: 'SHARED_MUTABLE_STATE',
  },
  {
    demoId: 'sync/mutex/bank-transfer',
    level: 'intermediate',
    tags: ['Mutex', 'lock ordering', 'check-then-act'],
    story:
      'Anna and Ben share the household bills and send each other money: Anna has 1000, Ben 500. Each transfer locks both accounts while it moves the money, so nobody sees money that has left one account but not yet reached the other. Every transfer takes the locks in the same order, Anna\'s account first, whoever pays.',
    code: `suspend fun transfer(from: Account, to: Account, amount: Int) {
    val (first, second) = listOf(from, to).sortedBy { it.owner }  // 1
    first.lock.withLock {
        second.lock.withLock {                    // 2
            checkBalance()            // takes 1 s
            if (from.balance >= amount) {         // 3
                from.balance -= amount   // takes 1 s
                to.balance += amount     // takes 1 s
            }
        }
    }
    sendReceipt()                                 // 4
}

launch {                                          // transfers
    launch { transfer(anna, ben, 400) }           // pay-rent
    launch { transfer(ben, anna, 50) }            // pay-back-dinner
    launch { transfer(anna, ben, 120) }           // split-groceries
    launch { transfer(ben, anna, 1200) }          // book-holiday
}`,
    steps: [
      {
        title: 'One order for every transfer',
        text: 'A transfer sorts the two accounts by owner and locks Anna\'s first, then Ben\'s. pay-back-dinner, where Ben pays Anna, also starts with Anna\'s account. Had it started with the payer\'s account, two transfers could each hold one account and wait for the other\'s for ever, as in the Deadlock demo.',
      },
      {
        title: 'The transfers queue at Anna\'s account',
        text: 'The transfers start at 1, 2, 3 and 5 s. pay-rent holds both accounts from 1 s to 4 s. pay-back-dinner asks for Anna\'s account at 2 s and waits until 4 s, split-groceries from 3 s and book-holiday from 5 s. While they wait they show as suspended in the tree, and the Lock contention panel lists them in the queue for account-anna. Ben\'s account is always free by the time a transfer asks for it: whoever holds Anna\'s has already let go of Ben\'s.',
      },
      {
        title: 'Check, then change, under the lock',
        text: 'The balance check and the change happen while the transfer holds both locks, so nothing can change the balance in between. book-holiday gets the accounts at 10 s: Ben has 970, not 1200, so it is declined at 11 s and no money moves.',
      },
      {
        title: 'The receipt comes after the lock',
        text: 'Each transfer lets go of both accounts first and then sends its receipt (1 s), so the next transfer does not wait for it. At 12 s Anna has 530 and Ben 970: together still 1500.',
      },
    ],
    keyIdea:
      'Lock every account a transfer touches, and always in the same order. Then two transfers can never each hold one lock and wait for the other\'s. Check and change the balance under the same lock, and do slow work that does not need the accounts, such as the receipt, after letting go.',
  },
  {
    demoId: 'sync/mutex/cache',
    level: 'intermediate',
    tags: ['Mutex', 'cache', 'double-checked'],
    story:
      'A shop server answers requests for the price of an espresso machine and a coffee grinder. Prices come from the database, which takes 3 s, so the server keeps them in a cache. The cache starts empty, and three requests for the espresso machine arrive before the first load is done. Only one of them should ask the database.',
    code: `val prices = ConcurrentHashMap<String, String>()
val lock = Mutex()                                // price-cache

suspend fun price(product: String): String =
    prices[product]                               // 1
        ?: lock.withLock {                        // 2
            prices[product]                       // 3
                ?: loadFromDatabase(product)      // 4
                    .also { prices[product] = it }
        }

launch {                                          // shop-server
    for (product in requests) {
        delay(gap)             // takes 0.5 to 2.5 s
        launch {                                  // request-1 … request-8
            render(price(product))    // takes 1 s
        }
    }
}`,
    steps: [
      {
        title: 'Cached? Read without the lock',
        text: 'A request first looks in the cache, without the lock. request-4 (at 5 s), request-6 (7 s) and request-8 (10.5 s) find their price there and never touch the lock. request-6 gets the espresso price while request-5 holds the lock for the grinder. The cache is a ConcurrentHashMap, so reading it while another coroutine writes it is safe.',
      },
      {
        title: 'A miss takes the lock',
        text: 'request-1 misses at 0.5 s and takes the lock. request-2 (1.5 s) and request-3 (2.5 s) miss too, because the price is not loaded yet, and wait for the lock. While they wait they show as suspended in the tree; the Lock contention panel shows them queued for price-cache.',
      },
      {
        title: 'Check again under the lock',
        text: 'When request-2 gets the lock at 3.5 s, the espresso price is in the cache: request-1 put it there. So request-2 does not load it again, and neither does request-3. Without this second check each waiting request would ask the database once more.',
      },
      {
        title: 'One load per product',
        text: 'Only request-1 loads the espresso machine (load-espresso, 0.5 to 3.5 s) and only request-5 loads the grinder (load-grinder, 6 to 9 s). request-7 waits for the lock from 8 s and then finds the grinder cached. Eight requests, two loads; the server is done at 11.5 s.',
      },
    ],
    keyIdea:
      'Read-through with a lock: read the cache without the lock; on a miss take the lock, check again, and load only if the value is still missing. Then each value is loaded once, however many requests miss at the same time. Here one lock covers every product, so a miss on one product also waits for a load of another; a lock per product avoids that.',
  },
  {
    demoId: 'sync/mutex/deadlock-demo',
    level: 'advanced',
    tags: ['Mutex', 'deadlock', 'cancellation'],
    story:
      'At month end Anna pays Ben and Ben pays Anna at the same moment. Unlike the Bank Transfer demo, each transfer locks its payer\'s account first. Anna\'s transfer holds her account and waits for Ben\'s; Ben\'s holds his and waits for Anna\'s. Neither can go on, and neither lets go: a deadlock. A watchdog notices that nothing finishes and cancels both.',
    code: `suspend fun payerFirst(from: Account, to: Account) {
    from.lock.withLock {                          // 1
        checkBalance()           // takes 1 to 1.5 s
        to.lock.withLock {                        // 2
            move(from, to)           // never reached
        }
    }
}

launch {                                          // month-end
    val transfers = listOf(
        launch { payerFirst(anna, ben) },         // anna-pays-ben
        launch { payerFirst(ben, anna) },         // ben-pays-anna
    )
    launch {                                      // watchdog
        repeat(5) {
            delay(2000)
            if (transfers.any { it.isCompleted }) return@launch  // 3
        }
        transfers.forEach { it.cancel() }         // 4
        writeReport()                // takes 1 s
    }
}`,
    steps: [
      {
        title: 'Each locks the payer\'s account',
        text: 'Both transfers start at once: anna-pays-ben locks Anna\'s account and ben-pays-anna locks Ben\'s. Then each checks its payer\'s balance, Ben\'s in 1 s and Anna\'s in 1.5 s.',
      },
      {
        title: 'Each waits for the other\'s',
        text: 'At 1 s ben-pays-anna asks for Anna\'s account, which anna-pays-ben holds; at 1.5 s anna-pays-ben asks for Ben\'s, which ben-pays-anna holds. Both are now suspended in the tree, waiting for a lock that will never be released. The Lock contention panel shows each account held by one transfer, with the other transfer queued for it.',
      },
      {
        title: 'Nothing moves',
        text: 'A deadlock throws no error; it just never ends. The watchdog checks every 2 s whether either transfer has finished. At 2, 4, 6 and 8 s neither has, and the tree stays the same: two transfers suspended.',
      },
      {
        title: 'Cancelled, never resumed',
        text: 'At 10 s the watchdog cancels both. Their waits end without a resume: in the tree both go straight from suspended to cancelled. Leaving withLock releases the account each one held. The watchdog writes its report, and month-end completes at 11 s. The money did not move; the transfers have to be made again.',
      },
    ],
    keyIdea:
      'Two coroutines that take the same two locks in opposite order can deadlock. Take locks in one fixed order everywhere, as the Bank Transfer demo does, or hold only one lock at a time. A timeout or a watchdog can end a deadlock, but only by cancelling the work: it is a safety net, not the fix.',
    mistakeId: 'LEAKED_COROUTINE',
  },
]
