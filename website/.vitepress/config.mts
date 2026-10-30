import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'
import diagnosticsSidebar from './diagnostics-sidebar.json'
import { miniCellsPlugin } from './mini-cells.ts'
import { learnSidebar } from './theme/learn-course.ts'

// GitHub Pages serves the site under /mini/ (https://henryschwerdt.github.io/mini/).
const base = '/mini/'

const guideSidebar = [
          {
            text: 'Guide',
            items: [
              { text: 'Install', link: '/install' },
              { text: 'Getting started', link: '/guide/getting-started' },
              { text: 'Mini and Kotlin/JVM', link: '/guide/mini-vs-kotlin' },
              { text: 'C interop', link: '/guide/c-interop' },
              { text: 'WebAssembly libraries', link: '/guide/wasm-library' },
              { text: 'Command line', link: '/reference/cli' },
              { text: 'Playground and notebook', link: '/guide/playground' },
              { text: 'Learn Mini (tutorial)', link: '/learn/' },
            ],
          },
        ]

export default withMermaid(
  defineConfig({
    title: 'Mini',
    description: 'Kotlin compiled to native code, with a memory model the compiler can check.',
    lang: 'en-US',
    base,
    cleanUrls: true,
    lastUpdated: false,
    // Dead links fail the build (this is VitePress' default; do not turn it off).
    ignoreDeadLinks: false,
    head: [['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}logo.svg` }]],
    markdown: {
      theme: { light: 'github-light', dark: 'github-dark' },
      lineNumbers: false,
      // every runnable Kotlin fence becomes a <MiniCell> (see theme/snippets.ts for the fence syntax)
      config: (md) => {
        md.use(miniCellsPlugin)
      },
    },
    themeConfig: {
      logo: '/logo.svg',
      siteTitle: 'Mini',
      nav: [
        { text: 'Learn', link: '/learn/', activeMatch: '/learn/' },
        { text: 'Install', link: '/install' },
        { text: 'Guide', link: '/guide/getting-started', activeMatch: '/guide/' },
        {
          text: 'Reference',
          activeMatch: '/reference/|/stdlib',
          items: [
            { text: 'mini: the project tool', link: '/reference/mini' },
            { text: 'minic: the compiler', link: '/reference/minic' },
            { text: 'Diagnostics', link: '/reference/diagnostics/' },
            { text: 'Standard library', link: '/stdlib' },
          ],
        },
        { text: 'Memory', link: '/memory/', activeMatch: '/memory/' },
        { text: 'Libraries', link: '/libraries/', activeMatch: '/libraries/' },
        { text: 'Playground', link: '/playground' },
        {
          text: 'More',
          items: [
            { text: 'Notebook', link: '/notebook' },
            { text: 'Language tour (listings)', link: '/tour/' },
            { text: 'How it works', link: '/internals/' },
            { text: 'Benchmarks', link: '/benchmarks' },
            { text: 'Roadmap and status', link: '/roadmap' },
          ],
        },
      ],
      sidebar: {
        '/learn/': learnSidebar,
        '/guide/': guideSidebar,
        '/install': guideSidebar,
        '/tour/': [
          {
            text: 'Language tour',
            items: [
              { text: 'Overview', link: '/tour/' },
              { text: 'Basics', link: '/tour/basics' },
              { text: 'Classes and interfaces', link: '/tour/classes' },
              { text: 'Data, enum and sealed', link: '/tour/types' },
              { text: 'Generics', link: '/tour/generics' },
              { text: 'Lambdas and inline', link: '/tour/lambdas' },
              { text: 'Exceptions', link: '/tour/exceptions' },
              { text: 'Extensions and null safety', link: '/tour/extensions' },
              { text: 'Collections and strings', link: '/tour/collections' },
            ],
          },
        ],
        '/memory/': [
          {
            text: 'Memory guide',
            items: [
              { text: 'Overview', link: '/memory/' },
              { text: '1. Managed by default', link: '/memory/managed' },
              { text: '2. Allocators and context', link: '/memory/allocators' },
              { text: '3. Arenas and Scoped', link: '/memory/arenas' },
              { text: '4. Strict mode', link: '/memory/strict' },
              { text: '5. Ownership', link: '/memory/ownership' },
              { text: '6. Fields and containers', link: '/memory/fields' },
              { text: '7. Lambdas and captures', link: '/memory/lambdas' },
              { text: '8. Threads and ThreadSafe', link: '/memory/threads' },
              { text: '9. @NoAlloc', link: '/memory/noalloc' },
            ],
          },
          { text: 'Diagnostics reference', link: '/reference/diagnostics/' },
        ],
        '/libraries/': [
          {
            text: 'Libraries',
            items: [
              { text: 'Overview', link: '/libraries/' },
              { text: 'mini.plot', link: '/libraries/plot' },
              { text: 'Rich output (mini.display)', link: '/libraries/display' },
            ],
          },
        ],
        '/reference/diagnostics/': [{ text: 'Diagnostics', items: diagnosticsSidebar }],
        '/reference/': [
          {
            text: 'Reference',
            items: [
              { text: 'Command line', link: '/reference/cli' },
              { text: 'mini: the project tool', link: '/reference/mini' },
              { text: 'minic: the compiler', link: '/reference/minic' },
              { text: 'Diagnostics', link: '/reference/diagnostics/' },
              { text: 'Standard library', link: '/stdlib' },
            ],
          },
        ],
        '/internals/': [
          {
            text: 'How it works',
            items: [
              { text: 'Pipeline', link: '/internals/' },
              { text: 'MIR', link: '/internals/mir' },
              { text: 'Object layout and dispatch', link: '/internals/objects' },
              { text: 'Exceptions', link: '/internals/exceptions' },
              { text: 'Closures and inline', link: '/internals/closures' },
              { text: 'Generics', link: '/internals/generics' },
              { text: 'Reference counting and RcOpt', link: '/internals/refcounting' },
              { text: 'Memory checker', link: '/internals/checker' },
              { text: 'The runtime', link: '/internals/runtime' },
              { text: 'Self-hosting and bootstrap', link: '/internals/bootstrap' },
            ],
          },
        ],
        '/': [
          {
            text: 'More',
            items: [
              { text: 'Standard library', link: '/stdlib' },
              { text: 'Benchmarks', link: '/benchmarks' },
              { text: 'Roadmap and status', link: '/roadmap' },
            ],
          },
        ],
      },
      socialLinks: [{ icon: 'github', link: 'https://github.com/HenrySchwerdt/mini' }],
      search: { provider: 'local' },
      editLink: {
        pattern: 'https://github.com/HenrySchwerdt/mini/edit/main/website/:path',
        text: 'Edit this page on GitHub',
      },
      outline: { level: [2, 3] },
      footer: {
        message: 'Released under the Apache License 2.0.',
        copyright: 'Mini: Kotlin, compiled to native code.',
      },
    },
    mermaid: {},
    vite: {
      optimizeDeps: { include: ['mermaid'] },
    },
  })
)
