<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import Vditor from 'vditor'
import 'vditor/dist/index.css'
import { api } from '../api'

// ===== Markdown 编辑器（Vditor 开源内核，MIT，所见即所得）=====
// 对外接口：modelValue(Markdown) / placeholder / minHeight，编辑与存储均为 Markdown
const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '输入 Markdown 内容…' },
  minHeight: { type: Number, default: 460 }
})
const emit = defineEmits(['update:modelValue'])

const box = ref(null)
let editor = null
let last = props.modelValue || ''
let mo = null

// 运行时资源走本地 public/vditor，避免依赖 unpkg CDN
const CDN = '/vditor'
const CONTENT_THEME = `${CDN}/dist/css/content-theme`

const isDark = () => document.documentElement.dataset.theme === 'dark'
const contentTheme = () => (isDark() ? 'dark' : 'light')
const codeTheme = () => (isDark() ? 'github-dark' : 'github')

// 工具栏单行精简：排除表情、录制、缩进、分割线、全屏、导出、主题等低频按钮
const TOOLBAR = [
  'headings', 'bold', 'italic', 'strike', '|',
  'list', 'ordered-list', 'check', '|',
  'quote', 'inline-code', 'code', 'link', 'table', '|',
  'upload', 'undo', 'redo', '|',
  'edit-mode', 'both', 'preview'
]

onMounted(() => {
  editor = new Vditor(box.value, {
    cdn: CDN,
    mode: 'wysiwyg',
    theme: isDark() ? 'dark' : 'classic',
    preview: {
      theme: { current: contentTheme(), path: CONTENT_THEME },
      hljs: { style: codeTheme() }
    },
    value: props.modelValue || '',
    placeholder: props.placeholder,
    height: 'auto', // 随内容撑高，页面整体滚动，编辑器内部不出现独立滚动条
    minHeight: props.minHeight,
    cache: { enable: false }, // 关闭 localStorage 草稿缓存，避免串文档
    counter: { enable: false },
    hint: { emoji: {} }, // 禁用表情提示（本地未打包表情图片）
    toolbar: TOOLBAR,
    // 图片上传到后端（RustFS），插入返回的读取 URL
    upload: {
      accept: 'image/*',
      handler: files => new Promise(resolve => {
        if (!files || !files.length) return resolve(null)
        Promise.all(files.map(async f => {
          try {
            const r = await api.uploadEditorImage(f)
            return r?.url ? `![${f.name}](${r.url})` : null
          } catch {
            return null
          }
        })).then(mds => {
          mds.filter(Boolean).forEach(md => editor?.insertValue(md))
          resolve(null)
        })
      })
    },
    input: md => { last = md; emit('update:modelValue', md) }
  })
  // 主题切换时同步 Vditor 的编辑器/正文/代码高亮主题（内容主题由它自己注入，不跟随会发黑发白）
  mo = new MutationObserver(() => {
    editor?.setTheme(isDark() ? 'dark' : 'classic', contentTheme(), codeTheme(), CONTENT_THEME)
  })
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

onUnmounted(() => {
  mo?.disconnect()
  mo = null
  editor?.destroy()
  editor = null
})

// 外部 v-model 变化（切换编辑对象）；自己 emit 的回环跳过
watch(() => props.modelValue, v => {
  const val = v || ''
  if (val === last || !editor) return
  last = val
  editor.setValue(val, true)
})
</script>

<template>
  <div ref="box" class="md-ed"></div>
</template>

<style scoped>
/* 贴合项目扁平风格：外框与卡片圆角、边框统一 */
.md-ed :deep(.vditor) {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
}
.md-ed :deep(.vditor-toolbar) {
  background: var(--card);
  border-bottom: 1px solid var(--border);
  padding: 4px 6px;
}
.md-ed :deep(.vditor-toolbar-item) { padding: 0 3px }
.md-ed :deep(.vditor-toolbar__divider) { margin: 4px 3px }
.md-ed :deep(.vditor-wysiwyg pre.vditor-reset),
.md-ed :deep(.vditor-sv) {
  background: var(--card);
}
.md-ed :deep(.vditor-wysiwyg),
.md-ed :deep(.vditor-sv) {
  font-size:14px;
  line-height: 1.7;
  padding: 14px 18px;
}
.md-ed :deep(.vditor-panel) { border-color: var(--border) }
.md-ed :deep(.vditor-hint) { border-color: var(--border) }
</style>
