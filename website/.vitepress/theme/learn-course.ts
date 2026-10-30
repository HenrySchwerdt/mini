/** Shared order for the sidebar, chapter header and previous/next navigation. */
export const learnChapters = [
  { slug: 'hello', title: 'Hello, Mini', part: 'Kotlin foundations', minutes: 10 },
  { slug: 'basics', title: 'Values, functions and control flow', part: 'Kotlin foundations', minutes: 25 },
  { slug: 'classes', title: 'Classes, data and sealed types', part: 'Kotlin foundations', minutes: 25 },
  { slug: 'collections', title: 'Lambdas, collections and strings', part: 'Kotlin foundations', minutes: 25 },
  { slug: 'errors', title: 'Null safety and exceptions', part: 'Kotlin foundations', minutes: 20 },
  { slug: 'generics', title: 'Generics and type contracts', part: 'Thinking in Kotlin', minutes: 25 },
  { slug: 'extensions', title: 'Extensions, receivers and scope', part: 'Thinking in Kotlin', minutes: 20 },
  { slug: 'project', title: 'Build a task report', part: 'Thinking in Kotlin', minutes: 35 },
  { slug: 'memory-managed', title: 'Memory: managed by default', part: 'What makes Mini different', minutes: 20 },
  { slug: 'memory-allocators', title: 'Allocators and arenas', part: 'What makes Mini different', minutes: 25 },
  { slug: 'memory-strict', title: 'Strict mode and ownership', part: 'What makes Mini different', minutes: 30 },
  { slug: 'c-interop', title: 'The native boundary: C interop', part: 'What makes Mini different', minutes: 20 },
  { slug: 'performance', title: 'Measure and understand performance', part: 'What makes Mini different', minutes: 25 },
  { slug: 'matrix', title: 'The @ operator', part: 'What makes Mini different', minutes: 15 },
  { slug: 'workflow', title: 'From notebook to native project', part: 'Keep building', minutes: 20 },
] as const

export const learnSidebar = [
  { text: 'Learn Mini', items: [{ text: 'Start here', link: '/learn/' }] },
  ...[...new Set(learnChapters.map((c) => c.part))].map((part) => ({
    text: part,
    items: learnChapters.flatMap((c, i) => c.part === part ? [{ text: `${i + 1}. ${c.title}`, link: `/learn/${c.slug}` }] : []),
  })),
]
