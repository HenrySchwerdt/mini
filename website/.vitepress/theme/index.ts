import DefaultTheme from 'vitepress/theme'
import { defineAsyncComponent } from 'vue'
import type { Theme } from 'vitepress'
import './custom.css'
import Layout from './Layout.vue'

// The playground and the notebook (CodeMirror, the runner, the toolchain client) are separate chunks that
// are only requested when one of these components is rendered, i.e. when a visitor opens one of the pages.
const theme: Theme = {
  extends: DefaultTheme,
  Layout,
  enhanceApp({ app }) {
    app.component('Playground', defineAsyncComponent(() => import('./playground/Playground.vue')))
    app.component('Notebook', defineAsyncComponent(() => import('./playground/Notebook.vue')))
  },
}

export default theme
