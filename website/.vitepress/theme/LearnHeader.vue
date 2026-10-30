<script setup lang="ts">
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'
import { learnChapters } from './learn-course.ts'

const props = defineProps<{ footer?: boolean }>()
const { page } = useData()
const index = computed(() => learnChapters.findIndex((c) => page.value.relativePath === `learn/${c.slug}.md`))
const chapter = computed(() => learnChapters[index.value])
const previous = computed(() => learnChapters[index.value - 1])
const next = computed(() => learnChapters[index.value + 1])
</script>

<template>
  <nav v-if="props.footer" class="learn-pagination" aria-label="Continue learning">
    <a v-if="index >= 0" :href="withBase(previous ? `/learn/${previous.slug}` : '/learn/')">
      <span>← Previous</span>{{ previous?.title ?? 'Course introduction' }}
    </a>
    <a v-if="next" :href="withBase(`/learn/${next.slug}`)"><span>Next →</span>{{ next.title }}</a>
    <a v-else :href="withBase('/notebook')"><span>Your next experiment →</span>Start your own notebook</a>
  </nav>
  <div v-else class="learn-heading">
    <a :href="withBase('/learn/')" class="learn-home">LEARN MINI</a>
    <span v-if="chapter">{{ chapter.part }} <span aria-hidden="true">/</span> {{ index + 1 }} of {{ learnChapters.length }}</span>
    <span v-else>A guided Kotlin and Mini notebook</span>
    <span v-if="chapter" class="learn-time">About {{ chapter.minutes }} min</span>
  </div>
</template>
