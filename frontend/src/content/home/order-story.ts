import type { Scenario } from '@/types/api'

/** One coroutine of the front door's picture: when it runs, in seconds from the start. */
export interface StoryBar {
  /** The coroutine's label in the demo, e.g. 'OrderService.processOrder'. */
  label: string
  from: number
  to: number
  /** From when it only waits for its children, if it does. */
  waitingFrom?: number
}

/**
 * The order story as the front door draws it: a still picture, not a run. The
 * labels and times are the Order Processing demo's usual ending, as its guide
 * tells it (content/gallery/real-life.ts); order-story.test.ts keeps the labels
 * equal to the demo's "What you will see" picture, which the backend checks.
 */
export const ORDER_STORY = {
  demoId: 'order-processing',
  title: 'Order processing: running shoes for Anna',
  endSeconds: 14.5,
  bars: [
    { label: 'OrderService.processOrder', from: 0, to: 14.5, waitingFrom: 11.5 },
    { label: 'OrderValidator.validate', from: 0, to: 3.5 },
    { label: 'InventoryService.checkStock', from: 3.5, to: 6 },
    { label: 'PaymentService.processPayment', from: 6, to: 10 },
    { label: 'Database.saveOrder', from: 10, to: 11.5 },
    { label: 'EmailService.sendConfirmation', from: 11.5, to: 14.5 },
    { label: 'SmsService.sendSms', from: 11.5, to: 13.5 },
    { label: 'AnalyticsService.trackPurchase', from: 11.5, to: 12.5 },
  ] satisfies StoryBar[] as StoryBar[],
} as const

/** 'OrderService.processOrder' reads as 'processOrder' in the picture. */
export const shortLabel = (label: string) => label.slice(label.lastIndexOf('.') + 1)

/**
 * The demo behind "Watch a checkout run", for a page whose demo list has not
 * loaded (or failed): the button works at once, with the catalog id the server
 * knows the demo by.
 */
export const ORDER_STORY_DEMO: Scenario = {
  id: ORDER_STORY.demoId,
  name: 'Order Processing: Running Shoes for Anna',
  description: '',
  endpoint: `/api/scenarios/${ORDER_STORY.demoId}`,
  category: 'realistic',
}
