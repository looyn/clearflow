<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { api } from '../api'
import { useProjectsStore } from '../stores/projects'
import { useMembersStore } from '../stores/members'
import { usePermStore } from '../stores/perm'
import AppModal from './AppModal.vue'

// props: project 为 null 表示新建；open 控制显隐
const props = defineProps({
  project: { type: Object, default: null },
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['close'])

// ===== 可选项目色（8 色）=====
const COLORS = ['#3370FF', '#22C55E', '#7A5AF8', '#FF7D00', '#F54A45', '#12B7C4', '#E0218A', '#8F959E']
const STATE_OPTIONS = [
  { key: 'active', label: '进行中' },
  { key: 'planned', label: '规划中' },
  { key: 'archived', label: '已归档' }
]

const pstore = useProjectsStore() // 保存成功后刷新共享项目数据
const mstore = useMembersStore() // 负责人下拉（共享）
const perm = usePermStore() // 负责人变更仅系统管理员（与后端 ProjectService.update 一致）
const members = computed(() => mstore.list)
const form = reactive({
  name: '', code: '', desc: '', owner: '', state: 'active',
  start: '', end: '', color: COLORS[0]
})
const err = reactive({ name: '', code: '' })
const submitting = ref(false)

// 打开时拉成员并回填（新建则重置表单；成员统一在项目卡片「管理成员」中维护，不在此表单内）
watch(() => props.open, async v => {
  if (!v) return
  await mstore.load()
  const p = props.project
  Object.assign(form, {
    name: p?.name || '', code: p?.code || '', desc: p?.desc || '',
    owner: p?.owner || '', state: p?.state || 'active',
    start: p?.start || '', end: p?.end || '',
    color: p?.color || COLORS[0]
  })
  err.name = ''
  err.code = ''
})

// ===== 即时校验 =====
function checkName() { err.name = form.name.trim() ? '' : '项目名称不能为空' }
function checkCode() { err.code = /^[A-Z]{2,5}$/.test(form.code) ? '' : '代号须为 2~5 位大写字母' }

// ===== 提交：新建 createProject / 编辑 updateProject =====
async function submit() {
  checkName()
  checkCode()
  if (err.name || err.code) return
  submitting.value = true
  try {
    if (props.project) {
      await api.updateProject(props.project.id, { ...form })
    } else {
      await api.createProject({ ...form })
    }
    await pstore.load() // 列表/当前项目可能变化，刷新共享数据
    emit('close')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AppModal v-if="open" :title="project ? '编辑项目' : '新建项目'"
    :sub="project ? '修改项目基本信息' : '创建后可在项目管理中继续调整'" :width="560" @close="emit('close')">
    <div class="field-row">
      <div class="field">
        <label class="field-label">项目名称<span class="req">*</span></label>
        <input class="field-input" :class="{ invalid: err.name }" v-model="form.name"
          placeholder="如：电商中台重构计划" @input="checkName" />
        <div v-if="err.name" class="field-error">{{ err.name }}</div>
      </div>
      <div class="field">
        <label class="field-label">项目代号<span class="req">*</span></label>
        <input class="field-input" :class="{ invalid: err.code }" v-model="form.code" maxlength="5"
          placeholder="2~5 位大写字母，如 EMP" @input="checkCode" />
        <div v-if="err.code" class="field-error">{{ err.code }}</div>
      </div>
    </div>

    <div class="field">
      <label class="field-label">描述</label>
      <textarea class="field-textarea" v-model="form.desc" placeholder="项目背景与目标"></textarea>
    </div>

    <div class="field-row">
      <div class="field">
        <label class="field-label">负责人</label>
        <select class="field-select" v-model="form.owner" :disabled="project && !perm.isAdmin">
          <option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }}</option>
        </select>
        <div v-if="project && !perm.isAdmin" class="field-hint">负责人由系统管理员变更</div>
      </div>
      <div class="field">
        <label class="field-label">状态</label>
        <select class="field-select" v-model="form.state">
          <option v-for="s in STATE_OPTIONS" :key="s.key" :value="s.key">{{ s.label }}</option>
        </select>
      </div>
    </div>

    <div class="field-row">
      <div class="field">
        <label class="field-label">起始日期</label>
        <input class="field-input" v-model="form.start" placeholder="2026-10-01 00:00:00" />
      </div>
      <div class="field">
        <label class="field-label">结束日期</label>
        <input class="field-input" v-model="form.end" placeholder="2026-12-31 00:00:00" />
      </div>
    </div>

    <div class="field">
      <label class="field-label">颜色</label>
      <div class="color-row">
        <span v-for="c in COLORS" :key="c" class="color-dot" :class="{ on: form.color === c }"
          :style="{ background: c }" @click="form.color = c"></span>
      </div>
    </div>

    <template #footer>
      <button class="ghost-btn" @click="emit('close')">取消</button>
      <button class="btn" :disabled="submitting" @click="submit">
        {{ submitting ? '提交中…' : (project ? '保存' : '创建项目') }}
      </button>
    </template>
  </AppModal>
</template>

<style scoped>
.color-row{display:flex;gap:10px}
.color-dot{width:22px;height:22px;border-radius:7px;cursor:pointer}
.color-dot.on{box-shadow:0 0 0 2px var(--card),0 0 0 4px var(--primary)}
.field-hint{font-size:12px;color:var(--t3);margin-top:4px}

.btn:disabled{opacity:.6;cursor:default}
</style>
