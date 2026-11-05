import type { DemoGuide } from './types'

/**
 * The "How it works" guides for the Flow demos, written from the demos' real
 * code in `backend/.../scenarios/FlowScenarios.kt`: the coroutine names, the
 * order of the steps and the times (in seconds at normal speed). The Kotlin
 * snippets are simplified, but keep the demo's structure. The only
 * `// <number>` comments are step markers; durations read `// takes 1.5 s`.
 *
 * The tree shows the coroutines; the values themselves travel through the
 * session's "Flow backpressure" panel. A collector that waits for its next
 * value shows as active in the tree: the flow emits no suspension for that.
 */
export const FLOW_GUIDES: DemoGuide[] = [
  {
    demoId: 'flow/simple',
    level: 'beginner',
    tags: ['cold flow', 'flow { }', 'collect'],
    story:
      'A stock ticker sends the Acme share price, one price every 1.5 seconds. A price chart starts to collect the prices at once. Four seconds later a price alert starts to collect them too. Each screen gets every price, from the first one, because the flow runs again for each collector.',
    code: `val prices = flow {                       // 1: acme-prices
    for (price in acmePrices) {
        delay(1500)                       // takes 1.5 s
        emit(price)
    }
}

launch { prices.collect { draw(it) } }    // 2: price-chart
delay(4000)
launch { prices.collect { check(it) } }   // 3: price-alert`,
    steps: [
      {
        title: 'The flow does nothing yet',
        text: 'flow { } only describes how to make the prices. Nothing runs until someone collects it. That is why it is called a cold flow.',
      },
      {
        title: 'The chart collects',
        text: 'price-chart calls collect. Now the flow block runs inside price-chart: it waits 1.5 s, sends a price, and the chart draws it for 0.5 s. In the tree, price-chart is suspended most of the time.',
      },
      {
        title: 'The alert collects too, from the start',
        text: 'After 4 s, price-alert calls collect. The flow block runs again, just for the alert, and starts with the first price, 101.2, even though the chart already has two prices. The chart ends at about 12 s, the alert at about 16 s.',
      },
    ],
    keyIdea:
      'A cold flow runs its block once for every collector. Each collector gets all values, from the first one, in its own coroutine.',
  },
  {
    demoId: 'flow/operators',
    level: 'intermediate',
    tags: ['debounce', 'filter', 'map', 'take'],
    story:
      'A search box searches while you type. It must not ask the server on every key. So it waits until you stop typing, skips queries that are too short, and only then asks for results. The user types "ko", thinks, types "tlin", thinks again, and types " flow".',
    code: `keystrokes                                // show-results
    .debounce(1200)                       // 1
    .filter { it.length >= 3 }            // 2
    .map { query -> search(query) }       // 3, takes 1.5 s
    .take(2)                              // 4
    .collect { show(it) }                 // takes 0.5 s`,
    steps: [
      {
        title: 'Wait for a pause in typing',
        text: 'type-query sends the text after each key, every 0.5 s. debounce lets a text through only when no new key comes for 1.2 s. So "k" is never searched, but "ko" is, when the user stops to think.',
      },
      {
        title: 'Skip short queries',
        text: 'filter drops every query shorter than three letters. "ko" stops here and never reaches the server.',
      },
      {
        title: 'Ask for results',
        text: 'map turns a query into search results. It takes 1.5 s, and show-results is suspended while it waits. Here it runs for "kotlin" and later for "kotlin flow".',
      },
      {
        title: 'Stop after two searches',
        text: 'take(2) ends the collection after two results, so show-results completes at about 14 s. The operators always work in this order, on one value at a time.',
      },
    ],
    keyIdea:
      'Flow operators form a pipeline: each value passes through them in order, and each operator can wait, drop or change it.',
  },
  {
    demoId: 'flow/stateflow',
    level: 'intermediate',
    tags: ['StateFlow', 'latest value'],
    story:
      'A shopping cart keeps its total in a StateFlow. The cart badge shows the total from the start. The user adds an item every few seconds. Later the user opens the checkout page, and it shows the current total at once, without waiting for the next change.',
    code: `val total = MutableStateFlow(0)           // 1: cart-total

launch { total.collect { showBadge(it) } }        // cart-badge
launch {                                          // add-items
    for (newTotal in listOf(25, 40, 52)) {
        delay(2000)                               // takes 2 s
        total.value = newTotal                    // 2
    }
    // later: 64, then 70
}
launch { total.collect { showCheckout(it) } }     // 3: checkout-summary`,
    steps: [
      {
        title: 'The state always has a value',
        text: 'A StateFlow starts with a value, here 0. cart-badge collects it and shows 0 at once.',
      },
      {
        title: 'Every change reaches the badge',
        text: 'add-items sets the total to 25, 40 and 52, two seconds apart. Each time, cart-badge gets the new total. Between changes the badge waits, and the tree shows it as active.',
      },
      {
        title: 'A late collector gets the latest value',
        text: 'checkout-summary opens at about 7 s. It gets 52 at once, the current total, and then 64 and 70 as they come. It never sees 0, 25 or 40. The demo ends at about 12 s, when the total is 70.',
      },
    ],
    keyIdea:
      'A StateFlow always holds one current value. A new collector gets that value at once, then every change after it.',
    mistakeId: 'SHARED_MUTABLE_STATE',
  },
  {
    demoId: 'flow/sharedflow',
    level: 'intermediate',
    tags: ['SharedFlow', 'broadcast', 'replay'],
    story:
      'A phone app shows notifications on every open screen. The home, chat and settings screens are open. A push server sends five notifications, 2.5 seconds apart, and every screen gets each one at the same moment. The profile screen opens later and gets only the notifications that come after it opens.',
    code: `val notifications = MutableSharedFlow<String>()       // 1

for (screen in openScreens) {
    launch { notifications.collect { draw(it) } }     // home, chat, settings
}
launch {                                              // push-server
    for (n in pushNotifications) {
        delay(2500)                                   // takes 2.5 s
        notifications.emit(n)                         // 2
    }
}
launch { notifications.collect { draw(it) } }         // 3: profile-screen`,
    steps: [
      {
        title: 'One flow, many subscribers',
        text: 'A SharedFlow is hot: it runs on its own, not once per collector. Three screens subscribe by calling collect.',
      },
      {
        title: 'Each notification goes to every screen',
        text: 'push-server emits a notification, and all subscribed screens get it at the same moment. Each screen draws it at its own speed: 0.5, 1 or 1.5 s.',
      },
      {
        title: 'A late screen gets only new notifications',
        text: 'profile-screen opens after the second notification. This SharedFlow has no replay, so it keeps nothing for latecomers. The profile screen gets only the last three. The demo ends at about 14 s.',
      },
    ],
    keyIdea:
      'A SharedFlow broadcasts each value to everyone subscribed at that moment. Without replay, a new subscriber gets only what comes next.',
    mistakeId: 'LEAKED_COROUTINE',
  },
]
