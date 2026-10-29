<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { createEditor, type MiniEditor } from './editor.ts'
import type { Diagnostic } from './types.ts'

const props = defineProps<{ source: string; diagnostics: Diagnostic[]; docsUrl: (code: string) => string }>()
const emit = defineEmits<{ change: [text: string]; run: [] }>()

const host = ref<HTMLElement>()
const editor = shallowRef<MiniEditor>()

onMounted(() => {
  editor.value = createEditor({
    parent: host.value!,
    doc: props.source,
    autoHeight: true,
    docsUrl: props.docsUrl,
    placeholderLabel: 'Mini code cell',
    onChange: (t) => emit('change', t),
    onRun: () => emit('run'),
  })
  editor.value.setDiagnostics(props.diagnostics)
})

watch(
  () => props.source,
  (s) => {
    if (editor.value && editor.value.getDoc() !== s) editor.value.setDoc(s)
  },
)
watch(
  () => props.diagnostics,
  (d) => editor.value?.setDiagnostics(d),
)

onBeforeUnmount(() => editor.value?.destroy())

defineExpose({
  focus: () => editor.value?.focus(),
  reveal: (line: number, col: number) => editor.value?.reveal(line, col),
})
</script>

<template>
  <div ref="host" class="mp-cell-editor"></div>
</template>

<style scoped>
.mp-cell-editor :deep(.cm-editor) { min-height: 2.6em; }
</style>
