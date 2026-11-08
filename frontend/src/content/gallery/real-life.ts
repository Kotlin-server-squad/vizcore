import type { DemoGuide } from './types'

/**
 * The "How it works" guides for the Real life demos, written from the demos'
 * real code in `backend/.../scenarios/RealLifeScenarios.kt`: the coroutine
 * names, the order of the steps and the times (in seconds at normal speed).
 * The Kotlin snippets are simplified, but keep the demo's structure. The only
 * `// <number>` comments are step markers; durations read `// takes 1.5 s`.
 *
 * A coroutine in a step (delay) or in await() shows as Suspended in the tree;
 * one waiting in join() or joinAll() shows as Active. Each demo has a failure
 * option that only the API starts (`?fail=true` …), not Run: the guides tell
 * what it does in one step, as it really happens.
 */
export const REAL_LIFE_GUIDES: DemoGuide[] = [
  {
    demoId: 'order-processing',
    level: 'beginner',
    tags: ['launch', 'join', 'structured concurrency'],
    story:
      'Anna orders running shoes online. The shop\'s backend handles her order in steps that depend on each other: check the order, make sure her size is in stock, charge her card, save the order. Only then does it tell her, three ways at once: an email, an SMS and an event for the shop\'s analytics.',
    code: `launch {                               // OrderService.processOrder
    launch { validate() }.join()       // 1
    launch { checkStock() }.join()     // takes 2.5 s
    launch { chargeCard() }.join()     // 2
    launch { saveOrder() }.join()      // takes 1.5 s
    launch { sendConfirmation() }      // 3
    launch { sendSms() }
    launch { trackPurchase() }
}                                      // 4`,
    steps: [
      {
        title: 'One step after the other',
        text: 'processOrder starts OrderValidator.validate and waits for it in join(): 3.5 s for the basket, the prices and the address. Only then does it start InventoryService.checkStock, which asks the warehouse for Anna\'s size (3.5 to 6 s). Each step is a child coroutine. While processOrder waits in join(), the tree shows it Active and the step Suspended.',
      },
      {
        title: 'The payment',
        text: 'PaymentService.processPayment runs from 6 to 10 s: it contacts the payment provider, Anna confirms in her banking app, and the bank answers. The card is accepted, and Database.saveOrder saves the order (10 to 11.5 s). With ?fail=true (an option of the API, not of Run) the bank declines the card at 10 s: processPayment fails, its failure cancels processOrder, which was waiting in join(), and processOrder fails too. Nothing after it starts: no order is saved and no confirmation is sent.',
      },
      {
        title: 'Three notifications at once',
        text: 'At 11.5 s processOrder starts the email, the SMS and the analytics event without join(), so they run side by side. The analytics event is in at 12.5 s and the SMS at 13.5 s; the email takes 3 s.',
      },
      {
        title: 'Waiting for children',
        text: 'processOrder\'s own code is done at 11.5 s, but it completes only with its last child, the email, at 14.5 s. Until then the tree shows it Waiting for children.',
      },
    ],
    keyIdea:
      'Use join() where the next step needs the one before, and leave it out where steps can run side by side. A parent completes only when all its children have. If a child fails, the parent fails with it and starts nothing more.',
  },
  {
    demoId: 'user-registration',
    level: 'intermediate',
    tags: ['joinAll', 'retry', 'coroutineScope'],
    story:
      'Ben signs up for a recipe app. The backend checks his details, makes sure no one has his email yet and creates his account. Then it sets up three things at once, his profile, his settings and an avatar, and waits for all three. Last it welcomes him: an email, a message to the team on Slack and an event for the analytics.',
    code: `launch {                               // UserService.register
    launch { validate() }.join()
    launch { checkEmailIsNew() }.join()
    launch { createUser() }.join()     // 1
    listOf(
        launch { createProfile() },    // takes 2.5 s
        launch { createSettings() },   // takes 1.5 s
        launch { createAvatar() },     // takes 1 s
    ).joinAll()                        // 2
    launch { sendWelcome() }           // 3
    launch { notifyTeam() }
    launch { trackSignup() }
}

suspend fun sendWelcome() {            // EmailService.sendWelcome
    for (attempt in 1..3) {
        try {
            coroutineScope {           // 4
                launch { sendEmail() } // EmailService.attempt-n
            }
            return
        } catch (e: SmtpTimeout) {
            if (attempt == 3) throw e
            delay(1000)
        }
    }
}`,
    steps: [
      {
        title: 'Three checks, one after the other',
        text: 'InputValidator.validateUserData (0 to 2 s), UserRepository.checkExists (2 to 4 s) and UserRepository.createUser (4 to 6.5 s) each need the one before, so register waits for each in join().',
      },
      {
        title: 'Three at once, then wait for all',
        text: 'The profile, the settings and the avatar do not depend on each other, so register starts all three and waits in joinAll(). The avatar is ready at 7.5 s and the settings at 8 s; register waits until the profile is done at 9 s. While it waits in joinAll(), the tree shows register Active.',
      },
      {
        title: 'Welcome',
        text: 'At 9 s register starts the welcome email, the Slack message and the analytics event, and its own code ends: the tree shows it Waiting for children. The analytics event is done at 10 s, Slack at 11 s and the email at 11.5 s, when register completes.',
      },
      {
        title: 'A retry that really retries',
        text: 'sendWelcome sends each try in a child of its own, EmailService.attempt-1, -2 …, inside coroutineScope. coroutineScope hands a failed child\'s exception back to sendWelcome instead of cancelling it, so sendWelcome can catch it and try again. Here the first try works. With ?failEmail=true (an option of the API, not of Run) the mail server does not answer: attempt-1 fails at 12 s, sendWelcome waits 1 s, and attempt-2 sends the email at 15.5 s. The registration completes, and Slack and the analytics event are not touched. A try/catch around launch { … }.join() would not have helped: the failed child would have failed sendWelcome, and through it the whole registration.',
      },
    ],
    keyIdea:
      'joinAll() waits for a group of steps that do not depend on each other. To handle a child\'s failure, catch it where it is thrown, or run the child inside coroutineScope and catch around that: a try/catch around join() cannot stop a failed launch from cancelling its parent. Catch only the error you mean to retry, so that cancellation still passes through.',
    mistakeId: 'UNHANDLED_EXCEPTION',
  },
  {
    demoId: 'report-generation',
    level: 'intermediate',
    tags: ['async', 'await', 'withTimeoutOrNull'],
    story:
      'On the first of the month a shop\'s backend builds last month\'s sales report for the managers. It needs four sources: the active users, the transactions, the analytics and the stock levels. It asks all four at once, adds the numbers up, writes the PDF part by part and delivers it three ways at once. The analytics service is sometimes slow, so it gets at most 4 seconds.',
    code: `launch {                               // ReportService.generateMonthlyReport
    val sources = listOf(
        async { fetchActiveUsers() },  // 1, takes 2.5 s
        async { fetchTransactions() }, // takes 4.5 s
        async { fetchMetrics() },      // 2, takes 3 s
        async { fetchStockLevels() },  // takes 2 s
    )
    val data = sources.map { it.await() }
    launch { aggregate(data) }.join()  // 3
    launch { writePdf() }.join()
    launch { uploadToS3() }            // 4
    launch { emailManagers() }
    launch { postToSlack() }
}

suspend fun fetchMetrics() =           // AnalyticsApi.fetchMetrics
    withTimeoutOrNull(4_000) {
        requestAnalytics()             // AnalyticsApi.request
    } ?: "analytics: not available this month"`,
    steps: [
      {
        title: 'Four sources at once',
        text: 'async starts each fetch in a coroutine of its own and gives back a Deferred: a value that comes later. All four start at 0 s. The report then awaits them in order; while it waits in await(), the tree shows it Suspended. The stock levels are in at 2 s, the users at 2.5 s, the analytics at 3 s and the transactions at 4.5 s.',
      },
      {
        title: 'A time limit for the slow service',
        text: 'fetchMetrics gives the analytics API at most 4 s with withTimeoutOrNull. The demo runs the call in a coroutine of its own, AnalyticsApi.request, so you can see it in the tree. Here it answers in 3 s. With ?timeout=true (an option of the API, not of Run) it would take 5 s: at 4 s the time limit cancels the request, which ends Cancelled, and withTimeoutOrNull returns null. fetchMetrics completes with "analytics: not available this month", and the rest of the report goes on as usual.',
      },
      {
        title: 'Add up, then write',
        text: 'With all four in hand at 4.5 s, the report starts DataProcessor.aggregateData, which runs three calculations at once and waits for them, until 7 s. Then PdfGenerator.createReport writes the header, the charts, the tables and the last touches, one after the other, until 12.5 s. The report waits for each in join(), Active in the tree.',
      },
      {
        title: 'Deliver it three ways',
        text: 'At 12.5 s the report starts the S3 upload, the email to the managers and the Slack post, and its own code ends: Waiting for children. Slack is done at 13.5 s, the email at 14.5 s and the upload at 15 s, when the report completes.',
      },
    ],
    keyIdea:
      'async starts work that returns a value, and await() suspends until it is there. Starting all four fetches before awaiting any lets them run at once: 4.5 s instead of 12. A timeout is a cancellation: withTimeoutOrNull cancels what runs inside it when time is up and returns null, so the caller decides what to do without the answer.',
  },
]
