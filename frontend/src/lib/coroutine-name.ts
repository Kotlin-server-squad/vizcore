/** A coroutine's name as a card shows it: what it belongs to (a class, a dotted prefix) and its own name. */
export interface CoroutineNameParts {
  /** "OrderService" of "OrderService.processOrder"; null for a name with no such part. */
  owner: string | null
  name: string
}

/**
 * "Class.method" → the class and the method, split at the last dot. A name with a space ("Step 1. Load"), with
 * nothing on one side of the dot, or whose last part starts with a digit ("v1.2") is one name.
 */
export function splitCoroutineName(label: string): CoroutineNameParts {
  const dot = label.lastIndexOf('.')
  if (dot <= 0 || dot === label.length - 1 || /\s/.test(label) || /\d/.test(label[dot + 1]!)) {
    return { owner: null, name: label }
  }
  return { owner: label.slice(0, dot), name: label.slice(dot + 1) }
}

/**
 * Where a name may wrap: after a separator (. _ - / : $) and at a camelCase boundary ("processOrder" → "process",
 * "Order"; "HTTPServer" → "HTTP", "Server"). The pieces joined are the name again.
 */
export function wrapPieces(name: string): string[] {
  return name.split(/(?<=[._\-/:$])|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])/).filter(Boolean)
}
