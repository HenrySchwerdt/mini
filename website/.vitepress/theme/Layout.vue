<script setup lang="ts">
import DefaultTheme from 'vitepress/theme'
import { nextTick, onMounted, watch } from 'vue'
import { useData, useRoute } from 'vitepress'
import { decorateCodeBlocks } from './open-in-playground.ts'
import ToolchainBadge from './ToolchainBadge.vue'
import RunAllBar from './RunAllBar.vue'
import HeroDemo from './HeroDemo.vue'
import QuickInstall from './QuickInstall.vue'

const { Layout } = DefaultTheme
const route = useRoute()
const { frontmatter } = useData()

// Code blocks are rendered at build time; the "Open in playground" button is added in the browser (runnable
// cells have their own bar, see MiniCell.vue).
onMounted(() => decorateCodeBlocks())
watch(
  () => route.path,
  () => nextTick(() => decorateCodeBlocks()),
)
</script>

<template>
  <Layout>
    <template #home-hero-image>
      <HeroDemo />
    </template>
    <template #home-hero-after>
      <QuickInstall />
    </template>
    <template #doc-before>
      <RunAllBar v-if="frontmatter.notebook" />
    </template>
  </Layout>
  <ToolchainBadge />
</template>
