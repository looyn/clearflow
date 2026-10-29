<script setup>
import { ref, computed, nextTick } from 'vue'

// ===== 子任务 / 检查项（纯受控组件）=====
const props = defineProps({
  modelValue: { type: Array, default: () => [] }, // [{ id, title, done }]
  label: { type: String, default: '检查项' },
  placeholder: { type: String, default: '输入检查项内容，回车添加' },
  addable: { type: Boolean, default: true },
  removable: { type: Boolean, default: true }
})
const emit = defineEmits(['update:modelValue'])

const adding = ref(false)    // 是否处于新增输入态
const draft = ref('')        // 新增输入内容
const draftErr = ref('')     // 内联红字提示
const inputEl = ref(null)

const doneCount = computed(() => props.modelValue.filter(x => x.done).length)
const pct = computed(() =>
  props.modelValue.length ? Math.round(doneCount.value / props.modelValue.length * 100) : 0)

// 勾选 / 取消
function toggle(item) {
  emit('update:modelValue',
    props.modelValue.map(x => x.id === item.id ? { ...x, done: !x.done } : x))
}

// 删除
function remove(id) {
  emit('update:modelValue', props.modelValue.filter(x => x.id !== id))
}

// 展开新增输入框并聚焦
async function openAdd() {
  draft.value = ''
  draftErr.value = ''
  adding.value = true
  await nextTick()
  inputEl.value?.focus()
}

function cancelAdd() {
  adding.value = false
  draftErr.value = ''
}

// 回车新增（空值不加，红字提示）
function commitAdd() {
  const title = draft.value.trim()
  if (!title) { draftErr.value = '内容不能为空'; return }
  emit('update:modelValue', [
    ...props.modelValue,
    { id: 'c' + Date.now() + Math.random().toString(36).slice(2, 6), title, done: false }
  ])
  draft.value = ''
  draftErr.value = ''
}
</script>

<template>
  <div class="ck">
    <!-- 头部：label + 进度 -->
    <div class="ck-head">
      <span class="ck-label">{{ label }}</span>
      <span class="ck-num">{{ doneCount }}/{{ modelValue.length }}</span>
      <span class="ck-track"><i :style="{ width: pct + '%' }"></i></span>
    </div>

    <!-- 行 -->
    <div v-if="modelValue.length" class="ck-list">
      <div v-for="it in modelValue" :key="it.id" class="ck-row">
        <button type="button" class="ck-box" :class="{ on: it.done }" @click="toggle(it)">
          <svg v-if="it.done" viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>
        </button>
        <span class="ck-title" :class="{ done: it.done }">{{ it.title }}</span>
        <button v-if="removable" type="button" class="ck-del" title="删除" @click="remove(it.id)">×</button>
      </div>
    </div>
    <div v-else-if="!adding" class="ck-empty">暂无，添加第一项</div>

    <!-- 新增 -->
    <div v-if="addable" class="ck-add-area">
      <button v-if="!adding" type="button" class="ck-add" @click="openAdd">+ 添加一项</button>
      <template v-else>
        <input
          ref="inputEl" v-model="draft" class="field-input ck-input"
          :placeholder="placeholder"
          @input="draftErr = ''"
          @keydown.enter.prevent="commitAdd"
          @keydown.esc.prevent="cancelAdd">
        <div v-if="draftErr" class="ck-err">{{ draftErr }}</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
/* 头部 */
.ck-head{display:flex;align-items:center;gap:8px;margin-bottom:6px}
.ck-label{font-size:13px;font-weight:700}
.ck-num{font-size:12px;color:var(--t3);font-variant-numeric:tabular-nums}
.ck-track{flex:1;height:4px;border-radius:2px;background:var(--track);overflow:hidden}
.ck-track i{display:block;height:100%;border-radius:2px;background:var(--green)}

/* 行 */
.ck-row{display:flex;align-items:center;gap:9px;padding:6px 0}
.ck-box{flex:none;width:16px;height:16px;border:1.5px solid var(--border);border-radius:4px;
  background:var(--card);cursor:pointer;padding:0;display:flex;align-items:center;justify-content:center}
.ck-box.on{background:var(--primary-fill);border-color:var(--primary-fill)}
.ck-box svg{width:11px;height:11px;stroke:#fff;stroke-width:2.6;fill:none;stroke-linecap:round;stroke-linejoin:round}
.ck-title{flex:1;min-width:0;font-size:13px;color:var(--t1);line-height:1.5;word-break:break-word}
.ck-title.done{text-decoration:line-through;color:var(--t3)}
.ck-del{flex:none;width:20px;height:20px;border:none;background:none;padding:0;color:var(--t3);
  font-size:16px;line-height:1;cursor:pointer;font-family:inherit;
  display:flex;align-items:center;justify-content:center}
.ck-empty{font-size:13px;color:var(--t3);padding:4px 0 2px}

/* 新增 */
.ck-add-area{margin-top:6px}
.ck-add{border:none;background:none;padding:0;font-size:13px;color:var(--t3);
  cursor:pointer;font-family:inherit}
.ck-input{height:32px}
.ck-err{font-size:12px;color:var(--red);margin-top:4px}
</style>
