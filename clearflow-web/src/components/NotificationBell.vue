<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useUiStore } from '../stores/ui'

const router = useRouter()
const ui = useUiStore()
const open = ref(false)
const box = ref(null)

const list = ref([])      // 已加载的通知（分页累加）
const page = ref(0)       // 当前已加载到的页码
const pageSize = 20
const total = ref(0)
const loading = ref(false)
const loadedAll = computed(() => list.value.length >= total.value)

const unread = ref(0)     // 服务端未读数（角标），不依赖已加载页

const kindLabel = { mention: '@ 我', due: '到期', defect: '缺陷', ci: 'CI', status: '状态', assign: '指派', release: '发版' }
const kindCls = { mention: 'hl', due: 'am', defect: 'rd', ci: 'gy', status: 'gy', assign: 'bl', release: 'pu' }

// ===== 相对时间：刚刚 / X 分钟前 / X 小时前 / 昨天 HH:mm / MM-DD / YYYY-MM-DD =====
function relTime(createdAt) {
  const d = new Date(createdAt)
  if (isNaN(d)) return createdAt || ''
  const diff = Date.now() - d.getTime()
  if (diff < 60e3) return '刚刚'
  if (diff < 3600e3) return `${Math.floor(diff / 60e3)} 分钟前`
  const startOfToday = new Date(); startOfToday.setHours(0, 0, 0, 0)
  if (d >= startOfToday) return `${Math.floor(diff / 3600e3)} 小时前`
  const y = new Date(startOfToday); y.setDate(y.getDate() - 1)
  if (d >= y) return `昨天 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (d.getFullYear() === new Date().getFullYear())
    return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// ===== 按天分组：今天 / 昨天 / 10月12日 / 2025年10月12日（列表已按时间倒序，顺扫即可成组）=====
const groups = computed(() => {
  const out = []
  const startOfToday = new Date(); startOfToday.setHours(0, 0, 0, 0)
  const startOfYesterday = new Date(startOfToday); startOfYesterday.setDate(startOfYesterday.getDate() - 1)
  for (const n of list.value) {
    const d = new Date(n.createdAt)
    let label = ''
    if (isNaN(d)) label = '更早'
    else if (d >= startOfToday) label = '今天'
    else if (d >= startOfYesterday) label = '昨天'
    else if (d.getFullYear() === new Date().getFullYear()) label = `${d.getMonth() + 1} 月 ${d.getDate()} 日`
    else label = `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日`
    const last = out[out.length - 1]
    if (last && last.label === label) last.items.push(n)
    else out.push({ label, items: [n] })
  }
  return out
})

// ===== 拉取 =====
async function syncUnread() {
  try { unread.value = (await api.getNotificationUnread()).count } catch (e) { /* 角标失败不阻塞 */ }
}
async function loadFirst() {
  loading.value = true
  try {
    const r = await api.getNotifications(1, pageSize)
    list.value = r.list || []
    total.value = r.total || 0
    page.value = 1
  } finally {
    loading.value = false
  }
}
async function loadMore() {
  if (loading.value || loadedAll.value) return
  loading.value = true
  try {
    const r = await api.getNotifications(page.value + 1, pageSize)
    list.value = list.value.concat(r.list || [])
    total.value = r.total ?? total.value
    page.value += 1
  } finally {
    loading.value = false
  }
}

// 打开面板：拉第一页 + 同步未读数
async function toggle() {
  open.value = !open.value
  if (open.value) await loadFirst()
}

// ===== 已读 / 未读 =====
async function toggleRead(n, ev) {
  ev.stopPropagation()
  const next = !n.read
  n.read = next                       // 乐观更新
  unread.value = Math.max(0, unread.value + (next ? -1 : 1))
  try {
    await api.markNotificationRead(n.id, next)
  } catch (e) {
    n.read = !next                    // 失败回滚
    unread.value = Math.max(0, unread.value + (next ? 1 : -1))
  }
}
async function markAll() {
  const prev = list.value.map((n) => n.read)
  list.value.forEach((n) => (n.read = true))
  unread.value = 0
  try {
    await api.markAllNotifications()
  } catch (e) {
    list.value.forEach((n, i) => (n.read = prev[i]))
    syncUnread()
  }
}

// ===== 删除 =====
async function del(n, ev) {
  ev.stopPropagation()
  const i = list.value.indexOf(n)
  if (i < 0) return
  list.value.splice(i, 1)             // 乐观移除
  total.value = Math.max(0, total.value - 1)
  if (!n.read) unread.value = Math.max(0, unread.value - 1)
  try {
    await api.deleteNotification(n.id)
  } catch (e) {
    list.value.splice(i, 0, n)        // 失败还原
    total.value += 1
    if (!n.read) unread.value += 1
  }
}
// 清空已读：先移除本地已读项，成功后再拉一次保证与后端一致
async function clearRead() {
  const removed = list.value.filter((n) => n.read)
  if (!removed.length) return
  list.value = list.value.filter((n) => !n.read)
  total.value = Math.max(0, total.value - removed.length)
  try {
    await api.clearReadNotifications()
    await loadFirst()
  } catch (e) {
    await loadFirst()                 // 失败整体回退到服务端真实状态
  }
}

// 点击行：标为已读并跳转（优先使用后端 refKind/refId 精确深链）
const REF_PAGE = { task: '/board', requirement: '/requirements', defect: '/defects', release: '/releases', doc: '/docs' }
function go(n) {
  if (!n.read) {
    n.read = true
    unread.value = Math.max(0, unread.value - 1)
    api.markNotificationRead(n.id, true).catch(() => syncUnread())
  }
  open.value = false
  if (n.refKind && REF_PAGE[n.refKind]) {
    const page = REF_PAGE[n.refKind]
    const key = n.refKind === 'doc' ? 'doc' : 'focus'
    router.push({ path: page, query: n.refId ? { [key]: n.refId } : {} })
    return
  }
  const page = n.kind === 'defect' ? '/defects'
    : n.kind === 'release' ? '/releases'
    : n.kind === 'assign' ? (n.text.startsWith('缺陷') ? '/defects' : '/mywork')
    : n.kind === 'due' ? '/mywork'
    : n.text.includes('发版') ? '/releases' : n.text.includes('R-') ? '/requirements' : '/overview'
  router.push(page)
}

function onDocClick(e) {
  if (open.value && box.value && !box.value.contains(e.target)) open.value = false
}
onMounted(() => {
  document.addEventListener('click', onDocClick)
  syncUnread()
})
onUnmounted(() => document.removeEventListener('click', onDocClick))

// 别处产生新动静（写操作广播）时刷新角标；面板开着则顺带刷新列表
watch(() => ui.refreshTick, () => {
  syncUnread()
  if (open.value) loadFirst()
})
</script>

<template>
  <div ref="box" class="bell-wrap">
    <button class="icon-btn" :class="{ on: open }" @click="toggle">
      <svg viewBox="0 0 24 24"><path d="M6 9.5a6 6 0 1 1 12 0c0 4.8 1.8 5.8 1.8 5.8H4.2S6 14.3 6 9.5"/><path d="M10 19.5a2.2 2.2 0 0 0 4 0"/></svg>
      <i v-if="unread">{{ unread > 99 ? '99+' : unread }}</i>
    </button>
    <div v-if="open" class="panel">
      <div class="panel-head">
        <span class="t">通知</span>
        <span v-if="unread" class="cnt">{{ unread }} 条未读</span>
        <div class="acts">
          <button v-if="unread" class="mark" @click="markAll">全部已读</button>
          <button v-if="list.some((n) => n.read)" class="mark" @click="clearRead">清空已读</button>
        </div>
      </div>

      <div class="scroll">
        <template v-if="list.length">
          <template v-for="g in groups" :key="g.label">
            <div class="group">{{ g.label }}</div>
            <div v-for="n in g.items" :key="n.id" class="row" :class="{ unread: !n.read }" @click="go(n)">
              <span class="dot" :class="{ on: !n.read }"></span>
              <span class="kind" :class="kindCls[n.kind]">{{ kindLabel[n.kind] || '通知' }}</span>
              <div class="body">
                <div class="text">{{ n.text }}</div>
                <div class="at">{{ relTime(n.createdAt) }}</div>
              </div>
              <div class="row-acts">
                <button class="ra" :title="n.read ? '标为未读' : '标为已读'" @click="toggleRead(n, $event)">
                  <svg v-if="n.read" viewBox="0 0 24 24"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v4h4"/></svg>
                  <svg v-else viewBox="0 0 24 24"><path d="m4 12 5 5L20 6"/></svg>
                </button>
                <button class="ra del" title="删除" @click="del(n, $event)">
                  <svg viewBox="0 0 24 24"><path d="M4 7h16M9 7V5h6v2M6 7l1 13h10l1-13"/></svg>
                </button>
              </div>
            </div>
          </template>
          <div class="more">
            <button v-if="!loadedAll" class="more-btn" :disabled="loading" @click="loadMore">
              {{ loading ? '加载中…' : '加载更多' }}
            </button>
            <span v-else class="more-end">已加载全部 {{ total }} 条</span>
          </div>
        </template>
        <div v-else-if="loading" class="empty">加载中…</div>
        <div v-else class="empty">暂无通知</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bell-wrap{position:relative}
.icon-btn{position:relative;width:32px;height:32px;border:none;background:none;cursor:pointer;
  display:flex;align-items:center;justify-content:center;border-radius:8px;transition:background .15s var(--ease)}
.icon-btn:hover{background:var(--track)}
.icon-btn svg{width:18px;height:18px;stroke:var(--t2);stroke-width:1.7;fill:none;
  stroke-linecap:round;stroke-linejoin:round}
.icon-btn.on{background:var(--primary-bg)}
.icon-btn i{position:absolute;top:-1px;right:-2px;min-width:16px;height:16px;padding:0 3px;background:var(--red);
  color:#fff;border-radius:8px;border:1.5px solid var(--card);font-size:12px;font-style:normal;font-weight:700;
  display:flex;align-items:center;justify-content:center;line-height:1;box-sizing:content-box}
.panel{position:absolute;right:0;top:40px;width:380px;background:var(--card);border:1px solid var(--border);
  border-radius:var(--radius-lg);box-shadow:var(--shadow-lg);z-index:1200;overflow:hidden;
  display:flex;flex-direction:column;max-height:70vh;animation:bellIn .16s var(--ease) both}
@keyframes bellIn{from{opacity:0;transform:translateY(-6px) scale(.98)}to{opacity:1;transform:none}}
.panel-head{display:flex;align-items:center;gap:8px;padding:13px 16px;border-bottom:1px solid var(--border);flex:none;
  background:var(--menu-head-bg)}
.panel-head .t{font-size:14px;font-weight:700}
.panel-head .cnt{font-size:12px;color:var(--red)}
.panel-head .acts{margin-left:auto;display:flex;gap:12px}
.panel-head .mark{border:none;background:none;color:var(--primary);font-size:12px;cursor:pointer;font-family:inherit;padding:0}

.scroll{overflow:auto;flex:1}
.group{position:sticky;top:0;z-index:1;padding:7px 16px;background:var(--bg);border-bottom:1px solid var(--border);
  font-size:12px;color:var(--t3);font-weight:400}

.row{display:flex;gap:8px;align-items:flex-start;padding:11px 16px;border-bottom:1px solid var(--border);cursor:pointer;position:relative}
.row:last-child{border-bottom:none}
.row.unread{background:var(--primary-faint)}
.row:hover{background:var(--track)}
.row.unread:hover{background:var(--primary-soft)}
.dot{width:7px;height:7px;flex:none;border-radius:50%;background:transparent;margin-top:6px}
.dot.on{background:var(--primary)}
.kind{flex:none;font-size:12px;padding:2px 7px;border-radius:5px;height:fit-content;margin-top:1px;
  background:var(--track);color:var(--t2);font-weight:400}
.kind.hl{background:var(--red-bg);color:var(--red)}
.kind.bl{background:var(--primary-bg);color:var(--primary)}
.kind.am{background:var(--amber-bg);color:var(--amber)}
.kind.rd{background:var(--red-bg);color:var(--red)}
.kind.gy{background:var(--track);color:var(--t2)}
.kind.pu{background:var(--purple-bg);color:var(--purple)}
.body{min-width:0;flex:1}
.text{font-size:13px;color:var(--t1);line-height:1.5}
.row.unread .text{font-weight:400}
.at{font-size:12px;color:var(--t3);margin-top:3px}

/* 行内操作：默认隐藏，悬停或未读时浮现，避免视觉噪音 */
.row-acts{display:flex;gap:2px;flex:none;opacity:0;transition:opacity .12s;margin-top:1px}
.row:hover .row-acts{opacity:1}
.ra{width:24px;height:24px;border:none;background:none;border-radius:6px;cursor:pointer;padding:0;
  display:flex;align-items:center;justify-content:center}
.ra svg{width:14px;height:14px;stroke:var(--t3);stroke-width:1.8;fill:none;stroke-linecap:round;stroke-linejoin:round}
.ra:hover{background:var(--track)}
.ra:hover svg{stroke:var(--t1)}
.ra.del:hover svg{stroke:var(--red)}

.more{padding:10px 16px;text-align:center}
.more-btn{border:1px solid var(--border);background:var(--card);border-radius:8px;padding:6px 16px;
  font-size:12px;color:var(--t2);cursor:pointer;font-family:inherit}
.more-btn:disabled{opacity:.6;cursor:default}
.more-end{font-size:12px;color:var(--t3)}
.empty{padding:32px 16px;text-align:center;font-size:13px;color:var(--t3)}
</style>
