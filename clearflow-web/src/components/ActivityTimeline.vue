<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { api } from '../api'
import { useUiStore } from '../stores/ui'
import { useMembersStore } from '../stores/members'

// ===== 活动时间线（自包含：自己拉数据）=====
const props = defineProps({
  kind: { type: String, required: true },
  refId: { type: String, required: true },
  fallback: { type: String, default: '' } // 无活动时展示，如「创建于 10.01」
})

const ui = useUiStore()
const members = useMembersStore() // 成员映射（共享，详情打开时确保已加载）
// 活动条目：{ by: 成员id, text, type: status|comment|attach|create|verify, date: 'MM.DD', time: 'HH:mm' }
const items = ref([])
const mmap = computed(() => members.map) // 成员映射 id → member

// 按日期分组：今天 / 昨天 / YYYY-MM-DD
const groups = computed(() => {
  const p = n => String(n).padStart(2, '0')
  const fmt = d => `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
  const now = new Date()
  const today = fmt(now)
  const yesterday = fmt(new Date(now.getTime() - 86400000))
  const map = new Map()
  items.value.forEach(it => {
    const label = it.date === today ? '今天' : it.date === yesterday ? '昨天' : it.date
    if (!map.has(label)) map.set(label, [])
    map.get(label).push(it)
  })
  return [...map.entries()].map(([label, rows]) => ({ label, rows }))
})

// 数据源：api.getActivity 返回 { id, who, type, text, time:'YYYY-MM-DD HH:mm:ss' }（升序）
async function load() {
  const rows = await api.getActivity(props.kind, props.refId)
  items.value = rows.map(a => {
    const parts = a.time.split(' ')
    return {
      by: a.who, type: a.type, text: a.text,
      date: parts[0],  // "YYYY-MM-DD"
      time: parts[1]   // "HH:mm:ss"
    }
  })
}

onMounted(() => members.load())
watch(() => props.refId, load)
watch(() => ui.refreshTick, load)
</script>

<template>
  <div class="tl">
    <!-- 无活动：显示 fallback -->
    <div v-if="!groups.length" class="tl-empty">{{ fallback }}</div>

    <template v-else>
      <div v-for="g in groups" :key="g.label" class="tl-group">
        <div class="tl-date">{{ g.label }}</div>
        <div class="tl-list">
          <div v-for="(a, i) in g.rows" :key="i" class="tl-row">
            <!-- 类型小圆点（挂在左侧竖线上） -->
            <span class="tl-dot" :class="a.type"></span>
            <span class="ava mini-ava" :class="mmap[a.by]?.color">{{ mmap[a.by]?.short || '?' }}</span>
            <span class="tl-text"><b>{{ mmap[a.by]?.name || a.by }}</b> {{ a.text }}</span>
            <span class="tl-time">{{ a.time }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tl-empty{font-size:13px;color:var(--t3)}

.tl-group + .tl-group{margin-top:10px}
.tl-date{font-size:12px;font-weight:700;color:var(--t3);margin-bottom:2px}

/* 组内左侧连接竖线 */
.tl-list{position:relative;padding-left:14px}
.tl-list::before{content:'';position:absolute;left:3px;top:8px;bottom:8px;width:1.5px;background:var(--border)}

.tl-row{position:relative;display:flex;align-items:center;gap:8px;min-height:32px;font-size:13px}
.tl-dot{position:absolute;left:-13.5px;top:12.5px;width:7px;height:7px;border-radius:50%;
  background:var(--t3);box-shadow:0 0 0 2.5px var(--card)}
.tl-dot.status{background:var(--primary)}
.tl-dot.comment{background:var(--green)}
.tl-dot.attach{background:var(--purple)}
.tl-dot.create{background:var(--t3)}
.tl-dot.verify{background:var(--amber)}

.tl-text{flex:1;min-width:0;color:var(--t2);line-height:1.5}
.tl-text b{color:var(--t1);font-weight:700;margin-right:2px}
.tl-time{flex:none;font-size:12px;color:var(--t3);font-variant-numeric:tabular-nums}
</style>
