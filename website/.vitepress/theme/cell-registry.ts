/**
 * The runnable cells that are mounted on the current page, in document order. Cells use it to find the cells above them
 * (notebook semantics) and the page toolbar uses it for "Run all" and "Reset all".
 */
import { ref } from 'vue'
import type { CellSpec } from './snippets.ts'

export interface CellHandle {
  spec: CellSpec
  /** Current source, including edits. */
  getSource(): string
  /** Runs (or checks) the cell. Resolves to whether it ended as expected. */
  run(): Promise<boolean>
  reset(): void
  isBusy(): boolean
}

const pages = new Map<string, Map<number, CellHandle>>()
/** Bumped on every (un)registration so that dependants (the "Run all" bar) re-render. */
export const registryVersion = ref(0)

export function registerCell(page: string, h: CellHandle) {
  let m = pages.get(page)
  if (!m) pages.set(page, (m = new Map()))
  m.set(h.spec.index, h)
  registryVersion.value++
}

export function unregisterCell(page: string, h: CellHandle) {
  const m = pages.get(page)
  if (m?.get(h.spec.index) === h) m.delete(h.spec.index)
  if (m && m.size === 0) pages.delete(page)
  registryVersion.value++
}

/** The mounted cells of a page ordered by their position in the document. */
export function cellsOf(page: string): CellHandle[] {
  void registryVersion.value
  return [...(pages.get(page)?.values() ?? [])].sort((a, b) => a.spec.index - b.spec.index)
}
