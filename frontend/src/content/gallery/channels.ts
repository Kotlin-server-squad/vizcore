import type { DemoGuide } from './types'

/**
 * The "How it works" guides for the Channels demos. Each one is written from
 * the demo's real code in `backend/.../scenarios/ChannelScenarios.kt`: the
 * coroutine names, the order of the steps and the times (in seconds at normal
 * speed). The Kotlin snippets are simplified, but keep the demo's structure.
 * The only `// <number>` comments are step markers; durations read `// takes 3 s`.
 */
export const CHANNELS_GUIDES: DemoGuide[] = [
  {
    demoId: 'channel-rendezvous',
    level: 'intermediate',
    tags: ['channel', 'rendezvous', 'send and receive'],
    story:
      'A chef cooks five plates and a waiter carries them out. They meet at the pass, the counter between the kitchen and the restaurant. The pass has no room: a plate changes hands only when the chef and the waiter are both there. Whoever comes first has to wait for the other.',
    code: `suspend fun dinnerService() = coroutineScope {      // dinner-service
    val pass = Channel<String>(Channel.RENDEZVOUS)  // no room on the pass
    launch {                                        // chef
        for (plate in plates) {
            cook(plate)                  // takes 2 s, then 1.5 s
            pass.send(plate)                        // 2
        }
        pass.close()                                // 4
    }
    launch {                                        // waiter
        for (plate in pass) {                       // 1
            serve(plate)                            // 3
        }
    }
}`,
    steps: [
      {
        title: 'The waiter waits for the first plate',
        text: 'The waiter is at the pass at once, but the soup is not ready. receive() suspends the waiter until the chef hands the soup over, at 2 s.',
      },
      {
        title: 'Then the chef waits for the waiter',
        text: 'From the second plate on, the chef is ready 1.5 s before the waiter is back. send() suspends the chef at the pass until the waiter takes the plate. In the tree the chef shows as active while he waits; the Channels panel shows his send waiting.',
      },
      {
        title: 'Both are there: the plate changes hands',
        text: 'The plate goes straight from the chef to the waiter, at 2, 5, 8, 11 and 14 s. The waiter needs 3 s to serve it and come back, while the chef cooks the next one.',
      },
      {
        title: 'The pass is closed',
        text: 'After the dessert the chef calls close(). The waiter serves the dessert, finds the pass closed, and his loop ends at about 17 s. Then all three coroutines are completed.',
      },
    ],
    keyIdea:
      'A rendezvous channel has no room: send() waits for a receiver and receive() waits for a sender, so the two coroutines meet for every value.',
    mistakeId: 'LEAKED_COROUTINE',
  },
  {
    demoId: 'channel-buffered',
    level: 'intermediate',
    tags: ['channel', 'buffer', 'backpressure'],
    story:
      'The office sends eight documents to the printer. The print queue has five slots, so the office does not have to wait for each page. But the office sends a job every 0.6 s, and the printer needs 2.5 s for each one. The queue fills up, and then the office has to wait for a free slot.',
    code: `suspend fun officePrinting() = coroutineScope {    // office-printing
    val queue = Channel<String>(capacity = 5)       // 1
    launch {                                        // office
        for (job in 1..8) {
            prepare()                    // takes 0.6 s
            queue.send("job-$job")                  // 2
        }
        queue.close()                               // 4
    }
    launch {                                        // printer
        warmUp()                         // takes 3 s
        for (job in queue) print(job)               // 3
    }
}`,
    steps: [
      {
        title: 'A queue with five slots',
        text: 'While the queue has room, send() puts the job in a slot and returns at once. Jobs 1 to 4 wait in the queue while the printer warms up for 3 s.',
      },
      {
        title: 'The queue is full: the office waits',
        text: 'At 3.6 s all five slots are taken. send() now suspends the office: job 7 waits from 4.2 s to 5.5 s, job 8 from 6.1 s to 8 s. In the tree the office shows as active while it waits; the Channels panel shows the full queue.',
      },
      {
        title: 'The printer drains the queue',
        text: 'Each time the printer takes a job, a slot is free again. It prints one job every 2.5 s, long after the office has sent the last one.',
      },
      {
        title: 'Closed and empty',
        text: 'After job 8 the office calls close() and is done at 8 s. The printer still prints the jobs in the queue; its loop ends when the queue is closed and empty, at about 23 s.',
      },
    ],
    keyIdea:
      'A buffered channel lets the sender run ahead by as many values as it has room for. When the buffer is full, send() waits, so a fast sender cannot run away from a slow receiver.',
  },
  {
    demoId: 'channel-fan-out',
    level: 'intermediate',
    tags: ['channel', 'fan-out', 'workers'],
    story:
      'A support desk has three agents. New tickets go into one queue, and each agent takes the next ticket when they are free. In the morning rush six tickets come in within 3 s, more than three agents can take at once, so some tickets wait in the queue.',
    code: `suspend fun supportDesk() = coroutineScope {       // support-desk
    val queue = Channel<String>(capacity = 3)       // 3
    launch {                                        // ticket-inbox
        for (ticket in tickets) {
            arrive(ticket)            // takes 0.5 s, later 1.2 s
            queue.send(ticket)                      // 1
        }
        queue.close()                               // 4
    }
    repeat(3) { n ->
        launch {                                    // agent-1 … agent-3
            for (ticket in queue) handle(ticket)    // 2
        }
    }
}`,
    steps: [
      {
        title: 'Tickets arrive',
        text: 'ticket-inbox puts 12 tickets into the queue: six in the morning rush, 0.5 s apart, then one every 1.2 s.',
      },
      {
        title: 'Three agents read one queue',
        text: 'Each agent loops over the same channel. Every ticket goes to exactly one agent: the one who is free. An agent needs 1 to 3 s for a ticket.',
      },
      {
        title: 'The rush: tickets wait in the queue',
        text: 'At 1.5 s all three agents are busy. Tickets 4, 5 and 6 wait in the queue for about 1 s each, until an agent is free again.',
      },
      {
        title: 'The queue is closed',
        text: 'After ticket 12, at about 10 s, the inbox calls close(). Each agent finishes its last ticket, finds the queue closed and empty, and stops; the last one stops at about 13 s.',
      },
    ],
    keyIdea:
      'Fan-out: several coroutines read from one channel, and each value goes to exactly one of them. A fixed number of workers also limits how much work runs at the same time.',
    mistakeId: 'COROUTINE_EXPLOSION',
  },
]
