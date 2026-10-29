<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useIterationsStore } from '../stores/iterations'
import { useUiStore } from '../stores/ui'
import { usePermStore } from '../stores/perm'
import { useOptionsStore } from '../stores/options'
import IterCountdown from '../components/IterCountdown.vue'
import LoadingMask from '../components/LoadingMask.vue'

const router = useRouter()
const ui = useUiStore()
const perm = usePermStore()
const opts = useOptionsStore()
const iters = useIterationsStore() // 页头倒计时（进行中迭代）
const loading = ref(true)
const m = ref(null)
const tab = ref('iter')
const moduleReport = ref(null)
const peopleReport = ref(null)
const reportLoading = ref(false)
const reportError = ref('')
// 模块/成员报表含个人归因，仅项目负责人/管理员可见（与后端 @RequireOwner 一致）
const isOwner = computed(() => !!(opts.project && perm.owns(opts.project.id)))
const reportItems = computed(() => ((tab.value === 'modules' ? moduleReport.value : peopleReport.value)?.items) || [])

async function loadReport() {
  if (tab.value === 'iter') return
  if (!isOwner.value) { tab.value = 'iter'; return }
  reportLoading.value = true
  reportError.value = ''
  try {
    const iter = fIter.value === '全部' ? '' : fIter.value
    if (tab.value === 'modules') moduleReport.value = await api.getModuleStats(iter)
    else peopleReport.value = await api.getPeopleStats(iter)
  } catch (e) {
    reportError.value = e.message || '报表加载失败'
  } finally {
    reportLoading.value = false
  }
}
onMounted(async () => { m.value = await api.getMetrics(); iters.load(); loading.value = false })
// 切换项目后重拉（燃尽/流速/吞吐都跟随当前项目）
watch(() => ui.settingsTick, async () => { m.value = await api.getMetrics(); iters.load(); loadReport() })

// ===== 筛选：迭代 / 时间范围 =====
// 迭代选项与顺序来自 metrics.velocity（按 code 升序的真实迭代）
const iterOpts = computed(() => ['全部', ...(m.value ? m.value.velocity : []).map((v) => v.iter)])
const velOrder = computed(() => (m.value ? m.value.velocity : []).map((v) => v.iter))
const fIter = ref('全部')
const fRange = ref('4')
// 切换报表 / 迭代筛选后按需拉取对应报表
watch([tab, fIter], loadReport)

// 流速切片：截取到所选迭代为止的近 N 个迭代（数据不足时展示现有全部）
const velList = computed(() => {
  const list = m.value ? m.value.velocity : []
  let end = list.length
  if (fIter.value !== '全部') {
    const i = list.findIndex(v => v.iter === fIter.value)
    if (i >= 0) end = i + 1
  }
  const s = list.slice(Math.max(0, end - Number(fRange.value)), end)
  return s.length ? s : list
})

// ===== 图表画布 =====
// 画布宽由 v-chart-size 回写的容器像素宽生成，1 用户单位 = 1 CSS px，
// 线宽/点径/字号即设计值，不会被拉伸成粗线大点。
const CW = ref(700)
const setChartWidth = (px) => { CW.value = px }
// 画布宽 = 容器像素宽；容器极窄/隐藏时整体兜底到 320，保证坐标不会溢出画布被裁
const W = computed(() => Math.max(320, CW.value))
const X0 = 40
const X1 = computed(() => W.value - X0)
// 燃尽：画布 220 高，绘图区 20~180，底部留日期
const Y0 = 20, Y1 = 180
const BH = 220
const bd = computed(() => {
  if (!m.value) return null
  const b = m.value.burndown
  const n = b.days.length
  const last = b.actual.length ? b.actual[b.actual.length - 1] : b.total
  // 上界兼取实际剩余：承诺点数与快照剩余目前口径不一致，不兜底折线会冲出画布顶部
  const max = Math.max(1, b.total, ...b.actual)
  const x = i => X0 + i * ((X1.value - X0) / (n - 1))
  const y = v => Y0 + (max - v) * ((Y1 - Y0) / max)
  const actualPts = b.actual.map((v, i) => `${x(i)},${y(v)}`).join(' ')
  const ticks = [1, 0.75, 0.5, 0.25, 0].map((f, i) => ({ v: Math.round(max * f), y: Y0 + (Y1 - Y0) * (i / 4) + 4 }))
  const grid = [1, 2, 3].map(i => Y0 + (Y1 - Y0) * (i / 4))
  // 横轴抽稀：每 ~110px 一个 MM-DD 刻度，窄屏自动抽稀、宽屏自动加密度
  const per = Math.max(3, Math.min(n, Math.floor((X1.value - X0) / 110)))
  const step = Math.max(1, Math.ceil((n - 1) / (per - 1)))
  const idxs = []
  for (let i = 0; i < n - 1; i += step) idxs.push(i)
  if (n - 1 - idxs[idxs.length - 1] > step / 2) idxs.push(n - 1)
  const labels = idxs.map((i, k) => ({ t: b.days[i].slice(5), x: x(i), anchor: k === 0 ? 'start' : (i === n - 1 ? 'end' : 'middle') }))
  // 预测线：按「已烧点数 / 已过天数」的真实速率外推，与横轴交点即预计清零日
  const elapsed = Math.max(1, b.todayIndex)
  const rate = ((b.actual.length ? b.actual[0] : last) - last) / elapsed
  const need = rate > 0 ? Math.ceil(last / rate) : 0
  const zeroIdx = b.todayIndex + need
  const endIdx = rate > 0 ? Math.min(n - 1, zeroIdx) : n - 1
  const forecast = {
    x1: x(b.todayIndex), y1: y(last), x2: x(endIdx),
    y2: rate > 0 ? y(Math.max(0, last - rate * (endIdx - b.todayIndex))) : y(last)
  }
  const remainDays = n - 1 - b.todayIndex
  const idealNow = Math.round(b.total * (1 - b.todayIndex / (n - 1)))
  let forecastNote
  if (!b.actual.length || rate <= 0) {
    forecastNote = '预测说明：迭代开始至今剩余点数未下降（速率为 0），无法预测清零日期'
  } else if (zeroIdx <= n - 1) {
    forecastNote = `预测说明：按当前速率 ${rate.toFixed(1)} 点/天，预计 ${b.days[zeroIdx]} 清零（比迭代末尾提前 ${n - 1 - zeroIdx} 天）`
  } else {
    forecastNote = `预测说明：按当前速率 ${rate.toFixed(1)} 点/天，迭代末尾仍将剩余 ${Math.round(last - rate * remainDays)} 点，需延期或砍范围`
  }
  return {
    x, y, actualPts, ticks, grid, labels, forecast, forecastNote, idealTop: y(b.total),
    todayX: x(b.todayIndex), remainDays, idealNow
  }
})
// 燃尽数据仅进行中迭代：选中其他迭代时展示空态
const bdOk = computed(() => !m.value || fIter.value === '全部' || fIter.value === m.value.burndown.iter)
const bdTitle = computed(() => {
  if (!m.value) return ''
  return `${fIter.value === '全部' ? m.value.burndown.iter : fIter.value} 燃尽图`
})

// ===== 流速图 =====
// 画布 240 高、基线 200；条形宽度按分组宽度自适应，宽屏下不会只留两根细条
const VH = 240, V_BASE = 200
const vel = computed(() => {
  if (!m.value) return null
  const list = velList.value
  const max = Math.max(...list.map(v => Math.max(v.committed, v.completed)))
  const gw = (X1.value - X0) / list.length
  const bw = Math.max(14, Math.min(40, Math.round(gw * 0.18)))
  const y = v => V_BASE - v * ((V_BASE - 40) / max)
  return { list, y, max, gw, bw, gap: Math.round(bw * 0.3) }
})
const barX = (i, j) => {
  const { gw, bw, gap } = vel.value
  return X0 + i * gw + gw / 2 - bw - gap / 2 + j * (bw + gap)
}
const BW = computed(() => vel.value?.bw ?? 26)

// ===== 吞吐趋势（按所选迭代裁剪到对应周次，数据不足时展示全部）=====
// 画布 200 高、基线 170
const TH = 200, T_BASE = 170, TMID = (T_BASE + 20) / 2
const tp = computed(() => {
  if (!m.value) return null
  const all = m.value.throughput
  let end = all.weeks.length
  if (fIter.value !== '全部') {
    const i = velOrder.value.indexOf(fIter.value)
    if (i >= 0) end = Math.min(all.weeks.length, i + 1)
  }
  let idxs = all.weeks.map((_, i) => i).slice(Math.max(0, end - Number(fRange.value)), end)
  if (!idxs.length) idxs = all.weeks.map((_, i) => i)
  const weeks = idxs.map(i => all.weeks[i])
  const closed = idxs.map(i => all.closed[i])
  const max = Math.max(1, ...closed) + 6
  // 单点时居中，避免步长为 0
  const xs = weeks.length > 1
    ? weeks.map((_, i) => X0 + i * ((X1.value - X0) / (weeks.length - 1)))
    : weeks.map(() => (X0 + X1.value) / 2)
  const y = v => T_BASE - v * ((T_BASE - 20) / max)
  return { weeks, closed, xs, y, idxs, max, pts: closed.map((v, i) => `${xs[i]},${y(v)}`).join(' ') }
})

// ===== 数据点下钻：跳转看板并带上迭代参数 =====
const goBoard = iter => router.push('/board', { query: { iter } })

// ===== 导出 CSV：周期指标 + DORA 指标 =====
function exportCsv() {
  const rows = [['分类', '指标', '数值', '趋势', '说明']]
  m.value.cycle.forEach(c => rows.push(['周期指标', c.k, c.v, c.trend.text, c.note]))
  m.value.dora.forEach(d => rows.push(['DORA', d.label, `${d.value} ${d.unit}`, d.trend || '', '']))
  const esc = v => { const s = String(v ?? ''); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s }
  const csv = '\uFEFF' + rows.map(r => r.map(esc).join(',')).join('\n')
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const iter = fIter.value === '全部' ? m.value.burndown.iter : fIter.value
  const a = Object.assign(document.createElement('a'), { href: url, download: `metrics-${iter}.csv` })
  a.click()
  URL.revokeObjectURL(url)
}

// ===== 模块 / 成员报表导出 =====
const esc = v => { const s = String(v ?? ''); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s }
const pctText = v => (v == null ? '—' : `${v}%`)
function downloadCsv(rows, name) {
  const csv = '\uFEFF' + rows.map(r => r.map(esc).join(',')).join('\n')
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const a = Object.assign(document.createElement('a'), { href: url, download: name })
  a.click()
  URL.revokeObjectURL(url)
}
function exportReport() {
  const iter = fIter.value === '全部' ? 'all' : fIter.value
  if (tab.value === 'modules') {
    const rows = [['模块', '负责人', '需求数', '需求点数', '需求完成率%', '任务数', '任务完成率%', '预估工时', '已投入工时', '缺陷数', '未关闭', '未关闭P0P1', '缺陷密度', '生产逃逸数', '逃逸率%', '重开数', '重开率%', 'MTTR(h)', '说明']]
    ;(moduleReport.value?.items || []).forEach(x => rows.push([x.name, x.ownerName, x.reqCount, x.reqPoints, x.reqDoneRate, x.taskCount, x.taskDoneRate, x.taskEstimate, x.taskSpent, x.defectCount, x.openDefects, x.p0p1Open, x.defectDensity ?? '', x.escapeCount, x.escapeRate, x.reopenCount, x.reopenRate, x.mttrHours ?? '', x.description]))
    rows.push([])
    rows.push(['口径', moduleReport.value?.note || ''])
    downloadCsv(rows, `module-quality-${iter}.csv`)
  } else {
    const rows = [['成员', '角色', '待办需求', '进行中任务', '已完成任务', '完成率%', '在办估时(h)', 'WIP', '逾期任务', '负责缺陷', '未关闭缺陷', '已修复缺陷', '平均修复(h)', '引入缺陷(仅复盘)', '报告缺陷', '工时(h)']]
    ;(peopleReport.value?.items || []).forEach(x => rows.push([x.name, x.role, x.openRequirements, x.openTasks, x.doneTasks, x.completionRate, x.openEstimate, x.wip, x.overdue, x.ownerDefects, x.openDefects, x.fixedDefects, x.avgFixHours ?? '', x.introducedDefects, x.reportedDefects, x.worklogHours]))
    rows.push([])
    rows.push(['口径', peopleReport.value?.note || ''])
    downloadCsv(rows, `member-workload-${iter}.csv`)
  }
}
</script>

<template>
  <LoadingMask v-if="loading" />
  <template v-else-if="m">
    <!-- 页头 -->
    <div class="page-head rise d1">
      <div>
        <h1>度量报表</h1>
        <div class="page-meta">迭代交付节奏 · 模块质量 · 成员负载</div>
      </div>
      <div class="page-right">
        <button class="ghost-btn" :disabled="tab !== 'iter' && (reportLoading || !reportItems.length)"
          @click="tab === 'iter' ? exportCsv() : exportReport()">导出</button>
        <IterCountdown />
      </div>
    </div>

    <!-- 报表切换：模块/成员报表仅负责人可见 -->
    <div class="rpt-tabs rise d2">
      <button :class="{ on: tab === 'iter' }" @click="tab = 'iter'">迭代度量</button>
      <template v-if="isOwner">
        <button :class="{ on: tab === 'modules' }" @click="tab = 'modules'">模块质量</button>
        <button :class="{ on: tab === 'people' }" @click="tab = 'people'">成员负载</button>
      </template>
    </div>

    <!-- 筛选行 -->
    <div class="filter-bar rise d2">
      <div class="filter-item">
        <span class="filter-label">迭代</span>
        <select class="filter-select" v-model="fIter">
          <option v-for="it in iterOpts" :key="it" :value="it">{{ it }}</option>
        </select>
      </div>
      <div v-if="tab === 'iter'" class="filter-item">
        <span class="filter-label">时间范围</span>
        <select class="filter-select" v-model="fRange">
          <option value="4">近 4 迭代</option>
          <option value="8">近 8 迭代</option>
        </select>
      </div>
    </div>

    <template v-if="tab === 'iter'">
    <!-- 周期指标 -->
    <div class="stat-strip rise d2">
      <div v-for="(c, i) in m.cycle" :key="c.k" class="stat">
        <div class="v">{{ c.v }}</div>
        <div class="k">{{ c.k }}</div>
        <div class="sub"><span class="trend" :class="c.trend.good ? 'good' : 'bad'">{{ c.trend.text }}</span>　{{ c.note }}</div>
      </div>
    </div>

    <!-- DORA 效能指标 -->
    <div class="stat-strip rise d2">
      <div v-for="d in m.dora" :key="d.label" class="stat">
        <div class="v">{{ d.value }}<span class="unit">{{ d.unit }}</span></div>
        <div class="k">{{ d.label }}</div>
        <div class="sub">
          <span v-if="d.trend" class="trend" :class="d.trend.includes('↑') ? 'good' : 'bad'">{{ d.trend }}</span>
          <template v-else>—</template>
        </div>
      </div>
    </div>

    <!-- 燃尽图 -->
    <div class="card rise d3">
      <div class="card-head">
        <span class="card-title">{{ bdTitle }}</span>
        <span v-if="bdOk" class="card-extra">剩余 {{ bd.remainDays }} 天 · 理想剩余 {{ bd.idealNow }} 点</span>
      </div>
      <template v-if="bdOk">
        <svg v-chart-size="setChartWidth" class="chart" :viewBox="'0 0 ' + W + ' ' + BH">
          <line :x1="X0" :y1="Y0" :x2="X0" :y2="Y1" stroke="var(--chart-grid)" stroke-width="1"/>
          <line :x1="X0" :y1="Y0" :x2="X1" :y2="Y0" stroke="var(--chart-grid)" stroke-width="1"/>
          <line v-for="g in bd.grid" :key="g" :x1="X0" :y1="g" :x2="X1" :y2="g" stroke="var(--chart-grid)" stroke-width="1" stroke-dasharray="3 3"/>
          <line :x1="X0" :y1="Y1" :x2="X1" :y2="Y1" stroke="var(--chart-grid)" stroke-width="1"/>
          <text v-for="(t, i) in bd.ticks" :key="i" x="30" :y="t.y" fill="var(--t3)" text-anchor="end">{{ t.v }}</text>
          <text v-for="l in bd.labels" :key="l.x" :x="l.x" :y="Y1 + 22" fill="var(--t3)" :text-anchor="l.anchor">{{ l.t }}</text>
          <line :x1="X0" :y1="bd.idealTop" :x2="X1" :y2="Y1" stroke="var(--chart-muted)" stroke-width="1.5" stroke-dasharray="6 4"/>
          <polyline :points="bd.actualPts" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linecap="round"/>
          <circle v-for="(v, i) in m.burndown.actual" :key="i" :cx="bd.x(i)" :cy="bd.y(v)" r="3.5" fill="var(--primary)">
            <title>{{ m.burndown.days[i] }}：剩余 {{ v }} 点</title>
          </circle>
          <line :x1="bd.forecast.x1" :y1="bd.forecast.y1" :x2="bd.forecast.x2" :y2="bd.forecast.y2" stroke="var(--amber)" stroke-width="1.5" stroke-dasharray="6 4"/>
          <circle :cx="bd.forecast.x2" :cy="bd.forecast.y2" r="3.5" fill="var(--card)" stroke="var(--amber)" stroke-width="1.5"/>
          <line :x1="bd.todayX" :y1="Y0" :x2="bd.todayX" :y2="Y1" stroke="var(--red)" stroke-width="1" stroke-dasharray="3 3" opacity=".5"/>
          <text :x="bd.todayX" y="14" fill="var(--red)" text-anchor="middle">今天</text>
        </svg>
        <div class="legend">
          <span><i style="background:var(--chart-muted)"></i>理想燃尽</span>
          <span><i style="background:var(--primary)"></i>实际剩余</span>
          <span><i style="background:var(--red)"></i>今日</span>
          <span><i style="background:var(--amber)"></i>预测</span>
        </div>
        <div class="note">{{ m.burndown.note }}</div>
        <div class="note">{{ bd.forecastNote }}</div>
      </template>
      <div v-else class="empty-tip">该迭代暂无燃尽数据</div>
    </div>

    <!-- 流速 -->
    <div class="card rise d4">
      <div class="card-head">
        <span class="card-title">迭代流速（承诺 vs 交付）</span>
        <span class="card-extra">近 {{ vel.list.length }} 个迭代 · 单位：点</span>
      </div>
      <svg v-chart-size="setChartWidth" class="chart" :viewBox="'0 0 ' + W + ' ' + VH">
        <line :x1="X0" :y1="V_BASE" :x2="X1" :y2="V_BASE" stroke="var(--chart-grid)" stroke-width="1"/>
        <g v-for="(v, i) in vel.list" :key="v.iter" style="cursor:pointer" @click="goBoard(v.iter)">
          <title>{{ v.iter }}：承诺 {{ v.committed }} 点 · 交付 {{ v.completed }} 点，点击看板查看</title>
          <rect :x="barX(i, 0)" :y="vel.y(v.committed)" :width="BW" :height="V_BASE - vel.y(v.committed)" rx="4" fill="var(--chart-muted)"/>
          <rect :x="barX(i, 1)" :y="vel.y(v.completed)" :width="BW" :height="V_BASE - vel.y(v.completed)" rx="4" fill="var(--primary)"/>
          <text :x="barX(i, 0) + BW / 2" :y="vel.y(v.committed) - 6" fill="var(--t3)" text-anchor="middle">{{ v.committed }}</text>
          <text :x="barX(i, 1) + BW / 2" :y="vel.y(v.completed) - 6" fill="var(--primary)" text-anchor="middle">{{ v.completed }}</text>
          <text :x="X0 + i * vel.gw + vel.gw / 2" :y="VH - 12" fill="var(--t2)" text-anchor="middle">{{ v.iter }}</text>
        </g>
      </svg>
      <div class="legend">
        <span><i style="background:var(--chart-muted)"></i>承诺规模</span>
        <span><i style="background:var(--primary)"></i>实际交付</span>
      </div>
    </div>

    <!-- 吞吐趋势 -->
    <div class="card rise d5">
      <div class="card-head">
        <span class="card-title">每周完成任务吞吐</span>
        <span class="card-extra">近 {{ tp.weeks.length }} 周</span>
      </div>
      <svg v-chart-size="setChartWidth" class="chart" :viewBox="'0 0 ' + W + ' ' + TH">
        <line :x1="X0" y1="20" :x2="X0" :y2="T_BASE" stroke="var(--chart-grid)" stroke-width="1"/>
        <line :x1="X0" :y1="T_BASE" :x2="X1" :y2="T_BASE" stroke="var(--chart-grid)" stroke-width="1"/>
        <line :x1="X0" :y1="TMID" :x2="X1" :y2="TMID" stroke="var(--chart-grid)" stroke-width="1" stroke-dasharray="3 3"/>
        <text x="30" y="24" fill="var(--t3)" text-anchor="end">{{ tp.max }}</text>
        <text x="30" :y="TMID + 4" fill="var(--t3)" text-anchor="end">{{ Math.round(tp.max / 2) }}</text>
        <text x="30" :y="T_BASE + 4" fill="var(--t3)" text-anchor="end">0</text>
        <text v-for="(w, i) in tp.weeks" :key="w" :x="tp.xs[i]" :y="TH - 10" fill="var(--t3)" :text-anchor="i === 0 ? 'start' : 'middle'">{{ w }}</text>
        <polyline :points="tp.pts" fill="none" stroke="var(--green)" stroke-width="2" stroke-linecap="round"/>
        <g v-for="(v, i) in tp.closed" :key="i" style="cursor:pointer" @click="goBoard(velOrder[tp.idxs[i]] || '')">
          <title>{{ tp.weeks[i] }}：完成 {{ v }} 个任务，点击看板查看</title>
          <circle :cx="tp.xs[i]" :cy="tp.y(v)" r="4" fill="var(--green)"/>
        </g>
      </svg>
    </div>
    </template>

    <!-- ===== 模块质量报表（负责人可见） ===== -->
    <template v-else>
      <LoadingMask v-if="reportLoading" />
      <div v-if="tab === 'modules'" class="card rise d3">
        <div class="card-head">
          <span class="card-title">模块质量与产出</span>
          <span class="card-extra">{{ fIter === '全部' ? '全部迭代' : fIter }} · {{ moduleReport?.items.length || 0 }} 行</span>
        </div>
        <div v-if="reportError" class="rpt-err">{{ reportError }}</div>
        <div v-else-if="moduleReport" class="rpt-scroll">
          <div class="rpt-grid mod">
            <div class="rpt-head">
              <div>模块</div>
              <div>负责人</div>
              <div>需求数 / 点</div>
              <div>需求完成率</div>
              <div>任务数 / 完成率</div>
              <div>预估 / 投入</div>
              <div>缺陷 / 未关闭</div>
              <div>缺陷密度</div>
              <div>生产逃逸率</div>
              <div>重开 / MTTR</div>
              <div>工时</div>
            </div>
            <div v-for="x in moduleReport.items" :key="x.id ?? 'none'" class="rpt-row" :class="{ loose: x.id === null }">
              <div>
                <div class="rpt-name">{{ x.name }}</div>
                <div v-if="x.description" class="rpt-sub">{{ x.description }}</div>
              </div>
              <div>{{ x.ownerName || '—' }}</div>
              <div><b>{{ x.reqCount }}</b><span class="muted"> / {{ x.reqPoints }} 点</span></div>
              <div>{{ pctText(x.reqDoneRate) }}</div>
              <div><b>{{ x.taskCount }}</b><span class="muted"> / {{ pctText(x.taskDoneRate) }}</span></div>
              <div><b>{{ x.taskEstimate }}</b><span class="muted"> / {{ x.taskSpent }} h</span></div>
              <div>
                <b>{{ x.defectCount }}</b><span class="muted"> / {{ x.openDefects }}</span>
                <span v-if="x.p0p1Open" class="warn-chip">P0/P1 {{ x.p0p1Open }}</span>
              </div>
              <div>{{ x.defectDensity ?? '—' }}</div>
              <div>{{ pctText(x.escapeRate) }}<span class="muted"> · {{ x.escapeCount }}</span></div>
              <div>{{ x.reopenCount }} 次<span class="muted"> / {{ x.mttrHours == null ? '—' : x.mttrHours + ' h' }}</span></div>
              <div>{{ x.worklogHours }} h</div>
            </div>
          </div>
          <div v-if="!moduleReport.items.length" class="empty">当前项目暂无模块，请先在「设置 · 业务模块」中建立字典</div>
        </div>
        <div v-if="moduleReport?.note" class="note">口径：{{ moduleReport.note }}</div>
      </div>

      <!-- ===== 成员负载报表（负责人可见，引入缺陷仅用于复盘） ===== -->
      <div v-else class="card rise d3">
        <div class="card-head">
          <span class="card-title">成员负载与质量</span>
          <span class="card-extra">{{ fIter === '全部' ? '全部迭代' : fIter }} · {{ peopleReport?.items.length || 0 }} 人</span>
        </div>
        <div v-if="reportError" class="rpt-err">{{ reportError }}</div>
        <div v-else-if="peopleReport" class="rpt-scroll">
          <div class="rpt-grid people">
            <div class="rpt-head">
              <div>成员</div>
              <div>待办需求</div>
              <div>任务（进行中 / 已完成）</div>
              <div>完成率</div>
              <div>在办估时</div>
              <div>WIP</div>
              <div>逾期</div>
              <div>缺陷（负责 / 未关闭 / 已修复）</div>
              <div>平均修复</div>
              <div>引入缺陷 <span class="tag-review">仅复盘</span></div>
              <div>报告缺陷</div>
              <div>工时</div>
            </div>
            <div v-for="x in peopleReport.items" :key="x.id" class="rpt-row">
              <div>
                <div class="rpt-name">{{ x.name }}</div>
                <div v-if="x.role" class="rpt-sub">{{ x.role }}</div>
              </div>
              <div>{{ x.openRequirements }}</div>
              <div><b>{{ x.openTasks }}</b><span class="muted"> / {{ x.doneTasks }}</span></div>
              <div>{{ pctText(x.completionRate) }}</div>
              <div>{{ x.openEstimate }} h</div>
              <div>{{ x.wip }}</div>
              <div><span :class="{ 'over-chip': x.overdue }">{{ x.overdue }}</span></div>
              <div><b>{{ x.ownerDefects }}</b><span class="muted"> / {{ x.openDefects }} / {{ x.fixedDefects }}</span></div>
              <div>{{ x.avgFixHours == null ? '—' : x.avgFixHours + ' h' }}</div>
              <div>{{ x.introducedDefects }}</div>
              <div>{{ x.reportedDefects }}</div>
              <div>{{ x.worklogHours }} h</div>
            </div>
          </div>
          <div v-if="!peopleReport.items.length" class="empty">当前项目暂无可统计成员</div>
        </div>
        <div v-if="peopleReport?.note" class="note">口径：{{ peopleReport.note }}</div>
      </div>
    </template>
  </template>
</template>

<style scoped>
.chart{width:100%;height:auto;display:block}
.legend{display:flex;gap:20px;margin-top:10px;font-size:12px;color:var(--t2)}
.legend i{display:inline-block;width:14px;height:3px;border-radius:1.5px;margin-right:6px;vertical-align:2px}
.note{font-size:12px;color:var(--t3);margin-top:10px}
.card{margin-bottom:16px}
.trend{font-size:12px;font-weight:400;padding:1px 7px;border-radius:5px}
.trend.good{background:var(--green-bg);color:var(--green)}
.trend.bad{background:var(--red-bg);color:var(--red)}
.stat .unit{font-size:13px;font-weight:400;color:var(--t3);margin-left:4px}
.empty-tip{padding:52px 0;text-align:center;font-size:13px;color:var(--t3)}

/* ===== 报表切换 ===== */
.rpt-tabs{display:flex;gap:2px;padding:3px;border:1px solid var(--border);border-radius:9px;
  background:var(--track);box-shadow:var(--shadow-xs);width:fit-content;margin-bottom:14px}
.rpt-tabs button{height:28px;padding:0 16px;border:none;border-radius:6px;background:none;
  font-size:13px;color:var(--t2);cursor:pointer;font-family:inherit;
  transition:background .15s var(--ease),color .15s var(--ease),box-shadow .2s var(--ease)}
.rpt-tabs button:hover{color:var(--t1)}
.rpt-tabs button.on{background:var(--card);color:var(--primary);font-weight:700;box-shadow:var(--shadow-sm)}

/* ===== 模块 / 成员报表 ===== */
.rpt-scroll{overflow-x:auto}
.rpt-grid.mod{--cols:190px 90px 100px 92px 116px 116px 138px 80px 104px 120px 76px;min-width:1222px}
.rpt-grid.people{--cols:170px 84px 148px 84px 94px 64px 64px 184px 96px 110px 90px 76px;min-width:1264px}
.rpt-head,.rpt-row{display:grid;grid-template-columns:var(--cols);align-items:center;column-gap:8px;
  padding:10px 4px;font-size:13px;color:var(--t2)}
.rpt-head{padding-bottom:8px;border-bottom:1px solid var(--border);font-size:12px;color:var(--t3);font-weight:400}
.rpt-row{border-bottom:1px solid var(--border);background:var(--card);transition:background .14s var(--ease)}
.rpt-row:hover{background:var(--surface-3)}
.rpt-row:last-of-type{border-bottom:none}
.rpt-name{font-size:14px;color:var(--t1);font-weight:400;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.rpt-sub{font-size:12px;color:var(--t3);margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.rpt-row b{color:var(--t1);font-weight:700}
.muted{color:var(--t3)}
.warn-chip{margin-left:6px;padding:0 5px;border-radius:4px;background:var(--red-bg);color:var(--red);font-size:12px}
.tag-review{margin-left:2px;padding:0 5px;border-radius:4px;background:var(--primary-bg);color:var(--primary);font-size:12px;font-weight:400}
.over-chip{color:var(--red);font-weight:700}
.rpt-err{padding:12px 0;font-size:13px;color:var(--red)}
.empty{padding:32px 0;text-align:center;font-size:13px;color:var(--t3)}
</style>
