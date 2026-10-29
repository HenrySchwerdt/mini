<script setup lang="ts">
import DefaultTheme from 'vitepress/theme'
import { nextTick, onMounted, watch } from 'vue'
import { useRoute } from 'vitepress'
import { decorateCodeBlocks } from './open-in-playground.ts'

const { Layout } = DefaultTheme
const route = useRoute()

// Code blocks are rendered at build time; the "Open in playground" button is added in the browser.
onMounted(() => decorateCodeBlocks())
watch(
  () => route.path,
  () => nextTick(() => decorateCodeBlocks()),
)
</script>

<template>
  <Layout />
</template>
