import type { Mistake } from './types'

/**
 * The common coroutine mistakes, one per kind the anti-pattern detector knows.
 * `detected` marks the kinds `AntiPatternDetector.detectAll()` reports today;
 * a test keeps it in step with the detector.
 */
export const MISTAKES: Mistake[] = [
  {
    id: 'GLOBAL_SCOPE_USAGE',
    title: 'Launching in GlobalScope',
    whatGoesWrong:
      'The coroutine has no parent. Nothing waits for it and nothing cancels it, so it keeps running after the screen or request that started it is gone.',
    fix: 'Launch in a scope that has an owner: coroutineScope { }, viewModelScope, or a scope you cancel yourself.',
    detected: true,
  },
  {
    id: 'BLOCKING_ON_MAIN',
    title: 'Blocking the main thread',
    whatGoesWrong:
      'Long work runs on Dispatchers.Main without suspending. The UI cannot draw or react until it is done. The visualizer flags more than 50 ms.',
    fix: 'Move the work off Main with withContext(Dispatchers.Default) or withContext(Dispatchers.IO). Come back to Main only to update the UI.',
    detected: true,
  },
  {
    id: 'LEAKED_COROUTINE',
    title: 'Leaked coroutine',
    whatGoesWrong:
      'The coroutine started but never completed and was never cancelled: it may be waiting for something that never comes, and nothing will stop it. The visualizer flags such coroutines as possibly leaked.',
    fix: 'Keep every coroutine inside a scope that lives at least as long as it does, and cancel that scope when its owner goes away.',
    detected: true,
  },
  {
    id: 'UNNECESSARY_ASYNC_AWAIT',
    title: 'async { } and then await() at once',
    whatGoesWrong:
      'async starts a second coroutine, and await() right after waits for it straight away. Nothing runs in parallel; it only adds a coroutine.',
    fix: 'Use withContext(dispatcher) { } when you just need the result, maybe on another dispatcher. Keep async for work that really runs side by side.',
    detected: true,
  },
  {
    id: 'COROUTINE_EXPLOSION',
    title: 'Too many coroutines at once',
    whatGoesWrong:
      'One coroutine per item, with no limit. The visualizer flags 100 or more created within one second; memory and threads run out under load.',
    fix: 'Limit how many run at the same time: a Semaphore, a fixed set of workers reading a Channel, or flatMapMerge(concurrency = n).',
    detected: true,
  },
  {
    id: 'RUN_BLOCKING_MISUSE',
    title: 'runBlocking inside a coroutine',
    whatGoesWrong:
      'runBlocking blocks its thread until the work is done. Inside a coroutine or a request handler that thread can do nothing else, and with few threads the app freezes.',
    fix: 'In suspend code, call the suspend function directly or use coroutineScope { }. Keep runBlocking for main() and tests.',
    detected: false,
  },
  {
    id: 'UNHANDLED_EXCEPTION',
    title: 'Unhandled exception in launch',
    whatGoesWrong:
      'An exception nobody catches in a launched coroutine cancels its parent and all its siblings, and can crash the app.',
    fix: 'Catch it with try/catch where you can handle it, run work that may fail on its own under supervisorScope, or add a CoroutineExceptionHandler to the top scope.',
    detected: false,
  },
  {
    id: 'MISSING_CANCELLATION_CHECK',
    title: 'A loop that ignores cancellation',
    whatGoesWrong:
      'A long loop of CPU work never suspends and never checks for cancellation, so cancel() has no effect until the loop ends.',
    fix: 'Call ensureActive() or yield() inside the loop, or check isActive.',
    detected: false,
  },
  {
    id: 'SHARED_MUTABLE_STATE',
    title: 'Shared mutable state',
    whatGoesWrong:
      'Several coroutines on different threads change the same variable at the same time. Updates get lost: a counter ends lower than it should.',
    fix: 'Protect the state with a Mutex, use an atomic type, or keep it in one coroutine and send it changes over a Channel.',
    detected: false,
  },
  {
    id: 'WRONG_DISPATCHER',
    title: 'Blocking I/O on Dispatchers.Default',
    whatGoesWrong:
      'Default has only as many threads as CPU cores. Blocking calls there, such as file or database reads, take threads away from CPU work and everything slows down.',
    fix: 'Run blocking I/O in withContext(Dispatchers.IO). Keep Default for CPU work.',
    detected: false,
  },
]
