import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'
import diagnosticsSidebar from './diagnostics-sidebar.json'

// GitHub Pages serves the site under /mini/ (https://henryschwerdt.github.io/mini/).
const base = '/mini/'

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
    },
    themeConfig: {
      logo: '/logo.svg',
      siteTitle: 'Mini',
      nav: [
        { text: 'Guide', link: '/guide/getting-started', activeMatch: '/guide/' },
        { text: 'Language tour', link: '/tour/', activeMatch: '/tour/' },
        { text: 'Memory', link: '/memory/', activeMatch: '/memory/' },
        { text: 'Diagnostics', link: '/reference/diagnostics/', activeMatch: '/reference/' },
        { text: 'How it works', link: '/internals/', activeMatch: '/internals/' },
        {
          text: 'More',
          items: [
            { text: 'Standard library', link: '/stdlib' },
            { text: 'Benchmarks', link: '/benchmarks' },
            { text: 'Roadmap and status', link: '/roadmap' },
          ],
        },
      ],
      sidebar: {
        '/guide/': [
          {
            text: 'Guide',
            items: [
              { text: 'Getting started', link: '/guide/getting-started' },
              { text: 'Mini and Kotlin/JVM', link: '/guide/mini-vs-kotlin' },
              { text: 'C interop', link: '/guide/c-interop' },
              { text: 'Command line', link: '/reference/cli' },
            ],
          },
        ],
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
        '/reference/diagnostics/': [{ text: 'Diagnostics', items: diagnosticsSidebar }],
        '/reference/': [
          {
            text: 'Reference',
            items: [
              { text: 'Command line', link: '/reference/cli' },
              { text: 'Diagnostics', link: '/reference/diagnostics/' },
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
