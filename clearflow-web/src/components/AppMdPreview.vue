<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import Vditor from 'vditor'
import 'vditor/dist/index.css'

// ===== Markdown 只读预览（Vditor 静态渲染，输出为 HTML）=====
// 渲染完成后回传标题结构（@headings），供外层生成导航目录
const props = defineProps({ markdown: { type: String, default: '' } })
const emit = defineEmits(['headings'])
const el = ref(null)

const isDark = () => document.documentElement.dataset.theme === 'dark'
let seq = 0
let mo = null

async function render() {
  if (!el.value) return
  const my = ++seq
  const dark = isDark()
  // Vditor 的正文主题与代码高亮都由自己注入，必须跟随全局主题重渲染，否则暗色下正文发黑、表格发白
  await Vditor.preview(el.value, props.markdown || '', {
    mode: dark ? 'dark' : 'light',
    cdn: '/vditor', // 运行时资源走本地 public/vditor
    lang: 'zh_CN',
    theme: { current: dark ? 'dark' : 'light', path: '/vditor/dist/css/content-theme' },
    hljs: { style: dark ? 'github-dark' : 'github' }
  })
  if (my !== seq || !el.value) return
  const hs = [...el.value.querySelectorAll('h1,h2,h3,h4,h5,h6')]
  emit('headings', hs.map((h, i) => {
    h.id = `doc-h-${i}`
    return { id: h.id, level: Number(h.tagName[1]), text: h.textContent }
  }))
}

onMounted(() => {
  render()
  mo = new MutationObserver(() => nextTick(render))
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})
onBeforeUnmount(() => mo?.disconnect())
watch(() => props.markdown, () => nextTick(render))
</script>

<template>
  <div ref="el" class="md-pv"></div>
</template>

<style scoped>
/* 预览排版贴合项目风格（与 AppEditor 只读态一致） */
.md-pv { color: var(--t1) }
.md-pv :deep(h1) { font-size: 20px; margin: .6em 0 .4em }
.md-pv :deep(h2) { font-size: 17px; margin: .6em 0 .4em }
.md-pv :deep(h3) { font-size: 15px; margin: .6em 0 .4em }
.md-pv :deep(h4),
.md-pv :deep(h5),
.md-pv :deep(h6) { font-size: 14px; margin: .6em 0 .4em }
.md-pv :deep(p) { margin: .35em 0 }
.md-pv :deep(ul),
.md-pv :deep(ol) { padding-left: 1.6em; margin: .35em 0 }
.md-pv :deep(li) { margin: .15em 0 }
.md-pv :deep(blockquote) { border-left: 3px solid var(--border); padding-left: 10px; color: var(--t2); margin: .5em 0 }
.md-pv :deep(pre) { background: var(--track); border-radius: 6px; padding: 10px 12px; margin: .5em 0; overflow-x: auto }
.md-pv :deep(code) { background: var(--track); border-radius: 4px; padding: 1px 5px; font-size:13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace }
.md-pv :deep(a) { color: var(--primary) }
.md-pv :deep(img) { max-width: 100%; border-radius: 6px }
.md-pv :deep(table) { border-collapse: collapse; margin: .5em 0 }
.md-pv :deep(th),
.md-pv :deep(td) { border: 1px solid var(--border); padding: 5px 9px; font-size:13px }
.md-pv :deep(th) { background: var(--track); font-weight:700 }
.md-pv :deep(hr) { border: none; border-top: 1px solid var(--border); margin: 1em 0 }
</style>
