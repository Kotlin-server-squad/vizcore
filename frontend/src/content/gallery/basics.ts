import type { DemoGuide } from './types'

/**
 * The "How it works" guides for the Basics demos. Each one is written from the
 * demo's real code in `backend/.../scenarios/BasicScenarios.kt`: the coroutine
 * names, the order of the steps and the times (in seconds at normal speed). The
 * Kotlin snippets are simplified, but keep the demo's structure. The only
 * `// <number>` comments are step markers; durations read `// takes 8 s`.
 */
export const BASICS_GUIDES: DemoGuide[] = [
  {
    demoId: 'nested',
    level: 'beginner',
    tags: ['structured concurrency', 'launch', 'join'],
    story:
      'A product page needs the product first. Then it loads three sections at once: the price box, the reviews and the recommendations. Each section starts requests of its own, so the work is three levels deep. The page is done only when the very last request below it is done.',
    code: `suspend fun loadProductPage() = coroutineScope {   // load-product-page
    launch { fetchProduct() }.join()                // 1
    launch { loadPriceBox() }                       // 2
    launch { loadReviews() }                        // 2
    launch { loadRecommendations() }                // 2
}                                                   // 4

suspend fun loadReviews() = coroutineScope {        // load-reviews
    launch {                                        // fetch-reviews
        fetchReviews()
        launch { fetchReviewPhotos() }              // 3
    }
}`,
    steps: [
      {
        title: 'The product comes first',
        text: 'load-product-page starts fetch-product and waits for it with join(), because the sections need the product. This takes 2.5 s.',
      },
      {
        title: 'Three sections start together',
        text: 'The price box, the reviews and the recommendations start at the same time. Each one starts its own requests, such as fetch-price and fetch-delivery-date.',
      },
      {
        title: 'Children have children',
        text: 'fetch-reviews starts fetch-review-photos, and fetch-similar-products starts fetch-thumbnails. Now the tree is three levels deep, and every parent waits for its own children.',
      },
      {
        title: 'The page waits for everything',
        text: "The page's own code is finished after 2.5 s, but it shows \"waiting for children\" until the last request, fetch-thumbnails, is done at about 16 s. Then all 11 coroutines are completed.",
      },
    ],
    keyIdea:
      'A parent coroutine is not finished until all of its children are finished, at every level. This is structured concurrency.',
    mistakeId: 'GLOBAL_SCOPE_USAGE',
  },
  {
    demoId: 'parallel',
    level: 'beginner',
    tags: ['parallel', 'launch', 'joinAll'],
    story:
      'A dashboard shows five widgets: sales, orders, visitors, alerts and top products. Each widget needs its own request, and none of them needs another. So all five load at the same time, and the dashboard is drawn when the slowest one is in.',
    code: `suspend fun loadDashboard() = coroutineScope {      // load-dashboard
    listOf(                                         // 1
        launch { fetchSales() },        // takes 8 s
        launch { fetchOrders() },       // takes 4.5 s
        launch { fetchVisitors() },     // takes 11 s
        launch { fetchAlerts() },       // takes 1.5 s
        launch { fetchTopProducts() },  // takes 6 s
    ).joinAll()                                     // 2
    launch { renderDashboard() }                    // 3
}`,
    steps: [
      {
        title: 'Five widgets start at once',
        text: 'Each request runs in its own coroutine. They all start at the same moment and finish one by one: alerts after 1.5 s, visitors last, after 11 s.',
      },
      {
        title: 'Wait for the slowest',
        text: 'joinAll() waits until all five are done. Together the requests take 31 s of work, but because they overlap, the wait is only 11 s.',
      },
      {
        title: 'Draw the dashboard',
        text: 'render-dashboard runs once all the data is in. It takes 2.5 s, so the whole demo takes about 14 s.',
      },
    ],
    keyIdea:
      'Work that does not depend on other work can run at the same time. Then the total time is the time of the slowest part, not the sum of all parts.',
    mistakeId: 'UNNECESSARY_ASYNC_AWAIT',
  },
  {
    demoId: 'cancellation',
    level: 'intermediate',
    tags: ['cancellation', 'scope', 'cancel'],
    story:
      'A profile screen loads the avatar, the friends list and the activity feed. Settings sync runs in the background of the app, not as part of the screen. After 6.5 s the user closes the screen. Only the screen and its own requests must stop.',
    code: `launch {                                            // app-session
    val screen = launch {                           // 1 profile-screen
        launch { fetchAvatar() }
        launch { fetchFriends() }
        launch { fetchActivityFeed() }
    }
    val sync = launch { syncSettings() }            // 2
    delay(6_500)                                    // 3
    screen.cancel()                                 // 4
    sync.join()                                     // 5
}`,
    steps: [
      {
        title: 'The screen starts its requests',
        text: 'profile-screen starts fetch-avatar, fetch-friends and fetch-activity-feed as its children.',
      },
      {
        title: 'Settings sync is not part of the screen',
        text: 'sync-settings is a sibling of the screen, not its child. It belongs to the app session.',
      },
      {
        title: 'The user looks at the screen',
        text: 'For 6.5 s the requests run. The avatar is ready after 2 s and completes.',
      },
      {
        title: 'The user closes the screen',
        text: 'screen.cancel() cancels profile-screen and every request still running under it: the friends and the activity feed. The avatar was already done, so it stays completed.',
      },
      {
        title: 'Settings sync carries on',
        text: 'Cancelling the screen did not touch sync-settings. It finishes at about 12 s, and then the app session completes.',
      },
    ],
    keyIdea:
      'Cancelling a coroutine cancels all of its children, and nothing else. Work that must go on belongs outside the scope you cancel.',
    mistakeId: 'MISSING_CANCELLATION_CHECK',
  },
  {
    demoId: 'deep-nesting',
    level: 'intermediate',
    tags: ['nesting', 'recursion', 'join'],
    story:
      'A search indexer walks the folders /home/ana/projects/vizcore/backend/src. Each folder lists its contents, starts indexing its subfolder, indexes its own files in the meantime, and then adds the subfolder’s results to its own. Six folders, six levels of coroutines.',
    code: `fun CoroutineScope.indexFolder(folder: Folder): Job = launch {   // index-<folder>
    listContents(folder)                            // 1
    val sub = folder.subfolder?.let { indexFolder(it) }   // 2
    indexOwnFiles(folder)                           // 3
    sub?.join()                                     // 4
    addResults(sub)                                 // 5
}`,
    steps: [
      {
        title: 'List the folder',
        text: 'Each folder first lists what it contains. This takes 1 s.',
      },
      {
        title: 'Start the subfolder, one level deeper',
        text: 'The folder starts a new coroutine for its subfolder: index-home starts index-ana, which starts index-projects, and so on down to index-src.',
      },
      {
        title: 'Index its own files in the meantime',
        text: 'While the subfolder is walked, the folder indexes its own files (1.5 s). The deepest folder, src, holds most of the files and needs 6 s.',
      },
      {
        title: 'Wait for the subfolder',
        text: 'join() waits until the subfolder is completely indexed, including everything below it.',
      },
      {
        title: 'Add the results, from the deepest up',
        text: 'The folder adds its subfolder’s results (0.8 s) and completes. So the folders complete one by one from the bottom: src first at about 12 s, home last at about 16 s.',
      },
    ],
    keyIdea:
      'Structured concurrency works at any depth. A coroutine finishes only after everything below it has finished, so results flow up the tree.',
    mistakeId: 'COROUTINE_EXPLOSION',
  },
  {
    demoId: 'mixed',
    level: 'intermediate',
    tags: ['sequential', 'parallel', 'join', 'joinAll'],
    story:
      'A checkout has steps that depend on each other and steps that do not. The cart must be valid before anything else. Then the stock, the card payment and the shipping cost do not depend on each other. The order can be created only after all three, and the receipt only after the order.',
    code: `launch {                                            // checkout
    launch { validateCart() }.join()                // 1
    listOf(                                         // 2
        launch { reserveStock() },
        launch { chargeCard() },
        launch { calculateShipping() },
    ).joinAll()                                     // 3
    launch { createOrder() }.join()                 // 4
    launch { sendReceipt() }.join()                 // 4
}`,
    steps: [
      {
        title: 'Validate the cart first',
        text: 'validate-cart runs alone, and join() waits for it (2.5 s). Nothing else makes sense with an invalid cart.',
      },
      {
        title: 'Three independent steps start together',
        text: 'reserve-stock, charge-card and calculate-shipping do not need each other, so they run at the same time.',
      },
      {
        title: 'Wait for all three',
        text: 'joinAll() waits for the slowest of the three, charging the card, which takes 6 s.',
      },
      {
        title: 'Then one after another',
        text: 'create-order needs everything before it, and send-receipt needs the order, so they run one after another. The checkout takes about 14 s instead of 19.5 s for every step in a row.',
      },
    ],
    keyIdea:
      'Use join() when the next step needs the result of this one. Start independent steps together, and wait for all of them before you go on.',
    mistakeId: 'UNNECESSARY_ASYNC_AWAIT',
  },
  {
    demoId: 'exception',
    level: 'intermediate',
    tags: ['exceptions', 'failure', 'cancellation'],
    story:
      'An account page loads five things at once: the delivery status, the profile, the orders, the invoices and the loyalty points. Two of them arrive. Then, after 12 s, the loyalty service answers with an error (HTTP 500).',
    code: `launch {                                            // 1 load-account-page
    launch { fetchDeliveryStatus() }                // 4
    launch { fetchProfile() }                       // 4
    launch { fetchOrders() }                        // 3
    launch { fetchInvoices() }                      // 3
    launch {
        fetchLoyaltyPoints()
        throw IOException("HTTP 500")               // 2
    }
}`,
    steps: [
      {
        title: 'Five requests start at once',
        text: 'Each request runs as a child of load-account-page. The delivery status arrives after 3.5 s and the profile after 6 s.',
      },
      {
        title: 'The loyalty service fails',
        text: 'After 12 s, fetch-loyalty-points throws an exception because the service answered HTTP 500. The coroutine fails.',
      },
      {
        title: 'The failure cancels the rest',
        text: 'The exception goes up to the parent. The parent fails and cancels its other children: fetch-orders and fetch-invoices are still loading, so they are cancelled.',
      },
      {
        title: 'Finished work stays finished',
        text: 'The delivery status and the profile were already done, so they stay completed. The page itself ends as failed.',
      },
    ],
    keyIdea:
      'When one child fails, its parent fails too and cancels its other children. If one part is allowed to fail on its own, catch the error in that child, or use supervisorScope.',
    mistakeId: 'UNHANDLED_EXCEPTION',
  },
  {
    demoId: 'dispatcher',
    level: 'intermediate',
    tags: ['dispatchers', 'Dispatchers.IO', 'Dispatchers.Default', 'threads'],
    story:
      'An app makes thumbnails of three photos that arrive 2 s apart. For each photo it downloads the image, makes it smaller and uploads the thumbnail. Downloading and uploading mostly wait for the network. Making the image smaller keeps the CPU busy.',
    code: `launch(Dispatchers.Default) {                       // make-thumbnails
    for (photo in 1..3) {
        if (photo > 1) delay(2_000)                 // 1
        launch {                                    // process-photo-N
            launch(Dispatchers.IO) { download(photo) }.join()       // 2
            launch(Dispatchers.Default) { resize(photo) }.join()    // 3
            launch(Dispatchers.IO) { upload(photo) }.join()         // 4
        }
    }
}`,
    steps: [
      {
        title: 'A new photo every 2 s',
        text: 'make-thumbnails starts process-photo-1, then process-photo-2 after 2 s, then process-photo-3 after another 2 s. Each photo runs in its own coroutine.',
      },
      {
        title: 'Download on IO',
        text: 'download-photo-N runs on Dispatchers.IO (3 s). IO lets many coroutines block at once (at least 64), because work that waits for the network or the disk does not keep a CPU busy.',
      },
      {
        title: 'Resize on Default',
        text: 'resize-photo-N runs on Dispatchers.Default (4 s). Default runs about one coroutine per CPU core at a time, the right number for work that computes.',
      },
      {
        title: 'Upload on IO again',
        text: 'upload-thumbnail-N goes back to IO (1.5 s). While photo 1 is resized on Default, photos 2 and 3 download on IO: two dispatchers at work at the same moment. The two share one pool of threads underneath, but each keeps its own limit. The demo takes about 13 s.',
      },
    ],
    keyIdea:
      'Choose the dispatcher by the kind of work: Dispatchers.IO for waiting on the network or the disk, Dispatchers.Default for work that keeps the CPU busy.',
    mistakeId: 'WRONG_DISPATCHER',
  },
]
