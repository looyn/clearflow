<script setup>
import { ref, onMounted, computed, watch } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api';
import { useUiStore } from '../stores/ui';
import LoadingMask from '../components/LoadingMask.vue';
import { useMembersStore } from '../stores/members';
import IterCountdown from '../components/IterCountdown.vue';
import { sanitizeHtml } from '../utils/sanitize';
import { APP_VERSION } from '../utils/appVersion';

const members = useMembersStore() // 成员映射（负责人/头像）

const ui = useUiStore()
const router = useRouter()
const loading = ref(true);
const ov = ref(null);
const mmap = computed(() => members.map);
const feed = ref([]); // 最近动态（api.getActivityFeed）

// 页头日期：真实系统时间（ISO 周数 + 日期 + 星期 + 数据更新时刻）
const headMeta = ref('');
const updatedAt = ref('');
function isoWeek(d) {
    const dt = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
    const day = dt.getUTCDay() || 7;
    dt.setUTCDate(dt.getUTCDate() + 4 - day);
    const yr = new Date(Date.UTC(dt.getUTCFullYear(), 0, 1));
    return Math.ceil(((dt - yr) / 86400000 + 1) / 7);
}
function fmtMeta(d) {
    const p = (n) => String(n).padStart(2, '0');
    const wk = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()];
    return `${d.getFullYear()} 年第 ${isoWeek(d)} 周 · ${d.getMonth() + 1} 月 ${d.getDate()} 日 ${wk} · 数据更新于 ${p(d.getHours())}:${p(d.getMinutes())}`;
}
async function fetchData() {
    await members.load();
    const [o, f] = await Promise.all([api.getOverview(), api.getActivityFeed(8)]);
    ov.value = o;
    feed.value = f;
    const now = new Date();
    headMeta.value = fmtMeta(now);
    const p = (n) => String(n).padStart(2, '0');
    updatedAt.value = `${p(now.getHours())}:${p(now.getMinutes())}`;
}
onMounted(async () => {
    await fetchData();
    loading.value = false;
});
watch(() => ui.refreshTick, fetchData);

// 最近动态：按 kind 跳转对应页面（overview 系统日志不跳）
const FEED_PATH = { task: '/board', defect: '/defects', requirement: '/requirements', release: '/releases' };
function gotoFeed(a) {
    const p = FEED_PATH[a.kind];
    if (p && a.refId) router.push({ path: p, query: { focus: a.refId } });
}

// KPI 卡片按指标含义跳到对应页面
function gotoKpi(k) {
    const l = k.label || '';
    if (l.includes('缺陷')) return router.push('/defects');
    if (l.includes('需求')) return router.push('/requirements');
    if (l.includes('发版') || l.includes('发布')) return router.push('/releases');
    if (l.includes('任务') || l.includes('完成') || l.includes('迭代') || l.includes('积压')) return router.push('/board');
    return router.push('/metrics');
}

// 导出 Markdown 周报（指标 + 迭代 + 团队负载 + 风险 + 动态）
function exportReport() {
    const o = ov.value;
    if (!o) return;
    const strip = (s) => String(s || '').replace(/<[^>]+>/g, '');
    const lines = [`# 研发周报 · ${o.currentIter.name}`, '', `> ${headMeta.value}`, '', '## 关键指标', ''];
    o.kpis.forEach((k) => {
        lines.push(`- **${k.label}**：${k.num}${k.unit || ''}${k.trend ? `（${k.trend.text}）` : ''}${k.note ? ` · ${k.note}` : ''}`);
    });
    lines.push('', '## 当前迭代', '');
    o.currentIter.stats.forEach((s) => lines.push(`- ${s.k}：${s.v}`));
    lines.push('', '## 团队负载', '');
    o.teamLoad.forEach((p) => lines.push(`- ${p.name}（${p.role}）：${p.pct}%`));
    lines.push('', '## 风险与阻塞', '');
    if (o.risks.length) o.risks.forEach((r) => lines.push(`- [${r.tag}] ${r.title} — ${r.meta}`));
    else lines.push('- 暂无');
    lines.push('', '## 最近动态', '');
    if (feed.value.length) feed.value.forEach((a) => lines.push(`- ${strip(a.text)}（${a.time}）`));
    else lines.push('- 暂无');
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    const url = URL.createObjectURL(new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' }));
    const a = Object.assign(document.createElement('a'), { href: url, download: `weekly-${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}.md` });
    a.click();
    URL.revokeObjectURL(url);
}
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else-if="ov">
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>研发概览</h1>
                <div class="page-meta">{{ headMeta }}</div>
            </div>
            <div class="page-right">
                <button class="ghost-btn" @click="exportReport">导出周报</button>
                <IterCountdown />
            </div>
        </div>

        <!-- KPI -->
        <div class="kpis rise d2">
            <div v-for="(k, i) in ov.kpis" :key="i" class="card lift kpi-card jump" @click="gotoKpi(k)">
                <div class="kpi-label">{{ k.label }}</div>
                <div class="kpi-num">
                    {{ k.num }}<small v-if="k.unit">{{ k.unit }}</small>
                </div>
                <div class="kpi-foot">
                    <span v-if="k.trend" class="trend" :class="k.trend.good ? 'good' : 'bad'">{{ k.trend.text }}</span>
                    <span class="kpi-note">{{ k.note }}</span>
                </div>
                <div v-if="k.bar !== undefined" class="kpi-bar"><i :style="{ width: k.bar + '%' }"></i></div>
            </div>
        </div>

        <!-- 迭代 + 团队 -->
        <div class="cols-2">
            <div class="card rise d3">
                <div class="card-head">
                    <span class="card-title">{{ ov.currentIter.name }}</span>
                    <span class="state-tag">{{ ov.currentIter.state }}</span>
                    <span class="card-extra">{{ ov.currentIter.release }}</span>
                </div>
                <div class="bar"><i v-for="b in ov.currentIter.bar" :key="b.cls" :class="b.cls" :style="{ width: b.pct + '%' }"></i></div>
                <div class="legend">
                    <span v-for="l in ov.currentIter.legend" :key="l.label"><i :style="{ background: l.color }"></i>{{ l.label }}</span>
                    <span style="margin-left: auto; color: var(--t3)">合计 {{ ov.currentIter.total }}</span>
                </div>
                <div class="iter-stats">
                    <div v-for="s in ov.currentIter.stats" :key="s.k">
                        <div class="v">{{ s.v }}</div>
                        <div class="k">{{ s.k }}</div>
                    </div>
                </div>
                <div class="miles">
                    <div v-for="m in ov.currentIter.miles" :key="m.n" class="mile" :class="m.state">
                        <div class="d">{{ m.d }}</div>
                        <div class="n">{{ m.n }}</div>
                    </div>
                </div>
            </div>

            <div class="card rise d4">
                <div class="card-head">
                    <span class="card-title">团队负载</span>
                    <span class="card-extra">{{ ov.teamLoad.length }} 名成员</span>
                </div>
                <div v-for="p in ov.teamLoad" :key="p.id" class="load-row" :class="p.cls">
                    <div class="ava" :class="mmap[p.id]?.color" style="width: 28px; height: 28px; font-size:12px">{{ mmap[p.id]?.short || '?' }}</div>
                    <div class="load-name">
                        {{ p.name }}<small>{{ p.role }}</small>
                    </div>
                    <div class="load-track"><i class="load-fill" :style="{ width: Math.min(p.pct, 100) + '%' }"></i></div>
                    <div class="load-pct">{{ p.pct }}%</div>
                </div>
                <div class="load-note">负载 = 在办任务估时 ÷ 本迭代剩余容量，红色为超载</div>
            </div>
        </div>

        <!-- 风险 + 动态 -->
        <div class="cols-2" style="margin-bottom: 24px">
            <div class="card rise d5">
                <div class="card-head">
                    <span class="card-title">风险与阻塞</span>
                    <span class="card-extra">{{ ov.risks.length }} 项待跟进</span>
                </div>
                <div v-for="r in ov.risks" :key="r.title" class="risk-row">
                    <span class="risk-tag" :class="r.level">{{ r.tag }}</span>
                    <div>
                        <div class="risk-title">{{ r.title }}</div>
                        <div class="risk-meta">{{ r.meta }}</div>
                    </div>
                </div>
                <div v-if="!ov.risks.length" class="risk-empty">暂无风险与阻塞</div>
            </div>

            <div class="card rise d5">
                <div class="card-head">
                    <span class="card-title">最近动态</span>
                    <span class="card-extra">{{ feed.length }} 条</span>
                </div>
                <!-- 动态来自 api.getActivityFeed：who 非空带头像，overview 日志直接 v-html -->
                <div v-for="(a, i) in feed" :key="i" class="act-row" :class="{ jump: a.kind !== 'overview' }" @click="gotoFeed(a)">
                    <span v-if="a.who" class="ava mini-ava" :class="mmap[a.who]?.color">{{ mmap[a.who]?.short || '?' }}</span>
                    <span v-else class="act-dot"></span>
                    <div v-if="a.who" class="act-text">
                        <b>{{ mmap[a.who]?.name || '未知' }}</b> {{ a.text }}
                    </div>
                    <div v-else class="act-text" v-html="sanitizeHtml(a.text)"></div>
                    <span class="act-time">{{ a.time }}</span>
                </div>
                <div v-if="!feed.length" class="act-empty">暂无动态</div>
            </div>
        </div>

        <!-- 页脚 -->
        <footer class="foot rise d5">
            <span>清流 ClearFlow · 让研发协作更高效</span>
            <span class="meta">v{{ APP_VERSION }} · 最近更新 {{ updatedAt }}</span>
        </footer>
    </template>
</template>

<style scoped>
.kpis {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
    gap: 16px;
    margin-bottom: 16px;
}
.kpi-card {
    position: relative;
    overflow: hidden;
}
.kpi-card.jump {
    cursor: pointer;
}
.kpi-card::before {
    content: '';
    position: absolute;
    inset: 0 0 auto 0;
    height: 3px;
    background: var(--grad-brand);
    opacity: 0;
    transition: opacity 0.22s var(--ease);
}
.kpi-card:hover::before {
    opacity: 1;
}
.kpi-label {
    font-size:13px;
    color: var(--t3);
}
.kpi-num {
    font-size: 30px;
    font-weight:700;
    margin-top: 8px;
    font-variant-numeric: tabular-nums;
}
.kpi-num small {
    font-size: 15px;
    font-weight:400;
    color: var(--t2);
}
.kpi-foot {
    margin-top: 10px;
    display: flex;
    align-items: center;
    gap: 8px;
}
.trend {
    font-size:12px;
    font-weight:400;
    padding: 1px 7px;
    border-radius: 5px;
}
.trend.good {
    background: var(--green-bg);
    color: var(--green);
}
.trend.bad {
    background: var(--red-bg);
    color: var(--red);
}
.kpi-note {
    font-size: 12px;
    color: var(--t3);
}
.kpi-bar {
    margin-top: 12px;
    height: 6px;
    border-radius: 3px;
    background: var(--track);
    overflow: hidden;
}
.kpi-bar i {
    display: block;
    height: 100%;
    border-radius: 3px;
    background: var(--grad-primary);
    transition: width 0.6s var(--ease);
}

.cols-2 {
    display: grid;
    grid-template-columns: 1.65fr 1fr;
    gap: 16px;
    margin-bottom: 16px;
}

.bar {
    display: flex;
    gap: 4px;
    height: 12px;
    border-radius: 6px;
    overflow: hidden;
}
.bar i {
    display: block;
    height: 100%;
    animation: grow 1s ease-out both;
}
.bar .b-done {
    background: var(--primary);
    width: 68%;
}
.bar .b-doing {
    background: var(--primary-light);
    width: 20%;
    animation-delay: 0.12s;
}
.bar .b-todo {
    background: var(--track);
    width: 12%;
    animation-delay: 0.24s;
}
.legend {
    display: flex;
    gap: 20px;
    margin-top: 12px;
    font-size:13px;
    color: var(--t2);
}
.legend i {
    display: inline-block;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    margin-right: 6px;
}
.iter-stats {
    display: flex;
    margin-top: 18px;
    border-top: 1px solid var(--border);
    padding-top: 16px;
}
.iter-stats div {
    flex: 1;
    padding-left: 20px;
    border-left: 1px solid var(--border);
}
.iter-stats div:first-child {
    border-left: none;
    padding-left: 0;
}
.iter-stats .v {
    font-size: 20px;
    font-weight:700;
    font-variant-numeric: tabular-nums;
}
.iter-stats .k {
    font-size: 12px;
    color: var(--t3);
    margin-top: 2px;
}
.miles {
    display: flex;
    margin-top: 22px;
}
.mile {
    flex: 1;
    position: relative;
    padding-top: 20px;
}
.miles::before {
    content: '';
    position: absolute;
    top: 4.5px;
    left: 8px;
    right: 8px;
    height: 2px;
    background: var(--track);
    border-radius: 1px;
}
.mile::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: var(--card);
    border: 2px solid var(--chart-muted);
}
.mile.done::before {
    background: var(--primary);
    border-color: var(--primary);
}
.mile.now::before {
    background: var(--card);
    border-color: var(--primary);
    box-shadow: 0 0 0 3px var(--primary-bg);
}
.mile .d {
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.mile .n {
    font-size:13px;
    font-weight:400;
    margin-top: 2px;
}
.mile.now .n {
    color: var(--primary);
}

.load-row {
    display: grid;
    grid-template-columns: 28px 1fr 110px 44px;
    gap: 12px;
    align-items: center;
    padding: 8px;
    margin: 0 -8px;
    border-radius: var(--radius-sm);
    transition: background 0.15s var(--ease);
}
.load-row:hover {
    background: var(--surface-3);
}
.load-name {
    font-size: 13px;
    font-weight:400;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.load-name small {
    font-size:12px;
    color: var(--t3);
    font-weight: 400;
    margin-left: 6px;
}
.load-track {
    height: 8px;
    border-radius: 4px;
    background: var(--track);
    overflow: hidden;
}
.load-fill {
    display: block;
    height: 100%;
    border-radius: 4px;
    animation: grow 1.1s ease-out both;
}
.load-pct {
    font-size: 12px;
    color: var(--t2);
    text-align: right;
    font-variant-numeric: tabular-nums;
}
.over .load-fill {
    background: var(--red);
}
.over .load-pct {
    color: var(--red);
    font-weight:700;
}
.warm .load-fill {
    background: var(--warm);
}
.cool .load-fill {
    background: var(--primary);
}
.load-note {
    margin-top: 12px;
    font-size: 12px;
    color: var(--t3);
    border-top: 1px solid var(--border);
    padding-top: 12px;
}

.risk-row {
    display: flex;
    gap: 12px;
    padding: 11px 0;
    border-bottom: 1px solid var(--border);
}
.risk-row:last-child {
    border-bottom: none;
    padding-bottom: 0;
}
.risk-row + .risk-row {
    padding-top: 12px;
}
.risk-tag {
    flex: none;
    font-size:12px;
    font-weight:400;
    padding: 2px 8px;
    border-radius: 5px;
    height: fit-content;
}
.risk-tag.b {
    background: var(--red-bg);
    color: var(--red);
}
.risk-tag.r {
    background: var(--amber-bg);
    color: var(--amber);
}
.risk-tag.n {
    background: var(--primary-bg);
    color: var(--primary);
}
.risk-title {
    font-size:14px;
}
.risk-meta {
    font-size: 12px;
    color: var(--t3);
    margin-top: 3px;
}
.risk-empty {
    padding: 14px 0;
    font-size: 13px;
    color: var(--t3);
    text-align: center;
}

.act-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 0;
    border-bottom: 1px solid var(--border);
}
.act-row:last-child {
    border-bottom: none;
    padding-bottom: 0;
}
.act-row + .act-row {
    padding-top: 11px;
}
.act-row.jump {
    cursor: pointer;
    border-radius: var(--radius-sm);
    padding-left: 8px;
    padding-right: 8px;
    margin: 0 -8px;
    transition: background 0.14s var(--ease);
}
.act-row.jump:hover {
    background: var(--surface-3);
}
.act-dot {
    width: 20px;
    height: 20px;
    flex: none;
    border-radius: 50%;
    background: var(--track);
    border: 1px solid var(--border);
}
.act-time {
    flex: none;
    font-size: 12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.act-text {
    flex: 1;
    min-width: 0;
    font-size: 13px;
}
.act-text :deep(b) {
    font-weight:700;
}
.act-text :deep(.mr) {
    color: var(--t3);
}
.act-text :deep(.hl) {
    color: var(--red);
    font-weight:400;
}
.act-empty {
    font-size:13px;
    color: var(--t3);
    padding: 8px 0;
}

.foot {
    display: flex;
    align-items: center;
    margin-top: 4px;
    padding: 0 4px;
    font-size: 12px;
    color: var(--t3);
}
.foot .meta {
    margin-left: auto;
}

@media (max-width: 1150px) {
    .kpis {
        grid-template-columns: repeat(2, 1fr);
    }
    .cols-2 {
        grid-template-columns: 1fr;
    }
}
</style>
