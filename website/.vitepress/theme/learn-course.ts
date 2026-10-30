/** Shared order for the sidebar, chapter header and previous/next navigation. */
export const learnChapters = [
  { slug: 'hello', title: 'Hello, Mini', part: 'Getting started', minutes: 10 },
  { slug: 'values', title: 'Values, types and expressions', part: 'Getting started', minutes: 35 },
  { slug: 'basics', title: 'Branching, functions and loops', part: 'Getting started', minutes: 35 },
  { slug: 'functions', title: 'Functions, scope and decomposition', part: 'Getting started', minutes: 35 },
  { slug: 'classes', title: 'Classes, data and sealed types', part: 'Model data and behavior', minutes: 35 },
  { slug: 'modelling', title: 'Model states and protect invariants', part: 'Model data and behavior', minutes: 40 },
  { slug: 'collections', title: 'Lambdas, collections and strings', part: 'Work with data', minutes: 35 },
  { slug: 'collection-design', title: 'Choose and transform collections', part: 'Work with data', minutes: 40 },
  { slug: 'errors', title: 'Null safety and exceptions', part: 'Work with data', minutes: 30 },
  { slug: 'error-design', title: 'Design failure and parse input', part: 'Work with data', minutes: 40 },
  { slug: 'generics', title: 'Generics and type contracts', part: 'Build reusable programs', minutes: 35 },
  { slug: 'extensions', title: 'Extensions, receivers and scope', part: 'Build reusable programs', minutes: 30 },
  { slug: 'project', title: 'Build a task report', part: 'Build reusable programs', minutes: 45 },
  { slug: 'memory-managed', title: 'Memory: managed by default', part: 'Understand Mini memory', minutes: 30 },
  { slug: 'memory-graphs', title: 'Draw reference graphs and lifetimes', part: 'Understand Mini memory', minutes: 40 },
  { slug: 'memory-allocators', title: 'Allocators and arenas', part: 'Understand Mini memory', minutes: 40 },
  { slug: 'memory-strict', title: 'Strict mode and ownership', part: 'Understand Mini memory', minutes: 45 },
  { slug: 'ownership', title: 'Ownership across API boundaries', part: 'Design Mini lifetimes', minutes: 45 },
  { slug: 'resources', title: 'Resources and error paths', part: 'Design Mini lifetimes', minutes: 40 },
  { slug: 'c-interop', title: 'The native boundary: C interop', part: 'Design Mini lifetimes', minutes: 35 },
  { slug: 'performance', title: 'Measure and understand performance', part: 'Design Mini lifetimes', minutes: 35 },
  { slug: 'memory-design', title: 'Choose memory boundaries deliberately', part: 'Design Mini lifetimes', minutes: 40 },
  { slug: 'matrix', title: 'The @ operator', part: 'Practice and build', minutes: 20 },
  { slug: 'testing', title: 'Test behavior and debug systematically', part: 'Practice and build', minutes: 40 },
  { slug: 'workflow', title: 'From notebook to native project', part: 'Practice and build', minutes: 35 },
  { slug: 'capstone', title: 'Capstone: a reliable import report', part: 'Practice and build', minutes: 75 },
] as const

export const learnSidebar = [
  { text: 'Learn Mini', items: [{ text: 'Start here', link: '/learn/' }] },
  ...[...new Set(learnChapters.map((c) => c.part))].map((part) => ({
    text: part,
    items: learnChapters.flatMap((c, i) => c.part === part ? [{ text: `${i + 1}. ${c.title}`, link: `/learn/${c.slug}` }] : []),
  })),
]
