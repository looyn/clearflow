<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { api } from '../api'
import { useUiStore } from '../stores/ui'
import { useMembersStore } from '../stores/members'
import LoadingMask from '../components/LoadingMask.vue'
import AppModal from '../components/AppModal.vue'
import AppPager from '../components/AppPager.vue'
import AppConfirm from '../components/AppConfirm.vue'

const ui = useUiStore()
const members = useMembersStore() // 成员映射与负载矩阵（共享）
const loading = ref(true)
const w = ref(null)
const mmap = computed(() => members.map)
// team=负责人/管理员（全团队数据）；self=普通成员（只统计本人）
const isTeam = computed(() => w.value?.scope !== 'self')

// 明细分页：服务端分页，records 为 PageResult{list,total,page,pageSize}
const page = ref(1)
const pageSize = ref(10)
const total = computed(() => w.value?.records?.total || 0)
const recordRows = computed(() => w.value?.records?.list || [])

let reqSeq = 0
async function fetchData() {
  const seq = ++reqSeq
  await members.load()
  const data = await api.getWorklog({ page: page.value, pageSize: pageSize.value })
  if (seq !== reqSeq) return // 丢弃过期的分页响应
  w.value = data
  actionError.value = ''
  // 页码越界时以后端钳制结果为准（避免停在空页）
  if (data?.records?.page && data.records.page !== page.value) page.value = data.records.page
}
onMounted(async () => { await fetchData(); loading.value = false })
watch(() => ui.refreshTick, fetchData)
watch([page, pageSize], fetchData)

// work_date 可能带 " 00:00:00"，展示统一只取日期
const pad = n => String(n).padStart(2, '0')
const fmtDate = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const dateOnly = d => (d || '').split(' ')[0]
const parseDate = s => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d) }
const TODAY = fmtDate(new Date())
const mondayOf = (d) => {
  const x = new Date(d)
  x.setDate(x.getDate() - ((x.getDay() + 6) % 7))
  return fmtDate(x)
}
const KIND_LABEL = { task: '任务', defect: '缺陷', other: '其他' }
const kindLabel = k => KIND_LABEL[k] || '其他'

// ===== 导出 CSV（后端生成全量明细）=====
const exporting = ref(false)
async function exportCsv() {
  if (exporting.value) return
  exporting.value = true
  try {
    const blob = await api.exportWorklog()
    const url = URL.createObjectURL(blob)
    const a = Object.assign(document.createElement('a'), {
      href: url, download: `worklog-${w.value?.iter || 'all'}.csv`
    })
    a.click()
    URL.revokeObjectURL(url)
  } finally {
    exporting.value = false
  }
}

// ===== 关联对象选择器（任务 / 缺陷，复用各自列表接口的服务端搜索 + 分页）=====
const refPicker = ref('') // 当前打开的选择器：task / defect
const refTarget = ref('log') // 选中后回写到哪个表单：log / week
const refSearch = ref('')
const refPage = ref(1)
const refPageSize = ref(10)
const refRows = ref([])
const refTotal = ref(0)
const refLoading = ref(false)
const refError = ref('')
const refTick = ref(0) // 打开弹窗时自增，保证即使搜索/页码没变化也重新取数（同一 tick 内合并成一次请求）
let refSeq = 0

async function fetchRefOptions() {
  const seq = ++refSeq
  refLoading.value = true
  refError.value = ''
  try {
    // 直接复用任务看板 / 缺陷追踪列表的分页接口（同样是 SQL 侧搜索 + 分页）
    const q = refSearch.value.trim()
    const r = refPicker.value === 'defect'
      ? await api.getDefectsPage({ q }, refPage.value, refPageSize.value)
      : await api.getTasks({ q }, refPage.value, refPageSize.value)
    if (seq !== refSeq || !refPicker.value) return // 丢弃过期响应 / 已关闭弹窗
    refRows.value = r?.list || []
    refTotal.value = r?.total || 0
  } catch (e) {
    if (seq !== refSeq) return
    refRows.value = []
    refTotal.value = 0
    refError.value = '关联对象加载失败：' + (e?.message || '请稍后重试')
  } finally {
    if (seq === refSeq) refLoading.value = false
  }
}
function openRefPicker(kind, target) {
  refTarget.value = target
  refPicker.value = kind
  refSearch.value = ''
  refPage.value = 1
  refRows.value = []
  refTotal.value = 0
  refTick.value++ // 先开弹窗，候选异步回填
}
watch([refTick, refSearch, refPage, refPageSize], ([, search, page], prev) => {
  if (!refPicker.value) return
  // 搜索词变化回到第一页（重新入队后本批内取第一页，只发一次请求）
  if (prev && search !== prev[1] && page !== 1) {
    refPage.value = 1
    return
  }
  fetchRefOptions()
})

function pickRef(o) {
  const label = o ? `${o.id} ${o.title}` : ''
  if (refTarget.value === 'week') {
    weekForm.refId = o ? o.id : ''
    weekRefLabel.value = label
    weekError.value = ''
  } else {
    logForm.refId = o ? o.id : ''
    logRefLabel.value = label
    clearErr('refId')
  }
  refPicker.value = ''
}

// ===== 登记 / 编辑弹窗 =====
const showLog = ref(false)
const editingId = ref('')
const submitting = ref(false)
const formError = ref('')
const logForm = reactive({ date: TODAY, kind: 'task', refId: '', customText: '', hours: null, note: '' })
const logRefLabel = ref('') // 已选关联对象回显（编号 + 标题）
const logErrors = reactive({})
const clearErr = k => { delete logErrors[k] }

// 工时只能登记本人的记录（后端强制 who=登录人），故表单无成员选择
function resetLog() {
  Object.assign(logForm, { date: TODAY, kind: 'task', refId: '', customText: '', hours: null, note: '' })
  logRefLabel.value = ''
  Object.keys(logErrors).forEach(k => delete logErrors[k])
  formError.value = ''
}

function openLog() {
  resetLog()
  editingId.value = ''
  showLog.value = true
}

// 切换关联类型：已选关联对象作废，避免类型与 refId 不匹配
function changeLogKind() {
  logForm.refId = ''
  logRefLabel.value = ''
  logForm.customText = ''
  clearErr('refId')
  clearErr('customText')
}

function openEdit(r) {
  resetLog()
  editingId.value = r.id
  logForm.date = dateOnly(r.date)
  logForm.kind = (r.kind === 'task' || r.kind === 'defect') ? r.kind : 'other'
  if (logForm.kind === 'other') logForm.customText = r.task || ''
  else {
    logForm.refId = r.refId || ''
    logRefLabel.value = r.task || r.refId || ''
  }
  logForm.hours = r.hours
  logForm.note = r.note || ''
  showLog.value = true
}

// 工时无效输入即时反馈
function touchHours() {
  delete logErrors.hours
  const h = Number(logForm.hours)
  if (logForm.hours !== null && logForm.hours !== '' && !(h >= 0.1 && h <= 24)) logErrors.hours = '工时需在 0.1 ~ 24 之间'
}

function validateLog() {
  Object.keys(logErrors).forEach(k => delete logErrors[k])
  if (!logForm.date) logErrors.date = '请选择日期'
  else if (logForm.date > TODAY) logErrors.date = '不能登记未来日期'
  if (logForm.kind === 'other') {
    if (!logForm.customText.trim()) logErrors.customText = '请填写工时内容'
  } else if (!logForm.refId) {
    logErrors.refId = logForm.kind === 'task' ? '请选择关联任务' : '请选择关联缺陷'
  }
  const h = Number(logForm.hours)
  if (!(h >= 0.1 && h <= 24)) logErrors.hours = '工时必填，范围 0.1 ~ 24'
  return Object.keys(logErrors).length === 0
}

function logPayload() {
  const base = { date: logForm.date, hours: Number(logForm.hours), note: logForm.note }
  if (logForm.kind === 'other') return { ...base, kind: 'other', task: logForm.customText.trim() }
  return { ...base, kind: logForm.kind, refId: logForm.refId }
}

async function submitLog() {
  if (!validateLog()) return
  submitting.value = true
  formError.value = ''
  try {
    if (editingId.value) await api.updateWorklog(editingId.value, logPayload())
    else await api.logWork(logPayload())
    showLog.value = false
    // 写请求由 request() 统一广播刷新，这里不再重复触发
  } catch (e) {
    formError.value = e?.message || '提交失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

// ===== 删除记录 =====
const delTarget = ref(null)
const deleting = ref(false)
const actionError = ref('')
async function removeRecord() {
  if (!delTarget.value || deleting.value) return
  const id = delTarget.value.id
  deleting.value = true
  actionError.value = ''
  try {
    await api.deleteWorklog(id)
    delTarget.value = null
  } catch (e) {
    actionError.value = '删除失败：' + (e?.message || '请稍后重试')
  } finally {
    deleting.value = false
  }
}

// ===== 周填报（周一 ~ 周日，共享关联对象）=====
const WEEK_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const showWeek = ref(false)
const weekAnchor = ref(mondayOf(new Date()))
const weekHours = reactive({})
const weekForm = reactive({ kind: 'task', refId: '', customText: '', note: '' })
const weekRefLabel = ref('')
const weekError = ref('')
const weekSubmitting = ref(false)
const weekDays = computed(() => {
  const start = parseDate(weekAnchor.value)
  return WEEK_NAMES.map((label, i) => {
    const d = new Date(start)
    d.setDate(start.getDate() + i)
    const key = fmtDate(d)
    return { key, label, future: key > TODAY }
  })
})
const weekRange = computed(() => weekDays.value.length ? `${weekDays.value[0].key} ~ ${weekDays.value[6].key}` : '')
const weekTotal = computed(() => weekDays.value.reduce((s, d) => {
  const v = Number(weekHours[d.key])
  return s + (v > 0 ? v : 0)
}, 0))

function resetWeekHours() {
  weekDays.value.forEach(d => { weekHours[d.key] = null })
}
function openWeek() {
  weekAnchor.value = mondayOf(new Date())
  Object.assign(weekForm, { kind: 'task', refId: '', customText: '', note: '' })
  weekRefLabel.value = ''
  resetWeekHours()
  weekError.value = ''
  showWeek.value = true
}
function changeWeekKind() {
  weekForm.refId = ''
  weekRefLabel.value = ''
  weekForm.customText = ''
  weekError.value = ''
}
function moveWeek(delta) {
  const d = parseDate(weekAnchor.value)
  d.setDate(d.getDate() + delta * 7)
  weekAnchor.value = fmtDate(d)
  resetWeekHours()
  weekError.value = ''
}

async function submitWeek() {
  weekError.value = ''
  const filled = weekDays.value
    .filter(d => !d.future && weekHours[d.key] !== null && weekHours[d.key] !== '')
    .map(d => ({ date: d.key, hours: Number(weekHours[d.key]) }))
    .filter(d => d.hours !== 0) // 填 0 视为当天不提交
  if (!filled.length) { weekError.value = '请至少填写一天工时'; return }
  if (filled.some(d => !(d.hours >= 0.1 && d.hours <= 24))) {
    weekError.value = '每日工时需在 0.1 ~ 24 小时之间'
    return
  }
  if (weekForm.kind === 'other') {
    if (!weekForm.customText.trim()) { weekError.value = '请填写工时内容'; return }
  } else if (!weekForm.refId) {
    weekError.value = weekForm.kind === 'task' ? '请选择关联任务' : '请选择关联缺陷'
    return
  }
  weekSubmitting.value = true
  try {
    const body = { note: weekForm.note, days: filled }
    if (weekForm.kind === 'other') {
      body.kind = 'other'
      body.task = weekForm.customText.trim()
    } else {
      body.kind = weekForm.kind
      body.refId = weekForm.refId
    }
    await api.logWorkBatch(body)
    showWeek.value = false
  } catch (e) {
    weekError.value = e?.message || '提交失败，请稍后重试'
  } finally {
    weekSubmitting.value = false
  }
}

// 每日趋势折线：画布宽 = 容器像素宽（v-chart-size 回写），1 单位 = 1px
const CW = ref(700)
const setChartWidth = (px) => { CW.value = px }
const X0 = 40, DH = 200, D_TOP = 20, D_BASE = 170
const daily = computed(() => {
  if (!w.value) return null
  const t = w.value.daily
  const axisMax = Math.max(10, Math.ceil((Math.max(...t.hours) + 2) / 10) * 10)
  const canvasW = Math.max(320, CW.value) // 容器极窄/隐藏时整体兜底，保证坐标不溢出画布
  const x1 = canvasW - X0
  const xs = t.days.map((_, i) => X0 + i * ((x1 - X0) / Math.max(1, t.days.length - 1)))
  const y = v => D_BASE - v * ((D_BASE - D_TOP) / axisMax)
  return { t, xs, x1, canvasW, y, axisMax, pts: t.hours.map((v, i) => `${xs[i]},${y(v)}`).join(' ') }
})

// ===== 成员负载热力日历（近 14 天，服务端聚合，与明细分页无关）=====
const heatDays = computed(() => w.value?.heat?.days || [])
const heat = computed(() => w.value?.heat?.rows || [])
// 格子底色：0=灰 / ≤2h 浅蓝 / ≤4h 中蓝 / >4h 深蓝
const heatCls = v => (v <= 0 ? 'h0' : v <= 2 ? 'h1' : v <= 4 ? 'h2' : 'h3')

// ===== 成员负载对比（个人容量阈值，数据 byMember）=====
const cmp = computed(() => {
  const list = w.value ? w.value.byMember : []
  const max = Math.max(1, ...list.map(r => Math.max(r.hours, r.budget || 1)))
  return { list, max }
})
</script>

<template>
  <LoadingMask v-if="loading" />
  <template v-else-if="w">
    <!-- 页头 -->
    <div class="page-head rise d1">
      <div>
        <h1>{{ isTeam ? '工时统计' : '我的工时' }}</h1>
        <div class="page-meta">{{ w.iter || '近 7 天' }}（{{ w.window.start }} ~ {{ w.window.end }}） · {{ isTeam ? '团队工时投入分析' : '个人工时登记与投入分析' }}</div>
      </div>
      <div class="page-right">
        <button class="ghost-btn" :disabled="exporting" @click="exportCsv">{{ exporting ? '导出中…' : (isTeam ? '导出' : '导出我的') }}</button>
        <button class="ghost-btn" @click="openWeek">周填报</button>
        <button class="btn" @click="openLog">登记工时</button>
      </div>
    </div>

    <!-- 统计条 -->
    <div class="stat-strip rise d2">
      <div v-for="s in w.summary" :key="s.k" class="stat">
        <div class="v">{{ s.v }}</div><div class="k">{{ s.k }}</div><div class="sub">{{ s.sub }}</div>
      </div>
    </div>

    <!-- 成员负载热力日历 -->
    <div class="card rise d3">
      <div class="card-head">
        <span class="card-title">{{ isTeam ? '成员负载热力日历' : '我的工时热力日历' }}</span>
        <span class="card-extra">近 14 天 · 格内为当日工时（h）</span>
      </div>
      <div class="heat">
        <div class="heat-row head">
          <div class="heat-name"></div>
          <div v-for="d in heatDays" :key="d" class="heat-cell" :class="{ today: d === TODAY }">{{ d }}</div>
          <div class="heat-total">合计</div>
        </div>
        <div v-for="r in heat" :key="r.id" class="heat-row">
          <div class="heat-name"><span class="mini-ava ava" :class="mmap[r.id]?.color">{{ mmap[r.id]?.short }}</span>{{ mmap[r.id]?.name || '—' }}</div>
          <div v-for="(v, i) in r.cells" :key="i" class="heat-cell" :class="heatCls(v)"
            :title="(mmap[r.id]?.name || '—') + ' · ' + heatDays[i] + ' · ' + v + 'h'">{{ v }}</div>
          <div class="heat-total">{{ r.total }}h</div>
        </div>
      </div>
      <div class="heat-legend">
        <span><i class="h0"></i>0 小时</span>
        <span><i class="h1"></i>≤ 2h</span>
        <span><i class="h2"></i>≤ 4h</span>
        <span><i class="h3"></i>&gt; 4h</span>
      </div>
    </div>

    <div class="cols-2">
      <!-- 成员负载对比 -->
      <div class="card rise d3">
        <div class="card-head">
          <span class="card-title">{{ isTeam ? '成员负载对比' : '个人负载' }}</span>
          <span class="card-extra">{{ w.iter ? '本迭代' : '近 7 天' }} · 小时</span>
        </div>
        <div v-for="r in cmp.list" :key="r.id" class="cmp-row">
          <div class="cmp-name">{{ r.name }}</div>
          <div class="cmp-track">
            <i class="cmp-fill" :style="{ width: r.hours / cmp.max * 100 + '%' }"></i>
            <span class="cmp-line" :style="{ left: r.budget / cmp.max * 100 + '%' }"></span>
          </div>
          <div class="cmp-val" :class="{ over: r.cls === 'over' }">{{ r.hours }}/{{ r.budget }}h</div>
        </div>
        <div class="load-note">红线为个人容量阈值（小时/迭代），超出时工时数标红</div>
      </div>

      <!-- 类型分布 -->
      <div class="card rise d4">
        <div class="card-head">
          <span class="card-title">工时类型分布</span>
          <span class="card-extra">按工种</span>
        </div>
        <div v-for="r in w.byType" :key="r.k" class="type-row">
          <span class="lbl">{{ r.k }}</span>
          <div class="capa-bar"><i class="capa-fill" :style="{ width: r.pct + '%', background: r.color }"></i></div>
          <span class="val">{{ r.pct }}%</span>
        </div>
        <div class="dchart">
          <svg v-chart-size="setChartWidth" class="chart" :viewBox="'0 0 ' + daily.canvasW + ' ' + DH" v-if="daily">
            <line :x1="X0" :y1="D_TOP" :x2="X0" :y2="D_BASE" stroke="var(--chart-grid)" stroke-width="1"/>
            <line :x1="X0" :y1="D_BASE" :x2="daily.x1" :y2="D_BASE" stroke="var(--chart-grid)" stroke-width="1"/>
            <text x="30" :y="D_TOP + 4" fill="var(--t3)" text-anchor="end">{{ daily.axisMax }}</text>
            <text x="30" :y="D_BASE + 4" fill="var(--t3)" text-anchor="end">0</text>
            <text v-for="(d, i) in daily.t.days" :key="d" :x="daily.xs[i]" :y="DH - 10" fill="var(--t3)" :text-anchor="i === 0 ? 'start' : 'middle'">{{ d }}</text>
            <polyline :points="daily.pts" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linecap="round"/>
            <circle v-for="(v, i) in daily.t.hours" :key="i" :cx="daily.xs[i]" :cy="daily.y(v)" r="3" fill="var(--primary)"/>
          </svg>
          <div class="sub-note">每日登记工时（近 4 天）</div>
        </div>
      </div>
    </div>

    <!-- 工时记录 -->
    <div class="card table-card rise d5">
      <div v-if="actionError" class="action-error">{{ actionError }}</div>
      <div class="table-head">
        <div class="col-id">单号</div>
        <div class="col-who" v-if="isTeam">成员</div>
        <div class="col-date">日期</div>
        <div class="col-kind">类型</div>
        <div class="col-task">关联对象</div>
        <div class="col-h">工时</div>
        <div class="col-note">备注</div>
        <div class="col-act">操作</div>
      </div>
      <div v-for="r in recordRows" :key="r.id" class="table-row">
        <div class="col-id wid">{{ r.id }}</div>
        <div class="col-who" v-if="isTeam"><div class="owner-cell"><span class="mini-ava ava" :class="mmap[r.who]?.color">{{ mmap[r.who]?.short }}</span>{{ mmap[r.who]?.name || '—' }}</div></div>
        <div class="col-date">{{ dateOnly(r.date) }}</div>
        <div class="col-kind"><span class="kind-chip" :class="'k-' + (r.kind || 'other')">{{ kindLabel(r.kind) }}</span></div>
        <div class="col-task">{{ r.task }}</div>
        <div class="col-h">{{ r.hours }} h</div>
        <div class="col-note">{{ r.note }}</div>
        <div class="col-act">
          <button class="row-link" @click="openEdit(r)">编辑</button>
          <button class="row-link danger" @click="delTarget = r">删除</button>
        </div>
      </div>
      <div v-if="!recordRows.length" class="empty">暂无工时记录</div>
      <!-- 分页（全站统一组件，服务端分页） -->
      <AppPager :total="total" :page="page" :page-size="pageSize"
        @update:page="page = $event" @update:pageSize="pageSize = $event" />
    </div>

    <!-- 登记 / 编辑工时弹窗 -->
    <AppModal v-if="showLog" :title="editingId ? '编辑工时 ' + editingId : '登记工时'"
      sub="工时只能登记本人；任务/缺陷的已投入会同步更新" @close="showLog = false">
      <div class="field">
        <label class="field-label">日期<span class="req">*</span></label>
        <input class="field-input" :class="{ invalid: logErrors.date }" type="date" :max="TODAY"
          v-model="logForm.date" @input="clearErr('date')">
        <div v-if="logErrors.date" class="field-error">{{ logErrors.date }}</div>
      </div>
      <div class="field">
        <label class="field-label">关联类型</label>
        <select class="field-select" v-model="logForm.kind" @change="changeLogKind">
          <option value="task">任务</option>
          <option value="defect">缺陷</option>
          <option value="other">其他（手动填写）</option>
        </select>
      </div>
      <div class="field" v-if="logForm.kind !== 'other'">
        <label class="field-label">{{ logForm.kind === 'task' ? '关联任务' : '关联缺陷' }}<span class="req">*</span></label>
        <div class="picker-input-wrap">
          <input class="field-input picker-input" :class="{ invalid: logErrors.refId }" readonly
            :value="logRefLabel" :placeholder="logForm.kind === 'task' ? '搜索选择任务…' : '搜索选择缺陷…'"
            @click="openRefPicker(logForm.kind, 'log')">
          <button class="picker-search-btn" :title="logForm.kind === 'task' ? '搜索关联任务' : '搜索关联缺陷'"
            @click="openRefPicker(logForm.kind, 'log')">
            <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
          </button>
        </div>
        <div v-if="logErrors.refId" class="field-error">{{ logErrors.refId }}</div>
      </div>
      <div class="field" v-else>
        <label class="field-label">工时内容<span class="req">*</span></label>
        <input class="field-input" :class="{ invalid: logErrors.customText }" v-model="logForm.customText"
          placeholder="如：需求评审、线上巡检、环境维护" @input="clearErr('customText')">
        <div v-if="logErrors.customText" class="field-error">{{ logErrors.customText }}</div>
      </div>
      <div class="field">
        <label class="field-label">工时（小时）<span class="req">*</span></label>
        <input class="field-input" :class="{ invalid: logErrors.hours }" type="number" step="0.5" min="0.1" max="24"
          v-model.number="logForm.hours" placeholder="如 6.5" @input="touchHours">
        <div v-if="logErrors.hours" class="field-error">{{ logErrors.hours }}</div>
      </div>
      <div class="field"><label class="field-label">备注</label>
        <textarea class="field-textarea" v-model="logForm.note" placeholder="补充说明（可不填）"></textarea>
      </div>
      <div v-if="formError" class="form-error">{{ formError }}</div>
      <template #footer>
        <button class="ghost-btn" @click="showLog = false">取消</button>
        <button class="btn" :disabled="submitting" @click="submitLog">{{ submitting ? '提交中…' : (editingId ? '保存' : '提交') }}</button>
      </template>
    </AppModal>

    <!-- 周填报弹窗 -->
    <AppModal v-if="showWeek" title="周填报工时" sub="同一关联对象，按天填写；仅提交已填写的日期" @close="showWeek = false">
      <div class="week-nav">
        <button class="ghost-btn" @click="moveWeek(-1)">上一周</button>
        <div class="week-range">{{ weekRange }}</div>
        <button class="ghost-btn" :disabled="weekAnchor >= mondayOf(new Date())" @click="moveWeek(1)">下一周</button>
      </div>
      <div class="field">
        <label class="field-label">关联类型</label>
        <select class="field-select" v-model="weekForm.kind" @change="changeWeekKind">
          <option value="task">任务</option>
          <option value="defect">缺陷</option>
          <option value="other">其他（手动填写）</option>
        </select>
      </div>
      <div class="field" v-if="weekForm.kind !== 'other'">
        <label class="field-label">{{ weekForm.kind === 'task' ? '关联任务' : '关联缺陷' }}<span class="req">*</span></label>
        <div class="picker-input-wrap">
          <input class="field-input picker-input" readonly
            :value="weekRefLabel" :placeholder="weekForm.kind === 'task' ? '搜索选择任务…' : '搜索选择缺陷…'"
            @click="openRefPicker(weekForm.kind, 'week')">
          <button class="picker-search-btn" :title="weekForm.kind === 'task' ? '搜索关联任务' : '搜索关联缺陷'"
            @click="openRefPicker(weekForm.kind, 'week')">
            <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
          </button>
        </div>
      </div>
      <div class="field" v-else>
        <label class="field-label">工时内容<span class="req">*</span></label>
        <input class="field-input" v-model="weekForm.customText"
          placeholder="如：需求评审、线上巡检、环境维护" @input="weekError = ''">
      </div>
      <div class="field">
        <label class="field-label">每日工时</label>
        <div class="week-grid">
          <div v-for="d in weekDays" :key="d.key" class="week-row" :class="{ off: d.future }">
            <div class="week-day"><span class="week-name">{{ d.label }}</span><span class="week-date">{{ d.key.slice(5) }}</span></div>
            <input class="field-input week-input" type="number" step="0.5" min="0.1" max="24"
              :disabled="d.future" v-model.number="weekHours[d.key]" :placeholder="d.future ? '未到' : '0'">
            <span class="week-unit">{{ d.future ? '' : 'h' }}</span>
          </div>
        </div>
      </div>
      <div class="field"><label class="field-label">备注</label>
        <textarea class="field-textarea" v-model="weekForm.note" placeholder="补充说明（可不填，应用到本周所有记录）"></textarea>
      </div>
      <div v-if="weekError" class="form-error">{{ weekError }}</div>
      <div class="week-total">本周合计 <b>{{ weekTotal }}</b> h</div>
      <template #footer>
        <button class="ghost-btn" @click="showWeek = false">取消</button>
        <button class="btn" :disabled="weekSubmitting" @click="submitWeek">{{ weekSubmitting ? '提交中…' : '提交' }}</button>
      </template>
    </AppModal>

    <!-- 关联对象搜索弹窗（任务 / 缺陷，服务端搜索 + 分页） -->
    <Teleport to="body">
      <div v-if="refPicker" class="picker-overlay">
        <div class="picker-dialog">
          <div class="picker-head">
            <span>{{ refPicker === 'task' ? '选择关联任务' : '选择关联缺陷' }}</span>
            <button class="picker-close" title="关闭" @click="refPicker = ''">
              <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>
            </button>
          </div>
          <input class="field-input picker-search" v-model="refSearch"
            :placeholder="refPicker === 'task' ? '搜索任务编号或标题…' : '搜索缺陷编号或标题…'" autofocus>
          <div class="picker-list">
            <div v-for="o in refRows" :key="o.id" class="picker-item" @click="pickRef(o)">
              <b>{{ o.id }}</b> {{ o.title }}
            </div>
            <div v-if="!refRows.length" class="picker-empty">{{ refLoading ? '加载中…' : (refError || '无匹配条目') }}</div>
          </div>
          <AppPager :total="refTotal" :page="refPage" :page-size="refPageSize"
            @update:page="refPage = $event" @update:pageSize="refPageSize = $event" />
        </div>
      </div>
    </Teleport>

    <!-- 删除确认 -->
    <AppConfirm v-if="delTarget" title="删除工时记录"
      :text="`确定删除 ${delTarget.id}（${delTarget.hours} h）？关联对象的已投入工时将同步回退。`"
      @close="delTarget = null" @confirm="removeRecord" />
  </template>
</template>

<style scoped>
.cols-2{display:grid;grid-template-columns:1.3fr 1fr;gap:16px;margin-bottom:16px}
.load-note{margin-top:12px;font-size:12px;color:var(--t3);border-top:1px solid var(--border);padding-top:12px}

/* 成员负载热力日历 */
.heat{overflow-x:auto}
.heat-row{display:grid;grid-template-columns:112px repeat(14,1fr) 58px;gap:4px;align-items:center;margin-bottom:4px;min-width:740px}
.heat-name{display:flex;align-items:center;gap:6px;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.heat-cell{height:26px;border-radius:5px;display:flex;align-items:center;justify-content:center;
  font-size:12px;font-variant-numeric:tabular-nums}
.heat-row.head .heat-cell{height:auto;background:none;color:var(--t3);font-size:12px}
.heat-row.head .heat-cell.today{color:var(--red);font-weight:700}
.heat-total{text-align:right;font-size:12px;color:var(--t2);font-variant-numeric:tabular-nums}
.heat-row.head .heat-total{font-size:12px;color:var(--t3)}
.h0{background:var(--track);color:var(--t3)}
.h1{background:var(--primary-bg);color:var(--primary)}
.h2{background:var(--primary-light);color:var(--on-primary-light)}
.h3{background:var(--grad-primary);color:#fff}
.heat-legend{display:flex;gap:16px;margin-top:12px;font-size:12px;color:var(--t2)}
.heat-legend i{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:5px;vertical-align:-2px}

/* 成员负载对比 */
.cmp-row{display:grid;grid-template-columns:76px 1fr 92px;gap:12px;align-items:center;padding:9px 0}
.cmp-name{font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cmp-track{position:relative;height:10px;border-radius:5px;background:var(--track);overflow:hidden}
.cmp-fill{display:block;height:100%;border-radius:5px;background:var(--grad-primary);animation:grow 1.1s ease-out both}
.cmp-line{position:absolute;top:0;bottom:0;width:2px;background:var(--red)}
.cmp-val{font-size:13px;color:var(--t2);text-align:right;font-variant-numeric:tabular-nums}
.cmp-val.over{color:var(--red);font-weight:700}

.type-row{display:flex;align-items:center;gap:12px;padding:7px 0;font-size:13px;color:var(--t2)}
.type-row .lbl{width:76px;flex:none;color:var(--t3)}
.capa-bar{flex:1;height:6px;border-radius:3px;background:var(--track);overflow:hidden}
.capa-fill{display:block;height:100%;border-radius:3px;animation:grow 1s ease-out both}
.type-row .val{width:40px;text-align:right;font-size:12px;font-variant-numeric:tabular-nums;color:var(--t1)}
.dchart{margin-top:14px;border-top:1px solid var(--border);padding-top:14px}
.chart{width:100%;height:auto;display:block}
.sub-note{font-size:12px;color:var(--t3);margin-top:8px}

.col-id{width:72px;flex:none;font-variant-numeric:tabular-nums}
.col-who{width:110px;flex:none}
.col-date{width:70px;flex:none;font-variant-numeric:tabular-nums}
.col-kind{width:58px;flex:none}
.col-task{flex:1;min-width:180px}
.col-h{width:64px;flex:none;text-align:center}
.col-note{width:150px;flex:none}
.col-act{width:88px;flex:none;display:flex;gap:12px;justify-content:flex-end}
.wid{font-size:13px;color:var(--t3)}
.owner-cell{display:flex;align-items:center;gap:6px;font-size:13px;color:var(--t2)}
.col-date{font-size:12px;color:var(--t3)}
.col-task{font-size:13px;color:var(--t1)}
.col-h{font-size:13px;color:var(--t2);font-variant-numeric:tabular-nums}
.col-note{font-size:12px;color:var(--t3)}
.row-link{border:0;background:none;padding:0;font-size:13px;color:var(--primary);cursor:pointer}
.row-link.danger{color:var(--red)}
.row-link:hover{text-decoration:underline}
.kind-chip{display:inline-flex;align-items:center;height:20px;padding:0 7px;border-radius:5px;font-size:12px;white-space:nowrap}
.kind-chip.k-task{background:var(--primary-bg);color:var(--primary)}
.kind-chip.k-defect{background:var(--red-bg);color:var(--red)}
.kind-chip.k-other{background:var(--track);color:var(--t3)}
.action-error{margin-bottom:10px;padding:8px 12px;border-radius:8px;background:var(--red-bg);color:var(--red);font-size:13px}
.form-error{margin-top:14px;padding:8px 12px;border-radius:8px;background:var(--red-bg);color:var(--red);font-size:13px}

/* 周填报 */
.week-nav{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:14px}
.week-range{font-size:13px;color:var(--t2);font-variant-numeric:tabular-nums}
.week-grid{display:flex;flex-direction:column;gap:8px}
.week-row{display:grid;grid-template-columns:92px 1fr 18px;gap:10px;align-items:center}
.week-row.off{opacity:.45}
.week-day{display:flex;gap:8px;align-items:baseline;font-size:13px;color:var(--t2)}
.week-name{color:var(--t1)}
.week-date{font-size:12px;color:var(--t3);font-variant-numeric:tabular-nums}
.week-input{height:34px}
.week-unit{font-size:12px;color:var(--t3)}
.week-total{margin-top:12px;font-size:13px;color:var(--t2);text-align:right}
.week-total b{color:var(--t1);font-variant-numeric:tabular-nums}

/* ===== 关联对象选择器（输入框 + 搜索弹窗）===== */
.picker-input-wrap{display:flex;align-items:stretch;gap:0;width:100%}
.picker-input{flex:1;min-width:0;border-top-right-radius:0 !important;border-bottom-right-radius:0 !important;
  cursor:pointer;height:34px}
.picker-search-btn{flex:none;width:32px;border:1px solid var(--border);border-left:none;border-radius:0 7px 7px 0;
  background:var(--card);color:var(--t3);cursor:pointer;display:flex;align-items:center;justify-content:center;padding:0}
.picker-search-btn svg{width:14px;height:14px;stroke:currentColor;stroke-width:2;fill:none;
  stroke-linecap:round;stroke-linejoin:round}
.picker-search-btn:hover{color:var(--primary)}
.picker-overlay{position:fixed;z-index:11000;inset:0;background:var(--overlay);
  display:flex;align-items:center;justify-content:center}
.picker-dialog{width:540px;max-height:75vh;overflow:hidden;background:var(--card);border-radius:12px;
  box-shadow:var(--shadow-lg);display:flex;flex-direction:column}
.picker-head{display:flex;align-items:center;gap:8px;font-size:14px;font-weight:700;padding:16px 20px 10px}
.picker-close{margin-left:auto;width:26px;height:26px;flex:none;padding:0;border:none;background:none;border-radius:6px;
  cursor:pointer;display:flex;align-items:center;justify-content:center;color:var(--t3)}
.picker-close svg{width:14px;height:14px;stroke:currentColor;stroke-width:1.8;fill:none;stroke-linecap:round}
.picker-close:hover{background:var(--track);color:var(--t1)}
.picker-search{width:calc(100% - 40px);margin:0 20px 10px}
.picker-list{overflow-y:auto;padding:0 0 6px;flex:1;min-height:220px}
.picker-item{padding:10px 20px;font-size:13px;color:var(--t2);cursor:pointer}
.picker-item b{color:var(--t1);margin-right:6px;font-weight:700}
.picker-item:hover{background:var(--track)}
.picker-empty{padding:18px;text-align:center;font-size:13px;color:var(--t3)}
.picker-dialog .pager{padding:12px 20px 12px}

@media (max-width:1150px){ .cols-2{grid-template-columns:1fr} .table-head,.table-row{flex-wrap:wrap;gap:8px} .col-task{flex-basis:100%} }
</style>
