<script setup>
import { ref, reactive, onMounted, computed, watch, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { api } from '../api';
import { useUiStore } from '../stores/ui';
import { useOptionsStore } from '../stores/options';
import { useMembersStore } from '../stores/members';
import PageCreateButton from '../components/PageCreateButton.vue';
import LoadingMask from '../components/LoadingMask.vue';
import AppModal from '../components/AppModal.vue';
import AppChecklist from '../components/AppChecklist.vue';
import AppPager from '../components/AppPager.vue';
import OptTag from '../components/OptTag.vue';

const ui = useUiStore();
const router = useRouter();
const route = useRoute();
const loading = ref(true);
const data = ref(null); // 容量 / 统计 / 燃尽卡片（后端聚合）
const timeline = ref([]); // 甘特时间轴：全部迭代一次取全（轻量字段，不含复盘正文）
const current = ref([]); // 「当前迭代」表：服务端分页
const curTotal = ref(0);
const released = ref([]); // 「已发布」表：服务端分页
const relTotal = ref(0);
const mmap = computed(() => members.map); // 成员映射（共享 store）
const iterReqRows = ref([]); // 迭代详情内的需求列表：服务端分页
const iterReqTotal = ref(0);
const iterReqPage = ref(1);
const iterReqPageSize = ref(10);

// 筛选/分页连打时只认最后一次响应
let fetchSeq = 0;
async function fetchCurrent() {
    const r = await api.getIterationsPage('current', curPage.value, curPageSize.value);
    current.value = r.list || [];
    curTotal.value = r.total || 0;
    if (!current.value.length && curPage.value > 1) curPage.value = 1; // 删除后页码越界回收
}
async function fetchReleased() {
    const r = await api.getIterationsPage('released', relPage.value, relPageSize.value);
    released.value = r.list || [];
    relTotal.value = r.total || 0;
    if (!released.value.length && relPage.value > 1) relPage.value = 1;
}
async function fetchTables() {
    await Promise.all([fetchCurrent(), fetchReleased()]);
}
async function fetchData() {
    const seq = ++fetchSeq;
    const [d, tl] = await Promise.all([api.getIterationCards(), api.getIterationTimeline()]);
    if (seq !== fetchSeq) return;
    data.value = d;
    timeline.value = tl || [];
    await fetchTables();
}
async function fetchIterReqs(code = detailIter.value?.code) {
    if (!code) {
        iterReqRows.value = [];
        iterReqTotal.value = 0;
        return;
    }
    const r = await api.getRequirements({ iter: code }, iterReqPage.value, iterReqPageSize.value);
    if (detailIter.value?.code !== code) return;
    iterReqRows.value = r.list || [];
    iterReqTotal.value = r.total || 0;
}
onMounted(async () => {
    await fetchData();
    loading.value = false;
    if (route.query.focus) openDetail(route.query.focus);
});
watch(() => ui.refreshTick, fetchData);

// ===== 状态流（项目设置 → 工作流）：迭代 = iterColumns，需求 = reqColumns；顺序即流转顺序，最后一档为终态 =====
const members = useMembersStore(); // 成员映射（负责人/头像）
const opts = useOptionsStore();
const iterKeys = computed(() => opts.keysOf('iteration'));
const iterTerminal = computed(() => opts.terminalOf('iteration'));
// 「进行中」档 = 配置里第二档（默认 active）
const iterActiveKey = computed(() => iterKeys.value[1] || iterKeys.value[0]);
const iterName = (k) => opts.nameOf('iteration', k);
// 徽标文案：配置里有就用配置名，否则回退实体自带的 stateLabel（历史/被删状态）
const itLabel = (it) => opts.statusOf('iteration', it.state)?.name || it.stateLabel || it.state;
const reqKeys = computed(() => opts.keysOf('requirement'));
const reqFirst = computed(() => reqKeys.value[0]);
const reqSecond = computed(() => reqKeys.value[1] || reqKeys.value[0]);

// ===== 分页（两张表各自服务端分页，paged 即当前页）=====
const curPage = ref(1);
const curPageSize = ref(10);
const curPaged = computed(() => current.value);
const relPage = ref(1);
const relPageSize = ref(10);
const relPaged = computed(() => released.value);
watch([curPage, curPageSize], () => fetchCurrent().catch(() => {}));
watch([relPage, relPageSize], () => fetchReleased().catch(() => {}));

const detailIter = ref(null); // 详情：点行先用表内行播种，复盘等完整字段异步回填
let detailSeq = 0;
async function fetchIterDetail(code) {
    const seq = ++detailSeq;
    if (!code) {
        detailIter.value = null;
        return;
    }
    const it = await api.getIterationDetail(code);
    if (seq !== detailSeq || detailIter.value?.code !== code) return;
    detailIter.value = it;
}

function openDetail(it) {
    const code = typeof it === 'string' ? it : it.code;
    detailIter.value = typeof it === 'string' ? timeline.value.find((i) => i.code === code) || { code } : it;
    iterReqPage.value = 1;
    iterReqRows.value = []; // 清掉上一个迭代的关联需求，避免闪现错数据
    iterReqTotal.value = 0;
    if (route.query.focus !== code) router.replace({ path: route.path, query: { ...route.query, focus: code } }).catch(() => {});
    // 先开弹窗，复盘与迭代内需求异步回填
    fetchIterDetail(code).catch(() => {});
    fetchIterReqs(code).catch(() => {});
}

function closeDetail() {
    detailIter.value = null;
    iterReqRows.value = [];
    iterReqTotal.value = 0;
    if (route.query.focus) {
        const q = { ...route.query };
        delete q.focus;
        router.replace({ path: route.path, query: q }).catch(() => {});
    }
}

// 浏览器前进/后退或外部深链切换迭代详情
watch(
    () => route.query.focus,
    (code) => {
        if (!code) {
            detailIter.value = null;
            return;
        }
        if (detailIter.value?.code === code) return;
        openDetail(code);
    }
);
watch([iterReqPage, iterReqPageSize], () => fetchIterReqs().catch(() => {}));

function goReq(id) {
    router.push({ path: '/requirements', query: { focus: id } });
}

// ===== 导出排期（甘特同源的全量迭代快照，不受两张表分页影响）=====
function exportCsv() {
    const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
    const head = ['迭代编号', '名称', '状态', '起止日期', '周期', '已完成', '总任务', '完成率', '负责人', '迭代目标'];
    const rows = [...(timeline.value || [])]
        .sort((a, b) => String(a.range || '').localeCompare(String(b.range || '')))
        .map((it) => [
            it.code,
            it.name,
            itLabel(it),
            it.range,
            it.days,
            it.done,
            it.total,
            `${it.pct}%`,
            mmap.value[it.owner]?.name || '',
            it.goal || ''
        ]);
    const csv = [head, ...rows].map((r) => r.map(esc).join(',')).join('\r\n');
    const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `迭代排期-${todayStr}.csv`;
    a.click();
    URL.revokeObjectURL(url);
}

// 弹窗打开期间刷新列表后，详情对象按 code 重取（含复盘等完整字段）
async function syncDetail() {
    await fetchData();
    if (detailIter.value) await fetchIterDetail(detailIter.value.code);
    await fetchIterReqs();
}

// ===== 迭代状态流转（流转按钮 = 配置顺序）=====
const iterErr = ref(''); // 流转被服务端拒绝的原因（如已有其它进行中迭代），显示在流转条下方
watch(
    () => detailIter.value?.code,
    () => {
        iterErr.value = '';
    }
);
async function setIterState(s) {
    if (!detailIter.value || detailIter.value.state === s) return;
    iterErr.value = '';
    const patch = { state: s, stateLabel: iterName(s) };
    if (s === iterTerminal.value && !detailIter.value.actualEnd) patch.actualEnd = new Date().toISOString().slice(0, 10);
    try {
        await api.updateIteration(detailIter.value.code, patch);
    } catch (e) {
        iterErr.value = e?.message || '状态更新失败，请稍后重试';
        return;
    }
    await syncDetail();
}

// ===== 规划需求入迭代（候选走服务端搜索 + 分页：未排入该迭代且未到终态）=====
const planOpen = ref(false);
const planSel = ref([]); // 已选需求 id
const planEst = ref({}); // id → 预估点数（跨页选择也能算准合计点数）
const planIter = ref('');
const planKw = ref('');
const planPage = ref(1);
const planPageSize = ref(10);
const planReqs = ref([]);
const planTotal = ref(0);
const planIterAgg = ref(null); // 目标迭代内已有需求数与点数（后端聚合）
let planSeq = 0;
async function fetchPlanPool() {
    const seq = ++planSeq;
    const iter = planIter.value;
    const r = await api.getRequirementPlanPool(iter, planKw.value.trim(), planPage.value, planPageSize.value);
    if (seq !== planSeq || !planOpen.value || planIter.value !== iter) return;
    planReqs.value = r.list || [];
    planTotal.value = r.total || 0;
}
async function fetchPlanAgg() {
    const iter = planIter.value;
    const s = await api.getRequirementStats(iter);
    if (planOpen.value && s?.iter === iter) planIterAgg.value = s;
}
const planPts = computed(() => planSel.value.reduce((n, id) => n + (planEst.value[id] || 0), 0));
const planIterPts = computed(() => planIterAgg.value?.iterPoints ?? 0);
// 全选作用于当前页（其他页已选中的保留）
const planAllChecked = computed(() => planReqs.value.length > 0 && planReqs.value.every((r) => planSel.value.includes(r.id)));
function togglePlanReq(r) {
    const i = planSel.value.indexOf(r.id);
    if (i >= 0) {
        planSel.value.splice(i, 1);
        delete planEst.value[r.id];
    } else {
        planSel.value.push(r.id);
        planEst.value[r.id] = r.estimate || 0;
    }
}
function togglePlanAll() {
    planReqs.value.forEach((r) => togglePlanReq(r));
}
function openPlan(it) {
    planOpen.value = true; // 先开弹窗，候选与迭代点数异步回填
    planSel.value = [];
    planEst.value = {};
    planKw.value = '';
    planPage.value = 1;
    planIter.value = it;
    planReqs.value = [];
    planTotal.value = 0;
    planIterAgg.value = null;
    fetchPlanPool().catch(() => {});
    fetchPlanAgg().catch(() => {});
}
watch(planKw, () => {
    if (planPage.value === 1) fetchPlanPool().catch(() => {});
    else planPage.value = 1;
});
watch([planPage, planPageSize], () => fetchPlanPool().catch(() => {}));
async function confirmPlan() {
    if (!planSel.value.length) return;
    await api.planRequirements([...planSel.value], planIter.value);
    planOpen.value = false;
    ui.notifyRefresh();
}

// ===== 新增里程碑 =====
const newMsOpen = ref(false);
const newMsDate = ref('');
const newMsName = ref('');
function openNewMs() {
    newMsDate.value = '';
    newMsName.value = '';
    newMsOpen.value = true;
}
async function addMilestone() {
    if (!newMsDate.value || !newMsName.value.trim()) return;
    const it = detailIter.value;
    const milestones = [...(it.milestones || []), { d: newMsDate.value, n: newMsName.value.trim(), state: '' }];
    await api.updateIteration(it.code, { milestones });
    newMsOpen.value = false;
    await syncDetail();
}
async function removeMilestone(m) {
    const it = detailIter.value;
    const milestones = (it.milestones || []).filter((x) => x !== m);
    await api.updateIteration(it.code, { milestones });
    await syncDetail();
}

// ===== 迭代关联的需求（详情内列表：服务端分页）=====
const iterReqs = computed(() => iterReqRows.value);

// 将需求移出迭代：排期回退到待排期；未开工（首档之后的排期档）一并回退到首档
async function removeIterReq(r) {
    const patch = { iter: '待排期' };
    if (r.status === reqSecond.value) patch.status = reqFirst.value;
    await api.updateRequirement(r.id, patch);
    ui.notifyRefresh();
    await syncDetail();
}

// ===== 迭代复盘（三维度评分 + 改进项 + 总结） =====
const RETRO_DIMS = [
    { key: 'delivery', label: '交付节奏' },
    { key: 'collab', label: '团队协作' },
    { key: 'quality', label: '交付质量' }
];
const retroOpen = ref(false);
const retroCode = ref('');
const retroScores = reactive({ delivery: 0, collab: 0, quality: 0 });
const retroActions = ref([]);
const retroSummary = ref('');
const retroIsObj = (r) => !!r && typeof r === 'object'; // 旧复盘是字符串，新复盘是结构化对象
const retroLoading = ref(false);
function fillRetro(it) {
    if (retroIsObj(it?.retro)) {
        retroScores.delivery = it.retro.scores?.delivery || 0;
        retroScores.collab = it.retro.scores?.collab || 0;
        retroScores.quality = it.retro.scores?.quality || 0;
        retroActions.value = [...(it.retro.actions || [])];
        retroSummary.value = it.retro.summary || '';
    } else {
        retroScores.delivery = 0;
        retroScores.collab = 0;
        retroScores.quality = 0;
        retroActions.value = [];
        retroSummary.value = it?.retro || ''; // 字符串旧复盘预填到总结
    }
}
// 列表行不含复盘正文：打开弹窗先给反馈，复盘按迭代编号异步回填
async function openRetro(it) {
    retroCode.value = it.code;
    fillRetro(it);
    retroOpen.value = true;
    retroLoading.value = true;
    try {
        const full = await api.getIterationDetail(it.code);
        if (retroCode.value === it.code) fillRetro(full);
    } finally {
        retroLoading.value = false;
    }
}
async function saveRetro() {
    await api.saveRetro(retroCode.value, {
        scores: { ...retroScores },
        actions: retroActions.value,
        summary: retroSummary.value
    });
    retroOpen.value = false;
    ui.notifyRefresh();
    await syncDetail();
}

// ===== 日期工具（YYYY-MM-DD，按真实月长计算，跨月平移正确） =====
const parseDT = (dt) => {
    const [y, m, d] = dt.split('-').map(Number);
    return new Date(y, m - 1, d);
};
const fmtDT = (dt) => `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`;
const shiftDT = (md, n) => {
    const dt = parseDT(md);
    dt.setDate(dt.getDate() + n);
    return fmtDT(dt);
};
const diffDays = (a, b) => Math.round((parseDT(b) - parseDT(a)) / 86400000);
const todayStr = fmtDT(new Date());

// ===== 迭代时间线（窗口 = 所有迭代起止的并集，随项目动态） =====
const tlWindow = computed(() => {
    const ds = (timeline.value || [])
        .flatMap((it) => (it.range || '').split(' ~ '))
        .map((d) => d.trim())
        .filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d))
        .sort();
    return ds.length ? { start: ds[0], end: ds[ds.length - 1] } : null;
});
const tlTotalDays = computed(() => (tlWindow.value ? diffDays(tlWindow.value.start, tlWindow.value.end) + 1 : 1));
const dayIdx = (d) => (tlWindow.value && /^\d{4}-\d{2}-\d{2}$/.test(d) ? diffDays(tlWindow.value.start, d) : 0);
const pct = (idx) => ((idx / tlTotalDays.value) * 100).toFixed(2) + '%';
const drag = ref(null); // 甘特拖拽改期 { code, startX, delta, minD, maxD, dayPx, s, e }
const tlRows = computed(() =>
    [...(timeline.value || [])]
        .filter((it) => /^\d{4}-\d{2}-\d{2} ~ \d{4}-\d{2}-\d{2}$/.test((it.range || '').trim()))
        .sort((a, b) => dayIdx(a.range.split(' ~ ')[0]) - dayIdx(b.range.split(' ~ ')[0]))
        .map((it) => {
            const [s, e] = it.range.split(' ~ ');
            const si = dayIdx(s),
                ei = dayIdx(e);
            const off = drag.value && drag.value.code === it.code ? drag.value.delta : 0; // 拖拽中的跟手偏移
            const left = pct(si + off),
                width = pct(Math.max(1, ei - si + 1));
            return { ...it, left, width, mid: `calc(${left} + ${width} / 2)` };
        })
);
const dragRange = computed(() => (drag.value ? `${shiftDT(drag.value.s, drag.value.delta)} ~ ${shiftDT(drag.value.e, drag.value.delta)}` : ''));
const tlTicks = computed(() => {
    if (!tlWindow.value) return [];
    const span = tlTotalDays.value - 1;
    return Array.from({ length: 6 }, (_, i) => {
        const idx = Math.round((span * i) / 5);
        return { d: shiftDT(tlWindow.value.start, idx), left: pct(idx), edge: i === 0 };
    });
});
const todayLeft = computed(() => {
    if (!tlWindow.value) return null;
    const idx = diffDays(tlWindow.value.start, todayStr);
    if (idx < 0 || idx > tlTotalDays.value - 1) return null;
    return { left: `calc(120px + (100% - 120px) * ${(idx / tlTotalDays.value).toFixed(4)})` };
});
const mileLeft = (d) => pct(dayIdx(d));

// ===== 页头倒计时（active 迭代距结束天数） =====
const countdown = computed(() => {
    const it = (timeline.value || []).find((i) => i.state === iterActiveKey.value && (i.range || '').includes(' ~ '));
    if (!it) return null;
    const n = diffDays(todayStr, it.range.split(' ~ ')[1]);
    return { code: it.code, days: Math.max(0, n) };
});

// ===== 燃尽图（active 迭代真实数据：理想线 + 快照实际线） =====
// 画布宽 = 容器像素宽（v-chart-size 回写），1 用户单位 = 1 CSS px，线宽/字号即设计值
const CW = ref(700);
const setChartWidth = (px) => {
    CW.value = px;
};
// 容器极窄/隐藏时整体兜底到 320，坐标不会溢出画布
const W = computed(() => Math.max(320, CW.value));
const BH = 230,
    B_X0 = 40,
    B_Y0 = 22,
    B_Y1 = 190;
const burnView = computed(() => {
    const b = data.value?.burn;
    if (!b || !b.series?.length || !b.total) return null;
    const X1 = W.value - B_X0,
        Y0 = B_Y0,
        Y1 = B_Y1;
    const yMax = Math.max(15, Math.ceil(b.total / 15) * 15);
    const days = b.series.length;
    const pt = (i, v) => `${(B_X0 + ((X1 - B_X0) * i) / (days - 1 || 1)).toFixed(1)},${(Y1 - ((Y1 - Y0) * v) / yMax).toFixed(1)}`;
    const idealPts = b.series.map((p, i) => pt(i, p.ideal)).join(' ');
    const actualPts = b.series
        .map((p, i) => (p.actual == null ? null : pt(i, p.actual)))
        .filter(Boolean)
        .join(' ');
    const yTicks = [0, 1, 2, 3, 4].map((i) => ({ v: Math.round((yMax * i) / 4), y: Y1 - ((Y1 - Y0) * i) / 4 }));
    // 横轴刻度密度随画布宽度变化（每 ~110px 一个），宽屏不会只挤五个日期
    const xn = days < 2 ? 1 : Math.max(3, Math.min(days, Math.floor((X1 - B_X0) / 110)));
    const xTicks = Array.from({ length: xn }, (_, i) => {
        const idx = xn === 1 ? 0 : Math.round(((days - 1) * i) / (xn - 1));
        return { d: b.series[idx].d, x: +(B_X0 + ((X1 - B_X0) * idx) / (days - 1 || 1)).toFixed(1), anchor: i === 0 ? 'start' : i === xn - 1 ? 'end' : 'middle' };
    });
    const tIdx = b.series.findIndex((p) => p.d === todayStr);
    const todayX = tIdx >= 0 ? +(B_X0 + ((X1 - B_X0) * tIdx) / (days - 1 || 1)).toFixed(1) : null;
    const idealNow = b.series[Math.min(tIdx >= 0 ? tIdx : days - 1, days - 1)].ideal;
    const diff = b.remaining - idealNow;
    return {
        ...b,
        idealPts,
        actualPts,
        yTicks,
        xTicks,
        todayX,
        note: `当前剩余 ${b.remaining} 点 · ${diff === 0 ? '与理想持平' : diff > 0 ? `比理想多 ${diff} 点` : `比理想少 ${-diff} 点`} · 已完成 ${b.done}/${b.total} 点`
    };
});

// ===== 近 4 周负载趋势折线（迷你图，同样按容器像素宽 1:1 绘制）=====
const SW = ref(320);
const setSparkWidth = (px) => {
    SW.value = px;
};
const SparkW = computed(() => Math.max(200, SW.value));
const trend = computed(() => {
    const weeks = data.value?.capacity?.loadTrend?.weeks || [];
    const vals = weeks.map((w) => Number(w.hours) || 0);
    if (weeks.length < 2 || vals.every((v) => v === 0)) return null;
    const maxH = Math.max(...vals, 1);
    const n = weeks.length;
    const span = SparkW.value - 12; // 左右各留半个点，避免端点被画布裁掉
    const pts = vals.map((v, i) => ({ x: +(6 + (span * i) / (n - 1)).toFixed(1), y: +(140 - (120 * v) / maxH).toFixed(1) }));
    return {
        pts: pts.map((p) => `${p.x},${p.y}`).join(' '),
        area: `M${pts[0].x},160 L${pts.map((p) => `${p.x},${p.y}`).join(' L')} L${pts[pts.length - 1].x},160 Z`,
        circles: pts.map((p, i) => ({ ...p, label: `${weeks[i].week || weeks[i].label || ''} · ${vals[i]} 小时` }))
    };
});

// ===== 甘特拖拽改期（Pointer Events：鼠标 / 触屏 / 触控笔通用；终态锁定不可拖） =====
function onBarDown(e, row) {
    if (row.state === iterTerminal.value) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    const [s, en] = row.range.split(' ~ ');
    const si = dayIdx(s),
        ei = dayIdx(en);
    drag.value = {
        code: row.code,
        startX: e.clientX,
        delta: 0,
        minD: -si,
        maxD: tlTotalDays.value - 1 - ei,
        dayPx: e.currentTarget.parentElement.offsetWidth / tlTotalDays.value,
        s,
        e: en
    };
    window.addEventListener('pointermove', onBarMove);
    window.addEventListener('pointerup', onBarUp);
    window.addEventListener('pointercancel', onBarUp);
    e.preventDefault(); // 防止起手选中文字 / 触屏滚动
}
function onBarMove(e) {
    if (!drag.value) return;
    if (e.cancelable) e.preventDefault();
    const n = Math.round((e.clientX - drag.value.startX) / drag.value.dayPx);
    drag.value.delta = Math.max(drag.value.minD, Math.min(drag.value.maxD, n)); // 夹紧在刻度窗口内
}
async function onBarUp() {
    window.removeEventListener('pointermove', onBarMove);
    window.removeEventListener('pointerup', onBarUp);
    window.removeEventListener('pointercancel', onBarUp);
    const d = drag.value;
    if (!d || !d.delta) {
        drag.value = null;
        return;
    }
    await shiftIteration(d);
}
async function shiftIteration(d) {
    const it = timeline.value.find((x) => x.code === d.code);
    if (!it) {
        drag.value = null;
        return;
    }
    const ns = shiftDT(d.s, d.delta),
        ne = shiftDT(d.e, d.delta);
    const range = `${ns} ~ ${ne}`;
    const days = diffDays(ns, ne) + 1 + ' 天'; // 起止含头含尾
    const milestones = (it.milestones || []).map((m) => ({ ...m, d: shiftDT(m.d, d.delta) })); // 里程碑同步平移
    lastShift.value = { code: d.code, range: it.range, days: it.days, milestones: it.milestones || [] };
    await api.updateIteration(d.code, { range, days, milestones });
    drag.value = null; // 已落库，去掉跟手偏移直接显示新排期
    await fetchData();
}

// 拖拽改期可撤销（记录最近一次的原始排期）
const lastShift = ref(null);
async function undoShift() {
    const s = lastShift.value;
    if (!s) return;
    lastShift.value = null;
    await api.updateIteration(s.code, { range: s.range, days: s.days, milestones: s.milestones });
    await fetchData();
}

onUnmounted(() => {
    window.removeEventListener('pointermove', onBarMove);
    window.removeEventListener('pointerup', onBarUp);
    window.removeEventListener('pointercancel', onBarUp);
});

// ===== 里程碑（详情内点击切换完成态，当前节点不可点） =====
const msLabel = (s) => (s === 'done' ? '已完成' : s === 'now' ? '当前节点' : '未开始');
async function toggleMilestone(m) {
    const it = detailIter.value;
    if (!it) return;
    const milestones = (it.milestones || []).map((x) => (x === m ? { ...x, state: x.state === 'done' ? '' : 'done' } : x));
    await api.updateIteration(it.code, { milestones });
    await syncDetail();
}
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else-if="data">
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>迭代规划</h1>
                <div class="page-meta">规划、追踪和管理所有研发迭代</div>
            </div>
            <div class="page-right">
                <button class="ghost-btn" @click="exportCsv">导出排期</button>
                <div v-if="countdown" class="countdown">
                    {{ countdown.code }} · 距发布还有 <b>{{ countdown.days }} 天</b>
                </div>
                <div v-else class="countdown">暂无进行中迭代</div>
                <PageCreateButton type="iteration" />
            </div>
        </div>

        <!-- 统计条 -->
        <div class="stat-strip rise d2">
            <div v-for="s in data.stats" :key="s.k" class="stat">
                <div class="v">{{ s.v }}</div>
                <div class="k">{{ s.k }}</div>
            </div>
        </div>

        <!-- 当前迭代 -->
        <div class="iter-section rise d3">
            <div class="iter-section-title">
                当前迭代 <span class="num">{{ curTotal }}</span>
            </div>
            <div class="iter-list">
                <div v-for="it in curPaged" :key="it.code" class="iter-card">
                    <div>
                        <div class="iter-name">
                            {{ it.code }} · {{ it.name }}<small>{{ it.range }} · {{ it.days }}</small>
                        </div>
                    </div>
                    <span class="iter-state" :class="it.state" :style="opts.styleOf('iteration', it.state)">{{ itLabel(it) }}</span>
                    <div class="iter-bar">
                        <div class="iter-bar-track"><i class="iter-bar-fill" :style="{ width: it.pct + '%' }"></i></div>
                    </div>
                    <div class="iter-pct">{{ it.pct }}%</div>
                    <div class="iter-dates">{{ it.range }}</div>
                    <div class="iter-tasks">{{ it.done }}/{{ it.total }}</div>
                    <div class="iter-owner">
                        <span class="mini-ava ava" :class="mmap[it.owner]?.color">{{ mmap[it.owner]?.short || '?' }}</span
                        >{{ mmap[it.owner]?.name || '未指定' }}
                    </div>
                    <div class="iter-actions">
                        <button class="iter-btn" @click="openDetail(it)">详情</button>
                        <button class="iter-btn" @click="openPlan(it.code)">规划</button>
                    </div>
                </div>
            </div>
            <!-- 分页（服务端分页，total 取该状态下的迭代总数） -->
            <AppPager :total="curTotal" :page="curPage" :page-size="curPageSize" @update:page="curPage = $event" @update:pageSize="curPageSize = $event" />
        </div>

        <!-- 已发布 -->
        <div class="iter-section rise d4">
            <div class="iter-section-title">
                {{ iterName(iterTerminal) }} <span class="num">{{ relTotal }}</span>
            </div>
            <div class="iter-list">
                <div v-for="it in relPaged" :key="it.code" class="iter-card" :class="{ wrap: it.retro }">
                    <div>
                        <div class="iter-name">
                            {{ it.code }} · {{ it.name }}<small>{{ it.range }} · {{ iterName(iterTerminal) }}</small>
                        </div>
                    </div>
                    <span class="iter-state released" :style="opts.styleOf('iteration', iterTerminal)">{{ iterName(iterTerminal) }}</span>
                    <div class="iter-bar">
                        <div class="iter-bar-track"><i class="iter-bar-fill" style="width: 100%"></i></div>
                    </div>
                    <div class="iter-pct">100%</div>
                    <div class="iter-dates">{{ it.range }}</div>
                    <div class="iter-tasks">{{ it.done }}/{{ it.total }}</div>
                    <div class="iter-owner">
                        <span class="mini-ava ava" :class="mmap[it.owner]?.color">{{ mmap[it.owner]?.short || '?' }}</span
                        >{{ mmap[it.owner]?.name || '未指定' }}
                    </div>
                    <div class="iter-actions">
                        <span v-if="it.retro" class="retro-badge">已复盘</span>
                        <button class="iter-btn" @click="openDetail(it)">详情</button>
                        <button class="iter-btn" @click="openRetro(it)">复盘</button>
                    </div>
                    <!-- 复盘展示：字符串直接显示；对象显示三维评分点 + 总结 + 改进项计数 -->
                    <div v-if="it.retro" class="iter-retro">
                        <div v-if="!retroIsObj(it.retro)" class="rr-sum">{{ it.retro }}</div>
                        <template v-else>
                            <div class="rr-dims">
                                <span v-for="d in RETRO_DIMS" :key="d.key" class="rr-dim">
                                    <b>{{ d.label }}</b>
                                    <i v-for="n in 5" :key="n" class="rr-dot" :class="{ on: n <= (it.retro.scores?.[d.key] || 0) }"></i>
                                </span>
                            </div>
                            <div v-if="it.retro.summary" class="rr-sum">{{ it.retro.summary }}</div>
                            <div>
                                <span class="rr-act">改进项 {{ (it.retro.actions || []).length }} 项</span>
                            </div>
                        </template>
                    </div>
                </div>
            </div>
            <!-- 分页（服务端分页，total 取该状态下的迭代总数） -->
            <AppPager :total="relTotal" :page="relPage" :page-size="relPageSize" @update:page="relPage = $event" @update:pageSize="relPageSize = $event" />
        </div>

        <!-- 容量规划 -->
        <div class="capa-grid rise d5">
            <div class="capa-card">
                <div class="capa-head">
                    <span class="capa-title">{{ data.capacity.s24.title }}</span>
                    <span class="capa-role">{{ data.capacity.s24.role }}</span>
                </div>
                <div class="capa-stats">
                    <div v-for="r in data.capacity.s24.rows" :key="r.k" class="capa-row">
                        <span class="lbl">{{ r.k }}</span>
                        <div class="capa-bar"><i class="capa-fill" :style="{ width: r.pct + '%', background: r.color }"></i></div>
                        <span class="val">{{ r.pct }}%</span>
                    </div>
                </div>
                <div class="capa-remain">{{ data.capacity.s24.remain }}</div>
            </div>
            <div class="capa-card">
                <div class="capa-head">
                    <span class="capa-title">{{ data.capacity.s25.title }}</span>
                    <span class="capa-role">{{ data.capacity.s25.role }}</span>
                </div>
                <div class="capa-stats">
                    <div v-for="r in data.capacity.s25.rows" :key="r.k" class="capa-row">
                        <span class="lbl">{{ r.k }}</span>
                        <div class="capa-bar"><i class="capa-fill" :style="{ width: r.pct + '%', background: r.color }"></i></div>
                        <span class="val">{{ r.pct }}%</span>
                    </div>
                </div>
                <div class="capa-remain">{{ data.capacity.s25.remain }}</div>
            </div>
            <div class="capa-card">
                <div class="capa-head">
                    <span class="capa-title">团队总负载趋势</span>
                    <span class="capa-role">近 4 周</span>
                </div>
                <svg v-if="trend" v-chart-size="setSparkWidth" class="burndown-svg" :viewBox="'0 0 ' + SparkW + ' 160'">
                    <defs>
                        <linearGradient id="gtrend" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0" stop-color="var(--primary)" stop-opacity=".15" />
                            <stop offset="1" stop-color="var(--primary)" stop-opacity="0" />
                        </linearGradient>
                    </defs>
                    <path :d="trend.area" fill="url(#gtrend)" />
                    <polyline :points="trend.pts" fill="none" stroke="var(--primary)" stroke-width="1.5" />
                    <circle v-for="(p, i) in trend.circles" :key="i" :cx="p.x" :cy="p.y" r="3" fill="var(--primary)">
                        <title>{{ p.label }}</title>
                    </circle>
                </svg>
                <div v-else class="burn-empty">近 4 周暂无工时记录</div>
                <div class="burndown-note">{{ data.capacity.loadTrend.note }}</div>
            </div>
        </div>

        <!-- 燃尽图 -->
        <div class="card burndown-card rise d5">
            <div class="card-head">
                <span class="card-title">{{ burnView ? burnView.code + ' 燃尽图' : '迭代燃尽图' }}</span>
                <span v-if="burnView" class="card-extra">{{ burnView.start }} ~ {{ burnView.end }}</span>
            </div>
            <svg v-if="burnView" v-chart-size="setChartWidth" class="burndown-svg" :viewBox="'0 0 ' + W + ' ' + BH">
                <line
                    v-for="t in burnView.yTicks"
                    :key="'yl' + t.v"
                    :x1="B_X0"
                    :y1="t.y"
                    :x2="W - B_X0"
                    :y2="t.y"
                    stroke="var(--chart-grid)"
                    stroke-width="1"
                    :stroke-dasharray="t.v > 0 && t.v < burnView.yTicks[4].v ? '3 3' : undefined"
                />
                <text v-for="t in burnView.yTicks" :key="'yt' + t.v" x="30" :y="t.y + 4" fill="var(--t3)" text-anchor="end">{{ t.v }}</text>
                <text v-for="t in burnView.xTicks" :key="'xt' + t.d" :x="t.x" :y="BH - 14" fill="var(--t3)" :text-anchor="t.anchor">{{ t.d }}</text>
                <polyline :points="burnView.idealPts" fill="none" stroke="var(--chart-muted)" stroke-width="1.5" stroke-dasharray="6 4" />
                <polyline v-if="burnView.actualPts" :points="burnView.actualPts" fill="none" stroke="var(--primary)" stroke-width="2" stroke-linecap="round" />
                <line v-if="burnView.todayX != null" :x1="burnView.todayX" :y1="B_Y0" :x2="burnView.todayX" :y2="B_Y1" stroke="var(--red)" stroke-width="1" stroke-dasharray="3 3" opacity=".5" />
                <text v-if="burnView.todayX != null" :x="burnView.todayX" y="14" fill="var(--red)" text-anchor="middle">今天</text>
            </svg>
            <div class="burndown-legend">
                <span><i style="background: var(--chart-muted)"></i>理想燃尽</span>
                <span><i style="background: var(--primary)"></i>实际剩余</span>
                <span><i style="background: var(--red)"></i>今日</span>
            </div>
            <div v-if="burnView" class="burndown-note">{{ burnView.note }}</div>
            <div v-else class="burn-empty">暂无进行中的迭代，无法生成燃尽图</div>
        </div>

        <!-- 迭代时间线（甘特） -->
        <div class="card tl-card rise d5" :class="{ dragging: !!drag }">
            <div class="card-head">
                <span class="card-title">迭代时间线</span>
                <button v-if="lastShift" class="ghost-btn tl-undo" @click="undoShift">撤销改期 · {{ lastShift.code }}</button>
                <!-- data 只含容量/统计/燃尽聚合，迭代条数取甘特时间轴（timeline） -->
                <span class="card-extra">{{ tlWindow ? tlWindow.start + ' ~ ' + tlWindow.end + ' · ' + tlRows.length + ' 个迭代 · ' + tlTotalDays + ' 天' : '暂无迭代排期' }}</span>
            </div>
            <div class="tl-scale">
                <span class="tl-scale-spacer"></span>
                <div class="tl-scale-track">
                    <span v-for="t in tlTicks" :key="t.d" class="tl-tick" :class="{ edge: t.edge }" :style="{ left: t.left }">{{ t.d }}</span>
                </div>
            </div>
            <div class="tl-body">
                <div v-if="todayLeft" class="tl-today" :style="todayLeft">
                    <i>今日 {{ todayStr }}</i>
                </div>
                <div v-for="row in tlRows" :key="row.code" class="tl-row" :class="{ 'has-miles': (row.milestones || []).length }">
                    <div class="tl-label">
                        <b>{{ row.code }}</b
                        ><span>{{ row.name }}</span>
                    </div>
                    <div class="tl-track">
                        <template v-if="(row.milestones || []).length">
                            <span v-for="m in row.milestones" :key="m.n" class="tl-mile" :class="m.state || 'next'" :style="{ left: mileLeft(m.d) }" :title="m.d + ' ' + m.n"></span>
                        </template>
                        <span
                            class="tl-bar"
                            :class="[row.state, { grabbing: drag && drag.code === row.code }]"
                            :style="{ left: row.left, width: row.width }"
                            :title="row.state === iterTerminal ? row.range + '（已发布，不可拖动）' : row.range + '（拖动调整排期）'"
                            @pointerdown="onBarDown($event, row)"
                            >{{ row.range }}</span
                        >
                        <span v-if="drag && drag.code === row.code" class="tl-hint" :style="{ left: row.mid }">{{ dragRange }}</span>
                    </div>
                </div>
            </div>
            <div class="tl-legend">
                <span v-for="k in iterKeys" :key="k"><i class="lg" :class="k"></i>{{ iterName(k) }}</span>
                <span><i class="mg done"></i>里程碑已完成</span>
                <span><i class="mg now"></i>当前节点</span>
                <span><i class="mg next"></i>未开始</span>
            </div>
        </div>

        <!-- 迭代详情弹窗（760 双栏：主区 + 属性栏） -->
        <AppModal v-if="detailIter" :title="'迭代 ' + detailIter.code" width="760" @close="closeDetail">
            <div class="dtitle">{{ detailIter.code }} · {{ detailIter.name }}</div>
            <div class="dsection">
                <div class="k">完成进度</div>
                <div class="prog-row">
                    <div class="iter-bar" style="max-width: 420px">
                        <div class="iter-bar-track"><i class="iter-bar-fill" :style="{ width: detailIter.pct + '%' }"></i></div>
                    </div>
                    <span class="prog-pct">{{ detailIter.pct }}%</span>
                </div>
            </div>
            <div class="dsection">
                <div class="k">迭代目标</div>
                <div class="v">{{ detailIter.goal || '暂无目标' }}</div>
            </div>
            <div class="dsection">
                <div class="k">关联需求<span class="k-num">共 {{ iterReqTotal }} 个</span></div>
                <div v-if="iterReqs.length" class="req-list">
                    <div v-for="r in iterReqs" :key="r.id" class="req-row" title="查看需求详情" @click="goReq(r.id)">
                        <b class="req-id">{{ r.id }}</b>
                        <span class="req-title">{{ r.title }}</span>
                        <span class="req-status">{{ r.status }}</span>
                        <span class="req-owner">{{ mmap[r.owner]?.name || '—' }}</span>
                        <OptTag :value="r.prio" />
                        <span class="req-est">{{ r.estimate }} 点</span>
                        <button class="req-del" title="移出迭代" @click.stop="removeIterReq(r)">×</button>
                    </div>
                    <AppPager :total="iterReqTotal" :page="iterReqPage" :page-size="iterReqPageSize"
                        @update:page="iterReqPage = $event" @update:pageSize="iterReqPageSize = $event" />
                </div>
                <div v-else class="req-empty">暂无关联需求</div>
            </div>
            <div class="dsection">
                <div class="k">里程碑（点击切换完成状态）</div>
                <div v-if="(detailIter.milestones || []).length" class="ms-list">
                    <div v-for="m in detailIter.milestones" :key="m.n" class="ms-row" :class="{ done: m.state === 'done', now: m.state === 'now' }" @click="toggleMilestone(m)">
                        <span class="ms-dot"></span>
                        <b class="ms-d">{{ m.d }}</b>
                        <span class="ms-n">{{ m.n }}</span>
                        <span class="ms-st">{{ msLabel(m.state) }}</span>
                        <button class="ms-del" @click.stop="removeMilestone(m)" title="删除里程碑">×</button>
                    </div>
                </div>
                <div v-else class="ms-none">暂无里程碑</div>
                <button class="ghost-btn ms-add" @click="openNewMs()">+ 添加里程碑</button>
            </div>
            <div class="dsection">
                <div class="k">迭代复盘</div>
                <div v-if="!detailIter.retro" class="retro-empty">暂未复盘</div>
                <div v-else-if="!retroIsObj(detailIter.retro)" class="retro-text">{{ detailIter.retro }}</div>
                <template v-else>
                    <div class="rr-dims">
                        <span v-for="d in RETRO_DIMS" :key="d.key" class="rr-dim">
                            <b>{{ d.label }}</b>
                            <i v-for="n in 5" :key="n" class="rr-dot" :class="{ on: n <= (detailIter.retro.scores?.[d.key] || 0) }"></i>
                        </span>
                    </div>
                    <div v-if="detailIter.retro.summary" class="retro-text">{{ detailIter.retro.summary }}</div>
                    <div>
                        <span class="rr-act">改进项 {{ (detailIter.retro.actions || []).length }} 项</span>
                    </div>
                </template>
            </div>
            <template #rail>
                <div class="flow-bar">
                    <button v-for="s in iterKeys" :key="s" class="flow-btn" :class="{ on: detailIter.state === s }" @click="setIterState(s)">{{ iterName(s) }}</button>
                </div>
                <div v-if="iterErr" class="field-error">{{ iterErr }}</div>
                <div class="rail-kv">
                    <div class="item">
                        <div class="k">状态</div>
                        <div class="v">
                            <span class="iter-state" :class="detailIter.state" :style="opts.styleOf('iteration', detailIter.state)">{{ itLabel(detailIter) }}</span>
                        </div>
                    </div>
                    <div class="item">
                        <div class="k">起止日期</div>
                        <div class="v">{{ detailIter.range }}</div>
                    </div>
                    <div class="item">
                        <div class="k">实际完成</div>
                        <div class="v">{{ detailIter.actualEnd || '—' }}</div>
                    </div>
                    <div class="item">
                        <div class="k">任务完成</div>
                        <div class="v">{{ detailIter.done }} / {{ detailIter.total }}（{{ detailIter.pct }}%）</div>
                    </div>
                    <div class="item">
                        <div class="k">负责人</div>
                        <div class="v">{{ mmap[detailIter.owner]?.name || '未指定' }} · {{ mmap[detailIter.owner]?.role || '—' }}</div>
                    </div>
                </div>
            </template>
        </AppModal>

        <!-- 规划需求弹窗（候选服务端搜索 + 分页） -->
        <AppModal v-if="planOpen" :title="'规划需求进 ' + planIter" sub="勾选待排期需求，纳入该迭代" width="880" @close="planOpen = false">
            <div class="plan-tools">
                <input class="field-input" v-model="planKw" placeholder="搜索编号或标题" />
                <button class="ghost-btn" @click="togglePlanAll">{{ planAllChecked ? '取消全选本页' : '全选本页' }}</button>
                <span class="plan-sum">共 {{ planTotal }} 个待排期需求</span>
            </div>
            <div v-if="planReqs.length" class="plan-list">
                <div v-for="r in planReqs" :key="r.id" class="plan-row" @click="togglePlanReq(r)">
                    <input type="checkbox" :checked="planSel.includes(r.id)" tabindex="-1" @click.stop.prevent="togglePlanReq(r)" />
                    <span class="plan-rid">{{ r.id }}</span>
                    <span class="plan-title">{{ r.title }}</span>
                    <OptTag :value="r.prio" />
                    <span class="plan-est">{{ r.estimate }} 点</span>
                </div>
                <AppPager :total="planTotal" :page="planPage" :page-size="planPageSize"
                    @update:page="planPage = $event" @update:pageSize="planPageSize = $event" />
            </div>
            <div v-else class="plan-empty">{{ planKw ? `没有匹配「${planKw}」的待排期需求` : '没有待排期需求' }}</div>
            <template #footer>
                <span class="plan-sum">
                    已选 {{ planSel.length }} 个 · {{ planPts }} 点
                    <em>迭代当前 {{ planIterPts }} 点，纳入后约 {{ planIterPts + planPts }} 点</em>
                </span>
                <button class="ghost-btn" @click="planOpen = false">取消</button>
                <button class="btn" :disabled="!planSel.length" @click="confirmPlan">确认纳入</button>
            </template>
        </AppModal>

        <!-- 新增里程碑弹窗 -->
        <Teleport to="body">
            <div v-if="newMsOpen" class="picker-overlay">
                <div class="picker-dialog" style="width: 400px">
                    <div class="picker-head">
                        <span>添加里程碑 - {{ detailIter?.code }}</span>
                        <button class="picker-close" title="关闭" @click="newMsOpen = false">
                            <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                        </button>
                    </div>
                    <div style="padding: 0 20px 16px">
                        <div class="field"><label class="field-label">日期</label> <input type="date" class="field-input" v-model="newMsDate" /></div>
                        <div class="field"><label class="field-label">名称</label> <input class="field-input" v-model="newMsName" placeholder="如 全链路压测" @keydown.enter="addMilestone" /></div>
                    </div>
                    <div style="display: flex; justify-content: flex-end; gap: 10px; padding: 0 20px 16px">
                        <button class="ghost-btn" @click="newMsOpen = false">取消</button>
                        <button class="btn" :disabled="!newMsDate || !newMsName.trim()" @click="addMilestone">添加</button>
                    </div>
                </div>
            </div>
        </Teleport>

        <!-- 迭代复盘弹窗（三维度评分 + 改进项 + 总结） -->
        <AppModal v-if="retroOpen" :title="'迭代复盘 · ' + retroCode" sub="为本迭代打分，记录改进项与总结" @close="retroOpen = false">
            <div class="field">
                <label class="field-label">维度评分</label>
                <div v-for="d in RETRO_DIMS" :key="d.key" class="score-row">
                    <span class="score-label">{{ d.label }}</span>
                    <span class="score-dots">
                        <button v-for="n in 5" :key="n" type="button" class="score-dot" :class="{ on: n <= retroScores[d.key] }" @click="retroScores[d.key] = n">{{ n }}</button>
                    </span>
                    <span class="score-num">{{ retroScores[d.key] ? retroScores[d.key] + ' 分' : '未评分' }}</span>
                </div>
            </div>
            <div class="field">
                <AppChecklist v-model="retroActions" label="改进项" placeholder="输入改进项，回车添加" :addable="true" :removable="true" />
            </div>
            <div class="field">
                <label class="field-label">总结</label>
                <textarea class="field-textarea" v-model="retroSummary" rows="4" placeholder="做得好的、待改进的、下一步行动…"></textarea>
            </div>
            <template #footer>
                <button class="ghost-btn" @click="retroOpen = false">取消</button>
                <button class="btn" @click="saveRetro">保存复盘</button>
            </template>
        </AppModal>
    </template>
</template>

<style scoped>
.iter-section {
    margin-bottom: 24px;
}
.iter-section-title {
    font-size: 13px;
    font-weight:700;
    color: var(--t2);
    margin-bottom: 10px;
    display: flex;
    align-items: center;
    gap: 8px;
}
.iter-section-title .num {
    font-size:12px;
    background: var(--track);
    padding: 1px 6px;
    border-radius: 4px;
    font-weight:400;
}
.iter-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
}
.iter-card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 16px 20px;
    display: flex;
    align-items: center;
    gap: 20px;
}
.iter-name {
    font-size: 14px;
    font-weight:700;
    min-width: 180px;
}
.iter-name small {
    display: block;
    font-size: 12px;
    font-weight: 400;
    color: var(--t3);
    margin-top: 2px;
}
.iter-state {
    flex: none;
    font-size:12px;
    font-weight:400;
    padding: 2px 8px;
    border-radius: 6px;
}
.iter-state.active {
    background: var(--green-bg);
    color: var(--green);
}
.iter-state.planned {
    background: var(--track);
    color: var(--t2);
}
.iter-state.released {
    background: var(--primary-bg);
    color: var(--primary);
}
.iter-bar {
    flex: 1;
    max-width: 280px;
}
.iter-bar-track {
    height: 8px;
    border-radius: 4px;
    background: var(--track);
    overflow: hidden;
}
.iter-bar-fill {
    display: block;
    height: 100%;
    border-radius: 4px;
    background: var(--primary);
    animation: grow 1s ease-out both;
}
.iter-pct {
    font-size: 13px;
    font-weight:700;
    font-variant-numeric: tabular-nums;
    min-width: 42px;
    text-align: right;
}
.iter-dates {
    font-size: 12px;
    color: var(--t3);
    min-width: 150px;
}
.iter-tasks {
    font-size: 12px;
    color: var(--t2);
    min-width: 70px;
}
.iter-owner {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--t2);
    min-width: 80px;
}
.iter-actions {
    margin-left: auto;
    display: flex;
    gap: 6px;
    align-items: center;
}
.iter-btn {
    font-size: 12px;
    padding: 5px 10px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--card);
    color: var(--t2);
    cursor: pointer;
    font-family: inherit;
}
.iter-btn.primary {
    background: var(--primary-bg);
    border-color: var(--primary-bg);
    color: var(--primary);
    font-weight:400;
}
.retro-badge {
    font-size:12px;
    font-weight:400;
    padding: 2px 8px;
    border-radius: 6px;
    background: var(--green-bg);
    color: var(--green);
}

.capa-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 16px;
    margin-bottom: 16px;
}
.capa-card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 18px 20px;
}
.capa-head {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
}
.capa-title {
    font-size:14px;
    font-weight:700;
}
.capa-role {
    font-size:12px;
    color: var(--t3);
}
.capa-stats {
    display: flex;
    flex-direction: column;
    gap: 8px;
}
.capa-row {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size:13px;
    color: var(--t2);
}
.capa-row .lbl {
    width: 64px;
    flex: none;
    color: var(--t3);
}
.capa-bar {
    flex: 1;
    height: 6px;
    border-radius: 3px;
    background: var(--track);
    overflow: hidden;
}
.capa-fill {
    display: block;
    height: 100%;
    border-radius: 3px;
}
.capa-row .val {
    width: 52px;
    text-align: right;
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    color: var(--t1);
}
.capa-remain {
    margin-top: 10px;
    font-size: 12px;
    color: var(--t3);
    border-top: 1px solid var(--border);
    padding-top: 10px;
}

.burndown-card {
    margin-bottom: 16px;
}
.burndown-svg {
    width: 100%;
    height: auto;
    display: block;
}
.burndown-legend {
    display: flex;
    gap: 20px;
    margin-top: 10px;
    font-size: 12px;
    color: var(--t2);
}
.burndown-legend i {
    display: inline-block;
    width: 14px;
    height: 3px;
    border-radius: 1.5px;
    margin-right: 6px;
    vertical-align: 2px;
}
.burn-empty {
    height: 120px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 13px;
    color: var(--t3);
}
.burndown-note {
    font-size: 12px;
    color: var(--t3);
    margin-top: 10px;
}

/* ===== 迭代时间线（甘特） ===== */
.tl-card {
    margin-bottom: 16px;
}
.tl-scale {
    display: flex;
    margin-bottom: 6px;
}
.tl-scale-spacer {
    width: 120px;
    flex: none;
}
.tl-scale-track {
    position: relative;
    flex: 1;
    height: 14px;
}
.tl-tick {
    position: absolute;
    top: 0;
    transform: translateX(-50%);
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.tl-tick.edge {
    transform: none;
}
.tl-body {
    position: relative;
}
.tl-today {
    position: absolute;
    top: 0;
    bottom: 0;
    border-left: 1px dashed var(--red);
    z-index: 2;
    pointer-events: none;
}
.tl-today i {
    position: absolute;
    top: -1px;
    left: 4px;
    font-style: normal;
    font-size:12px;
    color: var(--red);
    white-space: nowrap;
    background: var(--card);
    padding: 0 4px;
    font-weight:400;
}
.tl-row {
    display: flex;
    align-items: center;
    padding: 5px 0;
}
.tl-row.has-miles {
    padding-top: 24px;
}
.tl-label {
    width: 120px;
    flex: none;
    padding-right: 12px;
}
.tl-label b {
    display: block;
    font-size:13px;
    font-weight:700;
}
.tl-label span {
    display: block;
    font-size:12px;
    color: var(--t3);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.tl-track {
    position: relative;
    flex: 1;
    height: 26px;
}
.tl-row.has-miles .tl-track {
    height: 34px;
}
.tl-bar {
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    height: 18px;
    border-radius: 4px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size:12px;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    overflow: hidden;
    z-index: 1;
}
.tl-bar.active {
    background: var(--primary-fill);
    color: #fff;
}
.tl-bar.released {
    background: var(--green-bg);
    border: 1px solid var(--green);
    color: var(--green);
}
.tl-bar.planned {
    background: var(--primary-bg);
    border: 1px dashed var(--primary-light);
    color: var(--primary);
}
.tl-mile {
    position: absolute;
    top: 0;
    width: 8px;
    height: 8px;
    background: var(--card);
    border: 1.5px solid var(--chart-muted);
    transform: translateX(-50%) rotate(45deg);
    z-index: 2;
}
.tl-mile.done {
    background: var(--primary);
    border-color: var(--primary);
}
.tl-mile.now {
    width: 11px;
    height: 11px;
    border: 1.5px solid var(--red);
    background: var(--card);
}
.tl-mile.next {
    background: var(--card);
    border-color: var(--chart-muted);
}
.tl-legend {
    display: flex;
    gap: 20px;
    margin-top: 14px;
    font-size: 12px;
    color: var(--t2);
    flex-wrap: wrap;
}
.tl-legend .lg {
    display: inline-block;
    width: 14px;
    height: 8px;
    border-radius: 2px;
    margin-right: 6px;
    vertical-align: 0;
}
.tl-legend .lg.active {
    background: var(--primary);
}
.tl-legend .lg.released {
    background: var(--green-bg);
    border: 1px solid var(--green);
}
.tl-legend .lg.planned {
    background: var(--primary-bg);
    border: 1px dashed var(--primary-light);
}
.tl-legend .mg {
    display: inline-block;
    width: 8px;
    height: 8px;
    margin-right: 6px;
    transform: rotate(45deg);
    background: var(--card);
    border: 1.5px solid var(--chart-muted);
}
.tl-legend .mg.done {
    background: var(--primary);
    border-color: var(--primary);
}
.tl-legend .mg.now {
    border-color: var(--red);
}

/* ===== 规划弹窗 ===== */
.plan-tools {
    display: flex;
    gap: 8px;
    margin-bottom: 8px;
}
.plan-tools .field-input {
    flex: 1;
}
.plan-list {
    max-height: 46vh;
    overflow: auto;
}
.plan-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 4px;
    border-bottom: 1px solid var(--border);
    cursor: pointer;
    font-size: 13px;
}
.plan-row:last-of-type {
    border-bottom: none;
}
.plan-row input[type='checkbox'] {
    flex: none;
    width: 15px;
    height: 15px;
    accent-color: var(--primary);
    cursor: pointer;
}
.plan-rid {
    flex: none;
    font-size: 12px;
    color: var(--t2);
    min-width: 48px;
    font-variant-numeric: tabular-nums;
}
.plan-title {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.plan-est {
    flex: none;
    font-size: 12px;
    color: var(--t3);
    min-width: 44px;
    text-align: right;
}
.plan-empty {
    padding: 32px 0;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}
.plan-sum {
    margin-right: auto;
    align-self: center;
    font-size:13px;
    color: var(--t2);
}
.plan-sum em {
    display: block;
    font-style: normal;
    font-size:12px;
    color: var(--t3);
    margin-top: 1px;
}
.btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
}

/* ===== 甘特拖拽改期 ===== */
.tl-card.dragging {
    user-select: none;
}
.tl-undo {
    margin-left: 8px;
    height: 26px;
    font-size: 12px;
}
.tl-bar {
    touch-action: none;
}
.tl-bar.active,
.tl-bar.planned {
    cursor: grab;
}
.tl-bar.grabbing {
    cursor: grabbing;
}
.tl-hint {
    position: absolute;
    top: calc(100% + 3px);
    transform: translateX(-50%);
    z-index: 6;
    font-size:12px;
    font-weight:400;
    white-space: nowrap;
    padding: 2px 8px;
    border-radius: 6px;
    background: var(--t1);
    color: var(--card);
    pointer-events: none;
    font-variant-numeric: tabular-nums;
}

/* ===== 迭代详情：进度 / 里程碑 / 复盘 ===== */
.prog-row {
    display: flex;
    align-items: center;
    gap: 10px;
}
.prog-pct {
    font-size:14px;
    font-weight:700;
    font-variant-numeric: tabular-nums;
}
.ms-list {
    display: flex;
    flex-direction: column;
}
.ms-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 0;
    font-size: 14px;
    line-height: 1.6;
    cursor: pointer;
}
.ms-row + .ms-row {
    border-top: 1px solid var(--border);
}
.ms-dot {
    flex: none;
    width: 9px;
    height: 9px;
    background: var(--card);
    border: 1.5px solid var(--chart-muted);
    transform: rotate(45deg);
}
.ms-row.done .ms-dot {
    background: var(--primary);
    border-color: var(--primary);
}
.ms-row.now .ms-dot {
    border-color: var(--red);
}
.ms-d {
    flex: none;
    font-size: 13px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
}
.ms-n {
    flex: 1;
    min-width: 0;
}
.ms-st {
    flex: none;
    font-size: 12px;
    color: var(--t3);
}
.ms-del {
    flex: none;
    width: 20px;
    height: 20px;
    border: none;
    background: none;
    padding: 0;
    color: var(--t3);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
    font-family: inherit;
    display: flex;
    align-items: center;
    justify-content: center;
}
.ms-del:hover {
    color: var(--red);
}
.ms-row.done .ms-st {
    color: var(--green);
}
.ms-row.now .ms-st {
    color: var(--red);
}
.ms-none,
.retro-empty,
.req-empty {
    font-size: 13px;
    color: var(--t3);
    padding: 2px 0;
}
.ms-add {
    margin-top: 8px;
    font-size:13px;
}

.req-list {
    display: flex;
    flex-direction: column;
}
/* 分区标题旁的真实总数（来自后端，不受分页窗口截断） */
.k-num {
    margin-left: 6px;
    font-size: 12px;
    font-weight: 400;
    color: var(--t3);
}
.req-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 6px;
    margin: 0 -6px;
    border-radius: 6px;
    font-size:14px;
    cursor: pointer;
    transition: background 0.14s var(--ease);
}
.req-row:hover {
    background: var(--surface-3);
}
.req-row + .req-row {
    border-top: 1px solid var(--border);
}
.req-id {
    flex: none;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
}
.req-title {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--t1);
}
.req-status {
    flex: none;
    font-size:12px;
    padding: 1px 7px;
    border-radius: 5px;
    background: var(--track);
    color: var(--t2);
}
.req-owner {
    flex: none;
    font-size: 12px;
    color: var(--t2);
    min-width: 48px;
    text-align: right;
}
.req-est {
    flex: none;
    font-size: 12px;
    color: var(--t3);
}
.req-del {
    flex: none;
    width: 20px;
    height: 20px;
    border: none;
    background: none;
    padding: 0;
    color: var(--t3);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
    font-family: inherit;
    display: flex;
    align-items: center;
    justify-content: center;
}
.req-del:hover {
    color: var(--red);
}
.retro-text {
    font-size: 14px;
    line-height: 1.8;
    color: var(--t1);
    margin-top: 6px;
}
.rail-kv {
    display: flex;
    flex-direction: column;
    gap: 11px;
}
.rail-kv .item .k {
    font-size:12px;
    color: var(--t3);
}
.rail-kv .item .v {
    font-size: 13px;
    margin-top: 2px;
}

/* ===== 复盘展示（已发布卡 / 详情通用） ===== */
.iter-card.wrap {
    flex-wrap: wrap;
}
.iter-retro {
    flex-basis: 100%;
    border-top: 1px solid var(--border);
    margin-top: 4px;
    padding-top: 10px;
}
.rr-dims {
    display: flex;
    gap: 18px;
    flex-wrap: wrap;
}
.rr-dim {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size:13px;
}
.rr-dim b {
    font-weight:700;
    color: var(--t1);
}
.rr-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--track);
}
.rr-dot.on {
    background: var(--primary);
}
.rr-sum {
    font-size:13px;
    color: var(--t2);
    line-height: 1.7;
    margin-top: 6px;
}
.rr-act {
    display: inline-block;
    font-size:12px;
    background: var(--track);
    color: var(--t2);
    padding: 1px 8px;
    border-radius: 5px;
    margin-top: 6px;
}

/* ===== 复盘表单：三维度评分 ===== */
.score-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 7px 0;
}
.score-label {
    width: 64px;
    flex: none;
    font-size: 13px;
}
.score-dots {
    display: flex;
    gap: 8px;
}
.score-dot {
    width: 22px;
    height: 22px;
    border: none;
    border-radius: 50%;
    background: var(--track);
    color: var(--t3);
    font-size:12px;
    cursor: pointer;
    font-family: inherit;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    font-variant-numeric: tabular-nums;
}
.score-dot.on {
    background: var(--primary-fill);
    color: #fff;
    font-weight:700;
}
.score-num {
    font-size: 12px;
    color: var(--t3);
}

/* 新增里程碑弹窗 */
.flow-bar {
    display: grid;
    grid-template-columns: 1fr 1fr 1fr;
    gap: 6px;
    margin-bottom: 12px;
}
.flow-btn {
    height: 30px;
    padding: 0 10px;
    font-size:12px;
    border: 1px solid var(--border);
    border-radius: 7px;
    background: var(--card);
    color: var(--t2);
    cursor: pointer;
    font-family: inherit;
    transition: border-color 0.15s var(--ease), color 0.15s var(--ease), background 0.15s var(--ease);
}
.flow-btn:hover {
    border-color: var(--primary-light);
    color: var(--primary);
}
.flow-btn.on {
    background: var(--grad-primary);
    border-color: transparent;
    color: #fff;
    font-weight:400;
    box-shadow: var(--shadow-primary);
}
.picker-overlay {
    position: fixed;
    z-index: 12000;
    inset: 0;
    background: var(--overlay);
    display: flex;
    align-items: center;
    justify-content: center;
}
.picker-dialog {
    width: 540px;
    max-height: 75vh;
    background: var(--card);
    border-radius: 12px;
    box-shadow: var(--shadow-lg);
    display: flex;
    flex-direction: column;
    overflow: hidden;
}
.picker-head {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight:700;
    padding: 16px 20px 10px;
}
.picker-close {
    margin-left: auto;
    width: 26px;
    height: 26px;
    flex: none;
    padding: 0;
    border: none;
    background: none;
    border-radius: 6px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--t3);
}
.picker-close svg {
    width: 14px;
    height: 14px;
    stroke: currentColor;
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
}
.picker-close:hover {
    background: var(--track);
    color: var(--t1);
}
.picker-pages button {
    width: 28px;
    height: 28px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--card);
    color: var(--t2);
    font-size: 16px;
    cursor: pointer;
    font-family: inherit;
    display: flex;
    align-items: center;
    justify-content: center;
}
.picker-pages button:disabled {
    opacity: 0.35;
    cursor: default;
}

@media (max-width: 1150px) {
    .capa-grid {
        grid-template-columns: 1fr;
    }
    .iter-card {
        flex-wrap: wrap;
        gap: 12px;
    }
    .iter-name {
        min-width: 120px;
    }
}
@media (max-width: 880px) {
    .iter-card {
        flex-direction: column;
        align-items: flex-start;
    }
    .iter-actions {
        margin-left: 0;
    }
}
</style>
