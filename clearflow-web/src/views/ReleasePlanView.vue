<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { useUiStore } from '../stores/ui'
import { useOptionsStore } from '../stores/options'
import { useMembersStore } from '../stores/members'
import { usePermStore } from '../stores/perm'
import PageCreateButton from '../components/PageCreateButton.vue'
import LoadingMask from '../components/LoadingMask.vue'
import AppModal from '../components/AppModal.vue'
import AppConfirm from '../components/AppConfirm.vue'
import AppEditor from '../components/AppEditor.vue'
import ActivityTimeline from '../components/ActivityTimeline.vue'
import AppPager from '../components/AppPager.vue'

const ui = useUiStore()
const opts = useOptionsStore() // 项目设置里的选项配置（含缺陷工作流状态）
const members = useMembersStore() // 成员映射（负责人/头像）
const perm = usePermStore()
// 删除发版计划需「发版计划」管理级（与后端 DELETE 接口同口径）
const canManage = computed(() => perm.canManage('发版计划'))
const route = useRoute()
const router = useRouter()
const loading = ref(true)
const rows = ref([]) // 当前页发版计划（服务端分页，行内不含发版说明正文）
const total = ref(0) // 筛选后的总条数
const stats = ref(null) // 状态计数（后端按发版工作流配置聚合）
const mmap = computed(() => members.map) // 成员映射（共享 store）
const linked = ref({ reqs: [], defects: [] }) // 详情：已关联的需求 / 缺陷（按 id 批量取）
const mail = ref({ to: [], subject: '', body: '' }) // 详情：后端生成的通知邮件（收件人邮箱只在服务端解析）

// 筛选变化会连续发请求，只认最后一次响应
let fetchSeq = 0
async function fetchData() {
  const seq = ++fetchSeq
  const [r, s] = await Promise.all([
    api.getReleases(filterParams.value, page.value, pageSize.value),
    api.getReleaseStats()
  ])
  if (seq !== fetchSeq) return
  rows.value = r.list || []
  total.value = r.total || 0
  stats.value = s
  if (!rows.value.length && page.value > 1) page.value = 1 // 删除后页码越界回收
}
onMounted(async () => {
  // 先落 URL 条件再取数，避免深链进来后重复请求
  if (route.query.status) fStatus.value = route.query.status
  if (route.query.owner) fOwner.value = route.query.owner
  if (route.query.q) fVersion.value = route.query.q
  if (route.query.focus) detailId.value = route.query.focus
  await fetchData()
  loading.value = false
})
watch(() => ui.refreshTick, fetchData)
const mmapArr = computed(() => Object.values(mmap.value))

// ===== 状态流（项目设置 → 工作流 → 发版计划工作流）：顺序即流转顺序，最后一档为终态 =====
const relKeys = computed(() => opts.keysOf('release'))
const relTerminal = computed(() => opts.terminalOf('release'))
const relName = (k) => opts.nameOf('release', k)
// 「已回滚」为发版特有状态，不进配置但参与筛选与展示
const relFilterKeys = computed(() => (relKeys.value.includes('rolled') ? relKeys.value : [...relKeys.value, 'rolled']))
// 统计条计数：优先取后端按配置生成的 counts，回退旧的固定 key
const statCount = (k) => stats.value?.counts?.[k] ?? stats.value?.[k] ?? 0

// ===== 筛选（状态下拉 / 版本号搜索 / 负责人下拉，全部下推服务端）=====
const fStatus = ref('all')
const fVersion = ref('')
const fOwner = ref('')
const filterParams = computed(() => ({
  q: fVersion.value.trim(),
  status: fStatus.value === 'all' ? '' : fStatus.value,
  owner: fOwner.value
}))

// ===== 分页（服务端分页，paged 即当前页）=====
const page = ref(1)
const pageSize = ref(10)
const paged = computed(() => rows.value)
watch([fStatus, fVersion, fOwner], () => {
  // 回第一页由 page 监听取数，已经是第一页时直接取数，避免重复请求
  if (page.value === 1) fetchData().catch(() => {})
  else page.value = 1
})
watch([page, pageSize], () => fetchData().catch(() => {}))

// ===== 详情 / 状态流转（流转按钮 = 配置顺序）=====
const detailId = ref(null)
const detail = ref(null) // 详情按 id 取（含发版说明正文），点行先用列表行渲染
const confirmDel = ref(false)
let detailSeq = 0

function openDetail(r) {
  detail.value = r // 先用当前行渲染弹窗，说明/关联/邮件异步回填
  linked.value = { reqs: [], defects: [] } // 清掉上一个计划的关联，避免闪现错数据
  mail.value = { to: [], subject: '', body: '' }
  notesDraft.value = ''
  detailId.value = r.id
}

async function fetchDetail() {
  const seq = ++detailSeq
  const id = detailId.value
  if (!id) {
    detail.value = null
    linked.value = { reqs: [], defects: [] }
    mail.value = { to: [], subject: '', body: '' }
    return
  }
  const [full, lk, ml] = await Promise.all([
    api.getRelease(id),
    api.getReleaseLinked(id),
    api.getReleaseMail(id)
  ])
  if (seq !== detailSeq || detailId.value !== id) return
  detail.value = full
  linked.value = lk
  mail.value = ml
  notesDraft.value = full.notes || ''
}

// 列表与详情一起刷新
async function reloadAll() {
  await Promise.all([fetchData(), fetchDetail()])
}

async function setStatus(s) {
  if (!detail.value || detail.value.status === s) return
  await api.updateRelease(detailId.value, { status: s })
  await reloadAll()
}
async function doDelete() {
  const id = detailId.value
  confirmDel.value = false
  detailId.value = null
  await api.deleteRelease(id)
  ui.notifyRefresh()
}

// ===== 筛选/详情同步 URL =====
function syncQuery() {
  const q = {}
  if (fStatus.value !== 'all') q.status = fStatus.value
  if (fOwner.value) q.owner = fOwner.value
  if (fVersion.value.trim()) q.q = fVersion.value.trim()
  if (detailId.value) q.focus = detailId.value
  router.replace({ path: route.path, query: q }).catch(() => {})
}
watch([fStatus, fVersion, fOwner, detailId], syncQuery)
watch(
  () => route.query,
  (q) => { if (q.focus && q.focus !== detailId.value) detailId.value = q.focus },
  { deep: true }
)

// ===== 编辑（叠在详情弹窗之上）=====
const editing = ref(false)
const editForm = reactive({ version: '', name: '', date: '', owner: '', channel: '生产', risk: '低' })
const editErr = ref('')
function startEdit() {
  const d = detail.value
  if (!d) return
  Object.assign(editForm, { version: d.version, name: d.name, date: d.date, owner: d.owner, channel: d.channel, risk: d.risk })
  editErr.value = ''
  editing.value = true
}
async function saveEdit() {
  if (!editForm.version.trim() || !editForm.name.trim() || !editForm.date) {
    editErr.value = '版本号、计划名称、计划发版日期均为必填'
    return
  }
  await api.updateRelease(detailId.value, { ...editForm })
  editing.value = false
  await reloadAll()
}

// ===== 关联发布内容（需求 / 缺陷，可增删）=====
const linkedReqs = computed(() => linked.value.reqs)
const linkedDefects = computed(() => linked.value.defects)
async function removeReq(id) {
  await api.updateRelease(detailId.value, { reqIds: detail.value.reqIds.filter(x => x !== id) })
  await reloadAll()
}
async function removeDefect(id) {
  await api.updateRelease(detailId.value, { defectIds: detail.value.defectIds.filter(x => x !== id) })
  await reloadAll()
}

// 通用多选添加弹窗（req=需求 / defect=缺陷）：候选走服务端搜索 + 分页，已关联项置灰
const relPicker = ref('') // '' | 'req' | 'defect'
const relSel = ref([])
const relSearch = ref('')
const relPage = ref(1)
const relPageSize = ref(10)
const relRows = ref([])
const relTotal = ref(0)
let relSeq = 0
async function fetchRelOptions() {
  if (!relPicker.value || !detailId.value) return
  const seq = ++relSeq
  const id = detailId.value
  const q = relSearch.value.trim()
  const r = relPicker.value === 'req'
    ? await api.getReleaseReqOptions(id, q, relPage.value, relPageSize.value)
    : await api.getReleaseDefectOptions(id, q, relPage.value, relPageSize.value)
  if (seq !== relSeq || relPicker.value === '') return
  relRows.value = r.list || []
  relTotal.value = r.total || 0
}
function openRelPicker(type) {
  relPicker.value = type // 先开弹窗，候选异步回填
  relSel.value = []
  relSearch.value = ''
  relPage.value = 1
  relRows.value = []
  relTotal.value = 0
  fetchRelOptions().catch(() => {})
}
watch(relSearch, () => {
  if (relPage.value === 1) fetchRelOptions().catch(() => {})
  else relPage.value = 1
})
watch([relPage, relPageSize], () => fetchRelOptions().catch(() => {}))
function toggleRelSel(id) {
  const i = relSel.value.indexOf(id)
  if (i >= 0) relSel.value.splice(i, 1)
  else relSel.value.push(id)
}
async function confirmRelSel() {
  const field = relPicker.value === 'req' ? 'reqIds' : 'defectIds'
  await api.updateRelease(detailId.value, { [field]: [...(detail.value[field] || []), ...relSel.value] })
  relPicker.value = ''
  await reloadAll()
}

// ===== 发版说明（富文本 + 从关联内容生成草稿）=====
const notesDraft = ref('')
const notesSaved = ref(false)
const notesDirty = computed(() => !!detail.value && notesDraft.value !== (detail.value.notes || ''))
const confirmLeave = ref(false)
function closeDetail() {
  confirmLeave.value = false
  detailId.value = null
  editing.value = false
  notesDraft.value = ''
}
function requestClose() {
  if (notesDirty.value) confirmLeave.value = true
  else closeDetail()
}
// 关联内容跳转到对应详情
function goLinked(kind, id) {
  router.push({ path: kind === 'req' ? '/requirements' : '/defects', query: { focus: id } })
}
watch(detailId, async (id) => {
  notesSaved.value = false
  relPicker.value = ''
  if (id) await fetchDetail()
  else {
    linked.value = { reqs: [], defects: [] }
    mail.value = { to: [], subject: '', body: '' }
    detail.value = null
    notesDraft.value = ''
  }
})
const esc = s => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
function genDraft() {
  const d = detail.value
  if (!d) return
  const sec = (t, rows) => `<h3>${t}</h3>` + (rows.length ? `<ul>${rows.join('')}</ul>` : '<p>无</p>')
  const reqRows = linkedReqs.value.map(r =>
    `<li>${esc(r.id)} ${esc(r.title)}（负责人 ${esc(r.ownerName || '—')}）</li>`)
  const defRows = linkedDefects.value.map(x =>
    `<li>${esc(x.id)} ${esc(x.title)}（处理人 ${esc(x.ownerName || '—')}）</li>`)
  notesDraft.value =
    `<p>版本 ${esc(d.version)}「${esc(d.name)}」计划于 ${esc(d.date)} 发布，发布渠道：${esc(d.channel)}。</p>` +
    sec('一、新增功能', reqRows) +
    sec('二、问题修复', defRows) +
    `<h3>三、注意事项</h3><ul>` +
    `<li>风险等级：${esc(d.risk)}，发布前请完成回归验证并准备好回滚方案。</li>` +
    `<li>发布完成后请相关同学跟进线上验证。</li></ul>`
  notesSaved.value = false
}
async function saveNotes() {
  await api.updateRelease(detailId.value, { notes: notesDraft.value })
  await fetchDetail()
  notesSaved.value = true
  setTimeout(() => { notesSaved.value = false }, 1600)
}

// ===== 通知邮件（内容由后端生成，只复制不发送）=====
const EMPTY_MAIL = { to: [], subject: '', body: '' }
const copied = ref(false)
const hasLinked = computed(() => linkedReqs.value.length > 0 || linkedDefects.value.length > 0)
const mailToText = computed(() => {
  const to = mail.value?.to || []
  if (to.length) return to.join('、')
  return hasLinked.value ? '已关联内容，但当前账号无成员邮箱读取权限' : '暂无（请先关联需求或缺陷）'
})
async function copyMail() {
  const m = mail.value || EMPTY_MAIL
  const text = `收件人：${(m.to || []).join('、')}\n主题：${m.subject || ''}\n\n${m.body || ''}`
  try {
    await navigator.clipboard.writeText(text)
  } catch (e) {
    // 剪贴板 API 不可用时回退到 textarea + execCommand
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
  }
  copied.value = true
  setTimeout(() => { copied.value = false }, 1600)
}

// 风险等级样式映射（避免中文 class 名）
const riskCls = r => ({ 低: 'rl', 中: 'rm', 高: 'rh' }[r] || 'rl')
</script>

<template>
  <LoadingMask v-if="loading" />
  <template v-else-if="stats">
    <!-- 页头 -->
    <div class="page-head rise d1">
      <div>
        <h1>发版计划</h1>
        <div class="page-meta">规划版本发布、关联发布内容并生成发版通知</div>
      </div>
      <div class="page-right">
        <PageCreateButton type="release" />
      </div>
    </div>

    <!-- 统计条（计数与文案随发版工作流配置） -->
    <div class="stat-strip rise d2">
      <div class="stat">
        <div class="v">{{ statCount(relKeys[0]) }}</div>
        <div class="k">{{ relName(relKeys[0]) }}</div>
        <div v-if="relKeys[1]" class="sub">含 {{ relName(relKeys[1]) }} {{ statCount(relKeys[1]) }}</div>
      </div>
      <div v-if="relKeys[2]" class="stat warn">
        <div class="v">{{ statCount(relKeys[2]) }}</div>
        <div class="k">{{ relName(relKeys[2]) }}</div>
        <div class="sub">已具备发布条件</div>
      </div>
      <div class="stat ok">
        <div class="v">{{ statCount(relTerminal) }}</div>
        <div class="k">{{ relName(relTerminal) }}</div>
        <div class="sub">累计 {{ stats.total }} 个计划 · 回滚 {{ statCount('rolled') }}</div>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar rise d3">
      <div class="filter-item">
        <span class="filter-label">状态</span>
        <select class="filter-select" v-model="fStatus">
          <option value="all">全部</option>
          <option v-for="k in relFilterKeys" :key="k" :value="k">{{ relName(k) }}</option>
        </select>
      </div>
      <div class="filter-item">
        <span class="filter-label">版本号</span>
        <input class="field-input f-search" v-model="fVersion" placeholder="搜索版本号 / 计划名称…">
      </div>
      <div class="filter-item">
        <span class="filter-label">负责人</span>
        <select class="filter-select" v-model="fOwner">
          <option value="">全部</option>
          <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }}</option>
        </select>
      </div>
    </div>

    <!-- 发版计划列表 -->
    <div class="card table-card rise d4">
      <div class="table-head">
        <div class="col-ver">版本号</div>
        <div class="col-name">计划名称</div>
        <div class="col-date">计划发版日期</div>
        <div class="col-status">状态</div>
        <div class="col-links">关联内容</div>
        <div class="col-owner">负责人</div>
        <div class="col-ops">操作</div>
      </div>

      <div v-for="r in paged" :key="r.id" class="table-row clickable" @click="openDetail(r)">
        <div class="col-ver ver-cell">{{ r.version }}</div>
        <div class="col-name name-cell">{{ r.name }}<span class="chan">{{ r.channel }}</span></div>
        <div class="col-date date-cell">{{ r.date }}</div>
        <div class="col-status"><span class="status" :class="r.status" :style="opts.styleOf('release', r.status)">{{ relName(r.status) }}</span></div>
        <div class="col-links">
          <span class="lnk" :class="{ none: !r.reqIds.length }">需求 {{ r.reqIds.length }}</span>
          <span class="lnk" :class="{ none: !r.defectIds.length }">缺陷 {{ r.defectIds.length }}</span>
        </div>
        <div class="col-owner">
          <div class="owner-cell">
            <span class="mini-ava ava" :class="mmap[r.owner]?.color">{{ mmap[r.owner]?.short }}</span>{{ mmap[r.owner]?.name || '—' }}
          </div>
        </div>
        <div class="col-ops" @click.stop>
          <button class="op-btn" @click="openDetail(r)">查看</button>
          <button class="op-btn" @click="openDetail(r); startEdit()">编辑</button>
          <button v-if="canManage" class="op-btn danger" @click="openDetail(r); confirmDel = true">删除</button>
        </div>
      </div>

      <div v-if="!rows.length" class="empty">没有符合条件的发版计划</div>
      <!-- 分页（全站统一组件，total 取后端筛选后的总条数） -->
      <AppPager v-else :total="total" :page="page" :page-size="pageSize"
        @update:page="page = $event" @update:pageSize="pageSize = $event" />
    </div>

    <!-- 详情弹窗（760 档，侧栏状态流转 + 主区关联/说明/邮件） -->
    <AppModal v-if="detail" :title="'发版计划 ' + detail.version" width="760" @close="requestClose">
      <div class="dtitle">{{ detail.name }}</div>

      <!-- 关联发布内容 -->
      <div class="dsection">
        <div class="k">关联发布内容</div>
        <div class="rel-sub">
          <span class="t">需求</span><span class="n">{{ linkedReqs.length }} 项</span>
          <button class="ghost-btn add" @click="openRelPicker('req')">+ 添加需求</button>
        </div>
        <div v-for="r in linkedReqs" :key="r.id" class="rel-row clickable" @click="goLinked('req', r.id)">
          <b class="rel-id">{{ r.id }}</b>
          <span class="rel-title" :title="r.title">{{ r.title }}</span>
          <span class="rel-meta">{{ r.status }}</span>
          <span class="rel-meta">{{ r.ownerName || '—' }}</span>
          <button class="rel-x" title="移除关联" @click.stop="removeReq(r.id)">×</button>
        </div>
        <div v-if="!linkedReqs.length" class="rel-none">暂无关联需求</div>

        <div class="rel-sub">
          <span class="t">缺陷</span><span class="n">{{ linkedDefects.length }} 项</span>
          <button class="ghost-btn add" @click="openRelPicker('defect')">+ 添加缺陷</button>
        </div>
        <div v-for="d in linkedDefects" :key="d.id" class="rel-row clickable" @click="goLinked('defect', d.id)">
          <b class="rel-id">{{ d.id }}</b>
          <span class="rel-title" :title="d.title">{{ d.title }}</span>
          <span class="status" :class="d.status" :style="opts.defStyle(d.status)">{{ opts.defName(d.status) }}</span>
          <span class="rel-meta">{{ d.ownerName || '—' }}</span>
          <button class="rel-x" title="移除关联" @click.stop="removeDefect(d.id)">×</button>
        </div>
        <div v-if="!linkedDefects.length" class="rel-none">暂无关联缺陷</div>
      </div>

      <!-- 发版说明 -->
      <div class="dsection">
        <div class="k">发版说明</div>
        <div class="notes-act">
          <button class="ghost-btn" @click="genDraft">从关联内容生成草稿</button>
          <button class="btn" @click="saveNotes">{{ notesSaved ? '已保存 ✓' : '保存说明' }}</button>
        </div>
        <AppEditor v-model="notesDraft" :min-height="200"
          placeholder="点击「从关联内容生成草稿」自动汇总，或手动编写本次发版说明…" />
      </div>

      <!-- 通知邮件 -->
      <div class="dsection">
        <div class="k">通知邮件</div>
        <div class="mail-box">
          <div class="mail-row"><span class="mail-k">收件人</span>
            <span class="mail-v">{{ mailToText }}</span>
          </div>
          <div class="mail-row"><span class="mail-k">主题</span><span class="mail-v">{{ mail.subject }}</span></div>
          <div class="mail-row"><span class="mail-k">正文</span><span class="mail-v pre">{{ mail.body }}</span></div>
          <div class="mail-act">
            <span v-if="copied" class="copied">已复制到剪贴板</span>
            <button class="ghost-btn" @click="copyMail">复制邮件内容</button>
          </div>
        </div>
        <div class="mail-tip">仅生成邮件内容供复制，不会自动发送。</div>
      </div>

      <!-- 操作记录 -->
      <div class="dsection">
        <div class="k">操作记录</div>
        <ActivityTimeline kind="release" :ref-id="detail.id" :fallback="'创建于 ' + (detail.created || '—')" />
      </div>

      <template #rail>
        <div class="flow-bar">
          <button v-for="s in relKeys" :key="s" class="flow-btn" :class="{ on: detail.status === s }"
            @click="setStatus(s)">{{ relName(s) }}</button>
        </div>
        <button v-if="detail.status !== 'rolled'" class="rollback-btn" @click="setStatus('rolled')">回滚</button>
        <div class="rail-row"><div class="rail-k">状态</div><div class="rail-v"><span class="status" :class="detail.status" :style="opts.styleOf('release', detail.status)">{{ relName(detail.status) }}</span></div></div>
        <div class="rail-row"><div class="rail-k">计划发版日期</div><div class="rail-v mono">{{ detail.date }}</div></div>
        <div class="rail-row"><div class="rail-k">发布渠道</div><div class="rail-v">{{ detail.channel }}</div></div>
        <div class="rail-row"><div class="rail-k">风险等级</div><div class="rail-v"><span class="risk" :class="riskCls(detail.risk)">{{ detail.risk }}</span></div></div>
        <div class="rail-row"><div class="rail-k">发版负责人</div><div class="rail-v">{{ mmap[detail.owner]?.name || '—' }} · {{ mmap[detail.owner]?.role || '' }}</div></div>
        <div class="rail-row"><div class="rail-k">关联内容</div><div class="rail-v">需求 {{ detail.reqIds.length }} · 缺陷 {{ detail.defectIds.length }}</div></div>
        <div class="rail-row"><div class="rail-k">创建时间</div><div class="rail-v mono">{{ detail.created }}</div></div>
        <div class="rail-row"><div class="rail-k">更新时间</div><div class="rail-v mono">{{ detail.updated }}</div></div>
      </template>

      <template #footer>
        <button v-if="canManage" class="ghost-btn del-text" @click="confirmDel = true">删除</button>
        <button class="ghost-btn" @click="startEdit">编辑</button>
      </template>
    </AppModal>

    <!-- 编辑弹窗（叠在详情之上） -->
    <AppModal v-if="editing && detail" :title="'编辑发版计划 ' + detail.version" :z-index="1010" @close="editing = false">
      <div class="field-row">
        <div class="field">
          <label class="field-label">版本号<span class="req">*</span></label>
          <input class="field-input" :class="{ invalid: editErr }" v-model="editForm.version" placeholder="如 v2.25.0" @input="editErr = ''">
        </div>
        <div class="field">
          <label class="field-label">计划名称<span class="req">*</span></label>
          <input class="field-input" :class="{ invalid: editErr }" v-model="editForm.name" placeholder="计划名称" @input="editErr = ''">
        </div>
      </div>
      <div v-if="editErr" class="field-error" style="margin-top:-8px;margin-bottom:10px">{{ editErr }}</div>
      <div class="field-row">
        <div class="field">
          <label class="field-label">计划发版日期<span class="req">*</span></label>
          <input type="date" class="field-input" v-model="editForm.date">
        </div>
        <div class="field"><label class="field-label">发版负责人</label>
          <select class="field-select" v-model="editForm.owner">
            <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
          </select>
        </div>
      </div>
      <div class="field-row">
        <div class="field"><label class="field-label">发布渠道</label>
          <select class="field-select" v-model="editForm.channel">
            <option>生产</option><option>灰度</option><option>内测</option>
          </select>
        </div>
        <div class="field"><label class="field-label">风险等级</label>
          <select class="field-select" v-model="editForm.risk">
            <option>低</option><option>中</option><option>高</option>
          </select>
        </div>
      </div>
      <template #footer>
        <button class="ghost-btn" @click="editing = false">取消</button>
        <button class="btn" @click="saveEdit">保存</button>
      </template>
    </AppModal>

    <!-- 删除确认 -->
    <AppConfirm v-if="confirmDel && detail" title="删除发版计划"
      :text="`确定删除发版计划 ${detail.version}「${detail.name}」？删除后不可恢复。`"
      @confirm="doDelete" @close="confirmDel = false" />

    <!-- 发版说明有未保存修改时，关闭前确认 -->
    <AppConfirm v-if="confirmLeave" title="放弃未保存的修改"
      text="发版说明还有未保存的修改，关闭后这些内容会丢失。确定关闭吗？"
      confirm-text="放弃并关闭" @confirm="closeDetail" @close="confirmLeave = false" />

    <!-- 添加关联内容弹窗（多选，确定后写回） -->
    <Teleport to="body">
      <div v-if="relPicker" class="picker-overlay">
        <div class="picker-dialog">
          <div class="picker-head">
            <span>{{ relPicker === 'req' ? '添加关联需求' : '添加关联缺陷' }}<span class="head-sub">可多选</span></span>
            <button class="picker-close" title="关闭" @click="relPicker = ''">
              <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>
            </button>
          </div>
          <input class="field-input picker-search" v-model="relSearch" placeholder="搜索编号或标题…" autofocus>
          <div class="picker-list">
            <div v-for="x in relRows" :key="x.id" class="picker-item multi"
              :class="{ on: relSel.includes(x.id), dis: x.linked }" @click="!x.linked && toggleRelSel(x.id)">
              <b>{{ x.id }}</b> {{ x.title }}<span v-if="x.linked" class="pick-linked">已关联</span>
              <svg class="tick" viewBox="0 0 24 24"><path d="m5 13 4 4L19 7"/></svg>
            </div>
            <div v-if="!relRows.length" class="picker-empty">无匹配条目</div>
          </div>
          <AppPager :total="relTotal" :page="relPage" :page-size="relPageSize"
            @update:page="relPage = $event" @update:pageSize="relPageSize = $event" />
          <div class="picker-act">
            <span class="act-n">已选 {{ relSel.length }} 项</span>
            <button class="ghost-btn" @click="relPicker = ''">取消</button>
            <button class="btn" :disabled="!relSel.length" @click="confirmRelSel">确定</button>
          </div>
        </div>
      </div>
    </Teleport>
  </template>
</template>

<style scoped>
/* ===== 列表 ===== */
.col-ver{width:92px;flex:none}
.col-name{flex:1;min-width:200px}
.col-date{width:110px;flex:none;font-variant-numeric:tabular-nums}
.col-status{width:84px;flex:none;text-align:center}
.col-links{width:150px;flex:none}
.col-owner{width:110px;flex:none}
.col-ops{width:150px;flex:none;text-align:right}

.ver-cell{font-size:13px;font-weight:700;color:var(--t1);
  font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-variant-numeric:tabular-nums}
.name-cell{font-size:14px;color:var(--t1)}
.chan{margin-left:8px;font-size:12px;color:var(--t3);background:var(--track);
  padding:1px 6px;border-radius:5px;font-weight:400}
.date-cell{font-size:13px;color:var(--t3);font-variant-numeric:tabular-nums}
.lnk{font-size:12px;color:var(--primary);margin-right:8px}
.lnk.none{color:var(--t3)}
.owner-cell{display:flex;align-items:center;gap:6px;font-size:13px;color:var(--t2)}
.op-btn{border:none;background:none;font-size:13px;color:var(--primary);
  cursor:pointer;font-family:inherit;padding:0 4px}
.op-btn.danger{color:var(--red)}
.empty{padding:24px;text-align:center;font-size:13px;color:var(--t3)}

/* ===== 筛选 ===== */
.f-search{width:220px;height:30px;font-size:13px}

/* ===== 详情侧栏 ===== */
.rail-row{margin-bottom:13px}
.rail-k{font-size:12px;color:var(--t3)}
.rail-v{font-size:13px;margin-top:3px;line-height:1.6}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px}
.flow-bar{display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-bottom:8px}
.flow-btn{height:30px;padding:0 10px;font-size:12px;border:1px solid var(--border);border-radius:7px;
  background:var(--card);color:var(--t2);cursor:pointer;font-family:inherit;
  transition:border-color .15s var(--ease),color .15s var(--ease),background .15s var(--ease)}
.flow-btn:hover{border-color:var(--primary-light);color:var(--primary)}
.flow-btn.on{background:var(--grad-primary);border-color:transparent;color:#fff;font-weight:400;box-shadow:var(--shadow-primary)}
.rollback-btn{width:100%;height:30px;margin-bottom:12px;border:1px solid var(--red);border-radius:7px;
  background:var(--card);color:var(--red);font-size:12px;cursor:pointer;font-family:inherit}
.del-text{color:var(--red)}
.risk{display:inline-flex;font-size:12px;font-weight:400;padding:1px 7px;border-radius:5px}
.risk.rl{background:var(--green-bg);color:var(--green)}
.risk.rm{background:var(--amber-bg);color:var(--amber)}
.risk.rh{background:var(--red-bg);color:var(--red)}

/* ===== 关联发布内容 ===== */
.rel-sub{display:flex;align-items:center;gap:8px;margin:8px 0 2px}
.rel-sub .t{font-size:13px;font-weight:700;color:var(--t2)}
.rel-sub .n{font-size:12px;color:var(--t3)}
.rel-sub .add{margin-left:auto;height:26px;padding:0 10px;font-size:12px}
.rel-row{display:flex;align-items:center;gap:10px;padding:7px 0}
.rel-row.clickable{cursor:pointer}
.rel-row.clickable:hover .rel-title{color:var(--primary)}
.rel-row + .rel-row{border-top:1px solid var(--border)}
.rel-id{flex:none;font-size:13px;color:var(--t1);font-weight:700;font-variant-numeric:tabular-nums}
.rel-title{flex:1;min-width:0;font-size:13px;color:var(--t2);overflow:hidden;
  text-overflow:ellipsis;white-space:nowrap}
.rel-meta{flex:none;font-size:12px;color:var(--t3)}
.rel-x{flex:none;width:20px;height:20px;border:none;background:none;padding:0;color:var(--t3);
  font-size:16px;line-height:1;cursor:pointer;font-family:inherit;
  display:flex;align-items:center;justify-content:center}
.rel-none{font-size:13px;color:var(--t3);padding:4px 0}

/* ===== 发版说明 ===== */
.notes-act{display:flex;justify-content:flex-end;gap:8px;margin-bottom:8px}
.notes-act .ghost-btn,.notes-act .btn{height:28px;font-size:12px;padding:0 12px}

/* ===== 通知邮件 ===== */
.mail-box{border:1px solid var(--border);border-radius:8px;padding:12px 14px;background:var(--bg)}
.mail-row{display:flex;gap:10px;align-items:flex-start;margin-bottom:8px;font-size:13px}
.mail-k{flex:none;width:44px;color:var(--t1);font-weight:700}
.mail-v{flex:1;min-width:0;color:var(--t2);line-height:1.7;word-break:break-all}
.mail-v.pre{white-space:pre-wrap;word-break:break-word}
.mail-act{display:flex;align-items:center;justify-content:flex-end;gap:10px;margin-top:4px}
.mail-act .ghost-btn{height:28px;font-size:12px;padding:0 12px}
.copied{font-size:12px;color:var(--green)}
.mail-tip{margin-top:6px;font-size:12px;color:var(--t3)}

/* ===== 添加关联弹窗（沿用全局 picker 结构）===== */
.picker-overlay{position:fixed;z-index:11000;inset:0;background:var(--overlay);
  display:flex;align-items:center;justify-content:center}
.picker-dialog{width:540px;max-height:75vh;overflow:hidden;background:var(--card);border-radius:12px;
  box-shadow:var(--shadow-lg);display:flex;flex-direction:column}
.picker-head{display:flex;align-items:center;gap:8px;font-size:14px;font-weight:700;padding:16px 20px 10px}
.picker-close{margin-left:auto;width:26px;height:26px;flex:none;padding:0;border:none;background:none;border-radius:6px;
  cursor:pointer;display:flex;align-items:center;justify-content:center;color:var(--t3)}
.picker-close svg{width:14px;height:14px;stroke:currentColor;stroke-width:1.8;fill:none;stroke-linecap:round}
.picker-close:hover{background:var(--track);color:var(--t1)}
.head-sub{margin-left:8px;font-size:12px;font-weight:400;color:var(--t3)}
.picker-search{width:calc(100% - 40px);margin:0 20px 10px}
.picker-list{overflow-y:auto;padding:0 0 6px;flex:1;min-height:220px}
.picker-item{padding:10px 20px;font-size:13px;color:var(--t2);cursor:pointer}
.picker-item b{color:var(--t1);margin-right:6px;font-weight:700}
.picker-item:hover{background:var(--track)}
.picker-empty{padding:18px;text-align:center;font-size:13px;color:var(--t3)}
.picker-dialog .pager{padding:12px 20px 12px}
.picker-item.multi{display:flex;align-items:center}
.picker-item.on{background:var(--primary-bg)}
.picker-item.on b{color:var(--primary)}
.tick{display:none;width:16px;height:16px;margin-left:auto;flex:none;
  stroke:var(--primary);stroke-width:2.5;fill:none;stroke-linecap:round;stroke-linejoin:round}
.picker-item.on .tick{display:block}
/* 已关联项：置灰不可再选，右侧标注避免用户以为漏选 */
.picker-item.dis{color:var(--t3);cursor:default}
.picker-item.dis:hover{background:none}
.picker-item.dis b{color:var(--t3)}
.pick-linked{margin-left:8px;font-size:12px;color:var(--t3)}
.picker-act{display:flex;align-items:center;gap:8px;padding:12px 20px 14px;border-top:1px solid var(--border)}
.picker-act .ghost-btn,.picker-act .btn{height:30px}
.picker-act .btn:disabled{opacity:.5;cursor:default}
.act-n{margin-right:auto;font-size:13px;color:var(--t2)}

@media (max-width:1150px){
  .table-head,.table-row{flex-wrap:wrap;gap:8px}
  .col-name{flex-basis:100%}
  .col-ver,.col-date,.col-status,.col-links,.col-owner{width:auto;text-align:left}
}
</style>
