<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api';
import { useAuthStore } from '../stores/auth';
import { useUiStore } from '../stores/ui';
import LoadingMask from '../components/LoadingMask.vue';
import AppModal from '../components/AppModal.vue';
import AppConfirm from '../components/AppConfirm.vue';
import AppEditor from '../components/AppEditor.vue';
import AppUpload from '../components/AppUpload.vue';
import AppComments from '../components/AppComments.vue';
import ActivityTimeline from '../components/ActivityTimeline.vue';
import AppPager from '../components/AppPager.vue';
import BoardColumnList from '../components/BoardColumnList.vue';
import OptTag from '../components/OptTag.vue';
import { useBoardColumns } from '../composables/useBoardColumns';
import { useOptionsStore } from '../stores/options';
import { useMembersStore } from '../stores/members';
import { usePermStore } from '../stores/perm';
import { useIterationsStore } from '../stores/iterations';
import PageCreateButton from '../components/PageCreateButton.vue';

const opts = useOptionsStore(); // 项目设置里的选项配置（优先级/严重度/任务类型/标签）
const members = useMembersStore(); // 成员映射（处理人/头像）
const iters = useIterationsStore(); // 迭代筛选候选（共享 store）
const perm = usePermStore();// 删除缺陷需「缺陷追踪」管理级（与后端 DELETE 接口同口径）
const canManage = computed(() => perm.canManage('缺陷追踪'));
const canWrite = computed(() => perm.canWrite('缺陷追踪'));

const ui = useUiStore();
const CURRENT_USER = useAuthStore().user?.id ?? '';
const route = useRoute();
const router = useRouter();
const loading = ref(true);
const stats = ref(null);
const mmap = computed(() => members.map); // 成员映射（共享 store）

async function fetchData() {
    await members.load();
    // 看板按列窗口取数，统计与趋势由后端按全量口径算（不受列窗口截断）
    const [, s, t] = await Promise.all([reload(), api.getDefectStats(), api.getDefectTrend()]);
    stats.value = s;
    trend.value = t;
    if (view.value === 'list') await fetchList();
}

// ===== 缺陷趋势（近 14 天：新增/关闭/存量，后端真实数据）=====
// 画布宽 = 容器像素宽（v-chart-size 回写），1 用户单位 = 1 CSS px，线宽/字号即设计值
const trend = ref(null);
const CW = ref(700);
const setChartWidth = (px) => {
    CW.value = px;
};
const GH = 200;
// 画布宽 = 容器像素宽；容器极窄/隐藏时整体兜底到 320，坐标不会溢出画布
const TW = computed(() => {
    const w = Math.max(320, CW.value);
    return { w, x0: 40, x1: w - 40, y0: 24, y1: 165 };
});
const trendMax = computed(() => {
    if (!trend.value) return 10;
    const vals = [...trend.value.opened, ...trend.value.closed, ...trend.value.stock];
    const raw = Math.max(...vals, 1);
    // 刻度取 5 的倍数向上取整，避免顶部贴边
    return Math.max(5, Math.ceil(raw / 5) * 5);
});
const tx = (i, n) => TW.value.x0 + (i * (TW.value.x1 - TW.value.x0)) / (n - 1 || 1);
const ty = (v) => TW.value.y1 - (v / trendMax.value) * (TW.value.y1 - TW.value.y0);
const trendLine = (arr) =>
    arr.map((v, i) => `${tx(i, arr.length)},${ty(v)}`).join(' ');
const trendTicks = computed(() => {
    const max = trendMax.value;
    return [0, Math.round(max / 2), max];
});
// 横轴日期：按画布宽度决定密度（每 ~90px 一个刻度），宽屏不再只显示首尾三个
const trendLabels = computed(() => {
    const days = trend.value?.days || [];
    if (!days.length) return [];
    const n = days.length < 2 ? 1 : Math.max(2, Math.min(days.length, Math.floor((TW.value.x1 - TW.value.x0) / 90)));
    const idxs = n === 1 ? [0] : Array.from({ length: n }, (_, k) => Math.round((k * (days.length - 1)) / (n - 1)));
    return idxs.map((i, k) => ({ d: days[i], x: tx(i, days.length), anchor: k === 0 ? 'start' : k === n - 1 ? 'end' : 'middle' }));
});
onMounted(async () => {
    // 深链条件先落到筛选态，首次取数即按条件下推服务端
    if (route.query.iter) fIter.value = route.query.iter;
    if (route.query.module) fModule.value = route.query.module;
    if (route.query.status) fStatus.value = route.query.status;
    if (route.query.prio) fPrio.value = route.query.prio;
    if (route.query.owner) fOwner.value = route.query.owner;
    if (route.query.q) fKw.value = route.query.q;
    if (route.query.sort) sortKey.value = route.query.sort;
    if (route.query.view === 'list') view.value = 'list';
    await fetchData();
    if (route.query.focus) openDetail(route.query.focus);
    loading.value = false;
});
watch(() => ui.refreshTick, () => fetchData().catch(() => {}));

const mmapArr = computed(() => Object.values(mmap.value));

// ===== 筛选（状态项来自项目设置 → 工作流 → 缺陷工作流）=====
const defStatuses = computed(() => opts.defectStatuses); // [{key,name,color}]，顺序即流转顺序
const defName = (k) => opts.defName(k); // 状态展示名（被删掉的历史 key 回退 key 本身）
const defStyle = (k) => opts.defStyle(k); // 状态胶囊配色（按配置色渲染）
const fStatus = ref('');
const fPrio = ref('');
const fIter = ref('');
const fModule = ref('');
const fOwner = ref('');
const fKw = ref('');
const prioOptions = computed(() => [{ value: '', label: '全部' }, ...opts.priorities.map((p) => ({ value: p.key, label: p.name }))]);
// 原生 option 放不了色点，用下拉左侧小点回显当前选中优先级
const prioColor = computed(() => opts.priorities.find((p) => p.key === fPrio.value)?.color || 'transparent');
const filterActive = computed(() => fStatus.value !== '' || fPrio.value !== '' || fIter.value !== '' || fModule.value !== '' || fOwner.value !== '' || fKw.value.trim() !== '');
// ===== 双视图（看板 / 列表）=====
const view = ref('board');

const sortKey = ref('default');
// 筛选与排序条件下推服务端：看板按列窗口取数，列表按页取数
const filterParams = computed(() => ({
    q: fKw.value.trim(),
    status: fStatus.value,
    prio: fPrio.value,
    owner: fOwner.value,
    iter: fIter.value,
    module: fModule.value,
    sort: sortKey.value
}));
const { loadingAll, reload, refresh, loadMore, moveLocal, find: findLoaded, colOf, totalCount } = useBoardColumns(
    (limit) => api.getDefectBoardColumns(filterParams.value, limit),
    (status, offset, limit) => api.getDefectBoardColumn(status, offset, filterParams.value, limit)
);

const statusOptions = computed(() => [
    { value: '', label: `全部 ${totalCount()}` },
    ...defStatuses.value.map((s) => ({
        value: s.key,
        label: `${s.name} ${colOf(s.key)?.total ?? stats.value?.counts?.[s.key] ?? 0}`
    }))
]);

// ===== 列表视图：服务端分页 =====
const listRows = ref([]);
const listTotal = ref(0);
const page = ref(1);
const pageSize = ref(10);
const paged = computed(() => listRows.value);
async function fetchList() {
    const r = await api.getDefectsPage(filterParams.value, page.value, pageSize.value);
    listRows.value = r.list || [];
    listTotal.value = r.total || 0;
    if (!listRows.value.length && page.value > 1) page.value = 1; // 删除后页码越界回收
}
watch([fStatus, fPrio, fIter, fModule, fOwner, fKw, sortKey], () => {
    page.value = 1;
    fetchData().catch(() => {});
}); // 筛选/排序变化：回到第一页并重取列窗口与列表
watch([page, pageSize], () => {
    if (view.value === 'list') fetchList().catch(() => {});
});
watch(view, (v) => {
    if (v === 'list') fetchList().catch(() => {});
});

// 迭代候选取真实迭代（共享 store），不再从已加载缺陷里反推
const iterOptions = computed(() => iters.options);

// 看板列由缺陷工作流配置驱动：列头 key/name/color 取自 workflow.defectColumns，卡片取该列已加载窗口
const boardColumns = computed(() =>
    defStatuses.value.map((s) => ({
        key: s.key,
        color: s.color,
        name: s.name,
        col: colOf(s.key) || { key: s.key, total: 0, items: [], hasMore: false, loading: false }
    }))
);

// 顶部统计条：按配置的前三个状态计数（后端 /defects/stats 按配置返回 counts）
const statCards = computed(() => defStatuses.value.slice(0, 3));
const statCount = (k) => stats.value?.counts?.[k] ?? 0;
// 关闭率 / 近 14 天新增：用后端全量统计口径，不受看板列窗口与列表分页影响
const defTerminal = computed(() => opts.terminalOf('defect'));
const grandTotal = computed(() => stats.value?.total ?? 0);
const closedCount = computed(() => stats.value?.counts?.[defTerminal.value] ?? 0);
const closeRate = computed(() => (grandTotal.value ? ((closedCount.value / grandTotal.value) * 100).toFixed(1) : '0.0'));
const pctOf = (n) => (grandTotal.value ? Math.round((n / grandTotal.value) * 1000) / 10 : 0);
const recentOpened = computed(() => (trend.value?.opened || []).reduce((a, b) => a + b, 0));

// ===== 导出 CSV（后端按当前筛选生成全量明细，不受列窗口与分页影响）=====
const exporting = ref(false);
async function exportCsv() {
    if (exporting.value) return;
    exporting.value = true;
    try {
        const blob = await api.exportDefects(filterParams.value);
        const url = URL.createObjectURL(blob);
        const a = Object.assign(document.createElement('a'), { href: url, download: 'defects.csv' });
        a.click();
        URL.revokeObjectURL(url);
    } finally {
        exporting.value = false;
    }
}

// ===== 看板拖拽（跨列流转状态 + 同列上下排序）=====
const dragId = ref(null);
const dragOver = ref(''); // 悬停中的列（高亮）
const dropRef = ref(''); // 落点参照卡片 id（空 = 追加到列尾）
const dropAfter = ref(false); // 落在参照卡片的下方
function onDragStart(e, d) {
    // 必须写入 dataTransfer：否则浏览器把拖拽「半启动」，卡片按下再微移就吞掉 click，详情弹窗打不开
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', d.id);
    dragId.value = d.id;
}
function onCardDragOver(e, d) {
    if (!dragId.value || d.id === dragId.value) return;
    const r = e.currentTarget.getBoundingClientRect();
    dropRef.value = d.id;
    dropAfter.value = e.clientY > r.top + r.height / 2;
}
function resetDrag() {
    dragId.value = null;
    dragOver.value = '';
    dropRef.value = '';
    dropAfter.value = false;
}
async function onDrop(colKey) {
    const id = dragId.value;
    const anchor = dropRef.value === id ? '' : dropRef.value;
    const after = dropAfter.value;
    resetDrag();
    if (!id) return;
    const moved = findLoaded(id);
    if (!moved) return;
    const fromKey = moved.status;
    moveLocal(id, colKey, anchor, after); // 先落位给即时反馈，服务端结果回来后校正
    // 跨列先走领域 update（保留状态流转校验与动态），再按锚点落位
    try {
        if (fromKey !== colKey) await api.updateDefect(id, { status: colKey });
        await api.moveCard('defect', colKey, id, anchor, after);
    } finally {
        await refresh().catch(() => {}); // 被服务端拒绝（非法流转/无权限）时也要回到真实顺序
    }
}

// ===== 详情 / 编辑 =====
const detailId = ref(null);
// 深链 focus 可能不在任何列窗口里，按 id 单独补拉
const extraDefects = ref({});
const detail = computed(() => findLoaded(detailId.value) || extraDefects.value[detailId.value] || null);
async function loadExtra(id) {
    if (!id || findLoaded(id) || extraDefects.value[id]) return;
    try {
        extraDefects.value = { ...extraDefects.value, [id]: await api.getDefectDetail(id) };
    } catch {
        /* 无权限或已删除：详情保持空态 */
    }
}
const editing = ref(false);
const confirmDel = ref(false);
const editForm = reactive({
    title: '',
    env: '',
    prio: '',
    severity: '一般',
    owner: '',
    moduleId: '',
    reporterId: '',
    reporter: '',
    introducedBy: '',
    defectType: '功能',
    rootCause: '',
    fixedIn: '',
    foundIn: '',
    deadline: '',
    desc: ''
});
// 缺陷质量归因字典：类型用于区分缺陷性质，根因用于复盘系统性环节
const DEFECT_TYPES = ['功能', '性能', '兼容性', '安全', '数据', '体验'];
const ROOT_CAUSES = ['', '需求遗漏', '设计缺陷', '编码错误', '测试遗漏', '环境问题'];
const editErr = ref('');

function openDetail(d) {
    detailId.value = typeof d === 'string' ? d : d.id;
}
function startEdit() {
    const dl = detail.value.deadline;
    Object.assign(editForm, {
        title: detail.value.title,
        env: detail.value.env,
        prio: detail.value.prio,
        severity: detail.value.severity,
        owner: detail.value.owner,
        moduleId: detail.value.moduleId ?? '',
        reporterId: detail.value.reporterId ?? '',
        reporter: detail.value.reporter || '',
        introducedBy: detail.value.introducedBy ?? '',
        defectType: detail.value.defectType || '功能',
        rootCause: detail.value.rootCause || '',
        fixedIn: detail.value.fixedIn || '',
        foundIn: detail.value.foundIn,
        deadline: dl && dl !== '—' ? dl.replace(' ', 'T').slice(0, 16) : '',
        desc: detail.value.desc
    });
    editErr.value = '';
    editing.value = true;
}
async function saveEdit() {
    if (!editForm.title.trim()) {
        editErr.value = '标题不能为空';
        return;
    }
    const dl = editForm.deadline;
    const payload = { ...editForm, deadline: dl ? dl.replace('T', ' ') + ':00' : '' };
    // 内部报告人以成员为准（后端按 reporterId 取姓名），外部反馈人只保留手填展示名
    if (payload.reporterId) payload.reporter = '';
    await api.updateDefect(detailId.value, payload);
    editing.value = false;
    await fetchData();
}

// ===== 状态流转 / 删除 =====
const FLOW = computed(() => defStatuses.value.map((s) => s.key)); // 流转顺序 = 配置顺序
async function setStatus(s) {
    if (!detail.value || detail.value.status === s) return;
    await api.updateDefect(detailId.value, { status: s });
    await fetchData();
}
async function doDelete() {
    const id = detailId.value;
    confirmDel.value = false;
    detailId.value = null;
    editing.value = false;
    await api.deleteDefect(id);
    ui.notifyRefresh();
}

// ===== 关注（即时反馈，再后步刷新）=====
const watching = ref(false);
watch(
    detail,
    (d) => {
        watching.value = !!d?.watchers?.includes(CURRENT_USER);
    },
    { immediate: true }
);
async function doWatch() {
    if (!detailId.value) return;
    watching.value = !watching.value;
    await api.toggleWatch('defect', detailId.value);
    await fetchData();
}

// ===== 附件（AppUpload 受控，diff 后逐个持久化并重拉实体）=====
const attachList = ref([]);
const attachIds = ref([]); // 上次同步到服务端的附件 id
const attachSaving = ref(false);
const attachErr = ref('');
watch(detailId, (id) => {
    loadExtra(id);
    loadPool();
});
// 详情可能是按 id 补拉的（深链/关联跳转），卡片本体到位后再初始化附件编辑态
watch(detail, (d) => {
    attachList.value = d ? [...d.attachments] : [];
    attachIds.value = attachList.value.map((x) => x.id);
});
async function onAttachChange() {
    const now = attachList.value;
    const nowIds = now.map((x) => x.id);
    const added = now.filter((x) => !attachIds.value.includes(x.id));
    const removed = attachIds.value.filter((id) => !nowIds.includes(id));
    if (!added.length && !removed.length) return;
    const bid = detailId.value;
    attachSaving.value = true;
    attachErr.value = '';
    try {
        for (const it of added) await api.saveAttachment('defect', bid, it);
        for (const id of removed) await api.removeAttachment('defect', bid, id);
    } catch (e) {
        attachErr.value = '附件上传失败：' + (e?.message || '请稍后重试');
    } finally {
        attachSaving.value = false;
        await fetchData().catch(() => {});
        attachList.value = detail.value ? [...detail.value.attachments] : [];
        attachIds.value = attachList.value.map((x) => x.id);
    }
}

// ===== 关联缺陷 =====
const REL_LABEL = { duplicate: '重复', blocks: '阻塞', regress: '回归' };
const relNewType = ref('duplicate');
const relNewTarget = ref('');
// 关联候选与标题回显需要跨列的缺陷池：打开详情时按需拉最近 200 条（与旧版候选口径一致）
const pool = ref([]);
let poolLoading = null;
function loadPool() {
    if (pool.value.length || poolLoading) return;
    poolLoading = api
        .getDefects()
        .then((rows) => (pool.value = rows))
        .catch(() => {})
        .finally(() => (poolLoading = null));
}
const relCandidates = computed(() => {
    const d = detail.value;
    if (!d) return [];
    return pool.value.filter((x) => x.id !== d.id && !d.relates.some((r) => r.target === x.id));
});
const relTitle = (id) => pool.value.find((x) => x.id === id)?.title || findLoaded(id)?.title || extraDefects.value[id]?.title || '';
async function addRelate() {
    if (!relNewTarget.value) return;
    await api.addRelate(detailId.value, { type: relNewType.value, target: relNewTarget.value });
    relNewTarget.value = '';
    await fetchData();
}
async function removeRelate(rid) {
    await api.removeRelate(detailId.value, rid);
    await fetchData();
}
// 点击关联目标 → 深链到目标缺陷（已在当前页时直接切换）
function focusDefect(id) {
    openDetail(id);
    router.push({ path: '/defects', query: { focus: id } }).catch(() => {});
}

// ===== 回归验证 =====
const verResult = ref('通过');
const verNote = ref('');
async function addVerify() {
    await api.addVerifyLog(detailId.value, { result: verResult.value, note: verNote.value.trim() });
    verNote.value = '';
    await fetchData();
}

// ===== 列表批量操作 =====
const selected = ref([]);
// 全选作用于当前页，且不清掉其他页已选中的（跨页保留选择）
const allChecked = computed(() => paged.value.length > 0 && paged.value.every((d) => selected.value.includes(d.id)));
function toggleAll(e) {
    const ids = paged.value.map((d) => d.id);
    if (e.target.checked) selected.value = [...new Set([...selected.value, ...ids])];
    else selected.value = selected.value.filter((id) => !ids.includes(id));
}
const bulkStatus = ref('');
const bulkOwner = ref('');
const bulkModule = ref('');
const confirmBulkDel = ref(false);
async function bulkSetStatus() {
    if (!bulkStatus.value) return;
    await api.bulkUpdate('defect', selected.value, { status: bulkStatus.value });
    bulkStatus.value = '';
    selected.value = [];
}
async function bulkSetOwner() {
    if (!bulkOwner.value) return;
    await api.bulkUpdate('defect', selected.value, { owner: bulkOwner.value });
    bulkOwner.value = '';
    selected.value = [];
}
async function bulkSetModule() {
    if (!bulkModule.value) return;
    await api.bulkUpdate('defect', selected.value, { moduleId: bulkModule.value === '__none' ? null : bulkModule.value });
    bulkModule.value = '';
    selected.value = [];
}
async function bulkDelete() {
    confirmBulkDel.value = false;
    const ids = [...selected.value];
    selected.value = [];
    for (const id of ids) await api.deleteDefect(id);
    ui.notifyRefresh();
}

// ===== 状态同步到 URL =====
function syncQuery() {
    const q = {};
    if (fStatus.value) q.status = fStatus.value;
    if (fPrio.value) q.prio = fPrio.value;
    if (fIter.value) q.iter = fIter.value;
    if (fModule.value) q.module = fModule.value;
    if (fOwner.value) q.owner = fOwner.value;
    if (fKw.value.trim()) q.q = fKw.value.trim();
    if (sortKey.value !== 'default') q.sort = sortKey.value;
    if (view.value === 'list') q.view = 'list';
    if (detailId.value) q.focus = detailId.value;
    router.replace({ path: route.path, query: q }).catch(() => {});
}
watch([fStatus, fPrio, fIter, fModule, fOwner, fKw, sortKey, view, detailId], syncQuery);
watch(
    () => route.query,
    (q) => {
        if (q.focus && q.focus !== detailId.value) detailId.value = q.focus;
        if (q.iter !== undefined && q.iter !== fIter.value) fIter.value = q.iter || '';
    },
    { deep: true }
);
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else-if="stats">
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <div class="title-row">
                    <h1>缺陷追踪</h1>
                    <div class="view-tabs">
                        <button :class="{ on: view === 'board' }" @click="view = 'board'">看板</button>
                        <button :class="{ on: view === 'list' }" @click="view = 'list'">列表</button>
                    </div>
                </div>
                <div class="page-meta">
                    管理所有缺陷的生命周期
                    <template v-if="loadingAll"> · 刷新中…</template>
                </div>
            </div>
            <div class="page-right">
                <button class="ghost-btn" @click="exportCsv">导出</button>
                <PageCreateButton type="defect" />
            </div>
        </div>

        <!-- 统计条（前三个状态来自缺陷工作流配置，第四张为关闭率）；看板模式只看板，统计留给列表 -->
        <div v-if="view !== 'board'" class="stat-strip rise d2">
            <div v-for="(s, i) in statCards" :key="s.key" class="stat" :class="{ danger: i === 0, warn: i === 1 }">
                <div class="v">{{ statCount(s.key) }}</div>
                <div class="k">{{ s.name }}</div>
                <div v-if="i === 0" class="sub">{{ stats.openPrio }}</div>
                <div v-else-if="i === 1" class="sub">占全部 {{ pctOf(statCount(s.key)) }}%</div>
                <div v-else class="sub">近 14 天新增 {{ recentOpened }}</div>
            </div>
            <div class="stat ok">
                <div class="v">{{ closeRate }}%</div>
                <div class="k">累计关闭率</div>
                <div class="sub">关闭 {{ closedCount }} / 共 {{ grandTotal }}</div>
            </div>
        </div>

        <!-- 缺陷趋势 -->
        <div v-if="view !== 'board'" class="card trend-card rise d3">
            <div class="card-head">
                <span class="card-title">缺陷趋势</span>
                <span class="card-extra">近 14 天</span>
            </div>
            <svg v-chart-size="setChartWidth" class="trend-svg" :viewBox="'0 0 ' + TW.w + ' ' + GH">
                <line :x1="TW.x0" :y1="TW.y0" :x2="TW.x0" :y2="TW.y1" stroke="var(--chart-grid)" stroke-width="1" />
                <line :x1="TW.x0" :y1="TW.y1" :x2="TW.x1" :y2="TW.y1" stroke="var(--chart-grid)" stroke-width="1" />
                <line :y1="ty(trendTicks[1])" :y2="ty(trendTicks[1])" :x1="TW.x0" :x2="TW.x1" stroke="var(--chart-grid)" stroke-width="1" stroke-dasharray="3 3" />
                <text v-for="t in trendTicks" :key="'t' + t" x="30" :y="ty(t) + 4" fill="var(--t3)" text-anchor="end">{{ t }}</text>
                <text v-for="lb in trendLabels" :key="lb.d" :x="lb.x" :y="GH - 10" fill="var(--t3)" :text-anchor="lb.anchor">{{ lb.d }}</text>
                <polyline :points="trendLine(trend?.opened || [])" fill="none" stroke="var(--red)" stroke-width="2" />
                <polyline :points="trendLine(trend?.closed || [])" fill="none" stroke="var(--green)" stroke-width="2" />
                <polyline :points="trendLine(trend?.stock || [])" fill="none" stroke="var(--primary)" stroke-width="2" stroke-dasharray="5 3" />
            </svg>
            <div class="trend-legend">
                <span><i style="background: var(--red)"></i>新增</span>
                <span><i style="background: var(--green)"></i>关闭</span>
                <span><i style="background: var(--primary)"></i>存量</span>
            </div>
        </div>

        <!-- 筛选栏 -->
        <div class="filter-bar rise d4">
            <div class="filter-item">
                <span class="filter-label">状态</span>
                <select class="filter-select" v-model="fStatus">
                    <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
                </select>
            </div>
            <div class="filter-item">
                <span class="filter-label">优先级</span>
                <i v-if="fPrio" class="prio-dot" :style="{ background: prioColor }"></i>
                <select class="filter-select" v-model="fPrio">
                    <option v-for="o in prioOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
                </select>
            </div>
            <div class="filter-item">
                <span class="filter-label">模块</span>
                <select class="filter-select" v-model="fModule">
                    <option value="">全部</option>
                    <option v-for="m in opts.modules" :key="m.id" :value="String(m.id)">{{ m.name }}</option>
                </select>
            </div>
            <div class="filter-item">
                <span class="filter-label">当前处理人</span>
                <select class="filter-select" v-model="fOwner">
                    <option value="">全部</option>
                    <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                </select>
            </div>
            <div class="filter-item">
                <span class="filter-label">迭代</span>
                <select class="filter-select" v-model="fIter">
                    <option value="">全部</option>
                    <option v-for="o in iterOptions" :key="o.code" :value="o.code">{{ o.label }}</option>
                </select>
            </div>
            <div class="filter-item">
                <span class="filter-label">关键字</span>
                <input class="field-input f-search" v-model="fKw" placeholder="搜索编号 / 标题 / 环境…" />
            </div>
            <div class="filter-item">
                <span class="filter-label">排序</span>
                <select class="filter-select" v-model="sortKey">
                    <option value="default">默认</option>
                    <option value="deadline">修复期限</option>
                    <option value="prio">优先级</option>
                    <option value="id">编号</option>
                </select>
            </div>
        </div>

        <!-- 看板视图 -->
        <div v-if="view === 'board'" class="board rise d5">
            <div
                v-for="col in boardColumns"
                :key="col.key"
                class="bcol"
                :class="{ dropping: dragOver === col.key }"
                @dragover.prevent="dragOver = col.key"
                @dragleave="dragOver = ''"
                @drop.prevent="onDrop(col.key)"
            >
                <div class="col-head">
                    <span class="col-dot" :style="{ background: col.color }"></span>
                    <span class="nm">{{ col.name }}</span>
                    <span class="ct">{{ col.col.total }}</span>
                    <button v-if="canWrite" class="col-add" title="新建缺陷" @click.stop="ui.openCreate('defect', { status: col.key })">+</button>
                </div>
                <BoardColumnList
                    :items="col.col.items"
                    :total="col.col.total"
                    :has-more="col.col.hasMore"
                    :loading="col.col.loading"
                    @load-more="loadMore(col.key)"
                >
                    <template #item="{ item: d }">
                        <article
                            class="bug"
                            :class="{
                                dragging: dragId === d.id,
                                'drop-before': dropRef === d.id && !dropAfter && dragId !== d.id,
                                'drop-after': dropRef === d.id && dropAfter && dragId !== d.id
                            }"
                            draggable="true"
                            @dragstart="onDragStart($event, d)"
                            @dragend="resetDrag"
                            @dragover="onCardDragOver($event, d)"
                            @click="openDetail(d)"
                        >
                            <div class="bug-top">
                                <OptTag kind="severities" base="sev" :value="d.severity" />
                                <OptTag :value="d.prio" />
                                <span v-if="d.moduleName" class="bug-mod">{{ d.moduleName }}</span>
                                <span v-if="d.reopenCount" class="bug-reopen" title="重开次数">↻{{ d.reopenCount }}</span>
                                <span class="bug-id">{{ d.id }}</span>
                            </div>
                            <h4>{{ d.title }}</h4>
                            <div class="bug-foot">
                                <span class="mini-ava ava" :class="mmap[d.owner]?.color">{{ mmap[d.owner]?.short || '?' }}</span>
                                <span class="who">{{ mmap[d.owner]?.name || '—' }}</span>
                                <span class="env">{{ d.env }}</span>
                                <span class="it">{{ d.iter }}</span>
                                <span class="dt">{{ d.date.slice(0, 10) }}</span>
                            </div>
                        </article>
                    </template>
                    <template #empty>
                        <div class="col-empty">{{ filterActive ? '无匹配缺陷' : '暂无缺陷' }}</div>
                    </template>
                </BoardColumnList>
            </div>
        </div>

        <!-- 缺陷列表 -->
        <div v-else class="card table-card rise d5">
            <div class="table-head">
                <div class="col-check"><input type="checkbox" :checked="allChecked" @change="toggleAll" /></div>
                <div class="col-id">编号</div>
                <div class="col-title">标题</div>
                <div class="col-mod">模块</div>
                <div class="col-sev">严重度</div>
                <div class="col-prio">优先级</div>
                <div class="col-status">状态</div>
                <div class="col-owner">处理人</div>
                <div class="col-reporter">报告人</div>
                <div class="col-date">创建时间</div>
                <div class="col-iter">迭代</div>
            </div>

            <div v-for="d in paged" :key="d.id" class="table-row clickable" @click="openDetail(d)">
                <div class="col-check" @click.stop><input type="checkbox" :value="d.id" v-model="selected" /></div>
                <div class="col-id bug-id">{{ d.id }}</div>
                <div class="col-title">
                    <div class="bug-title">
                        {{ d.title }}<span class="env">{{ d.env }}</span>
                    </div>
                </div>
                <div class="col-mod">{{ d.moduleName || opts.moduleName(d.moduleId) || '—' }}</div>
                <div class="col-sev">
                    <OptTag kind="severities" base="sev" :value="d.severity" />
                </div>
                <div class="col-prio">
                    <OptTag :value="d.prio" />
                </div>
                <div class="col-status">
                    <span class="status" :class="d.status" :style="defStyle(d.status)">{{ defName(d.status) }}</span>
                </div>
                <div class="col-owner">
                    <div class="owner-cell">
                        <span class="mini-ava ava" :class="mmap[d.owner]?.color">{{ mmap[d.owner]?.short || '?' }}</span
                        >{{ mmap[d.owner]?.name || '—' }}
                    </div>
                </div>
                <div class="col-reporter reporter-cell">{{ d.reporter }}</div>
                <div class="col-date date-cell">{{ d.date }}</div>
                <div class="col-iter iter-cell">{{ d.iter }}</div>
            </div>

            <div v-if="!listRows.length" class="empty">没有符合条件的缺陷</div>
            <!-- 分页（全站统一组件） -->
            <AppPager v-else :total="listTotal" :page="page" :page-size="pageSize" @update:page="page = $event" @update:pageSize="pageSize = $event" />

            <!-- 底部批量操作条 -->
            <div v-if="selected.length" class="batch-bar">
                <span class="batch-n">已选 {{ selected.length }} 项</span>
                <select class="batch-sel" v-model="bulkStatus" @change="bulkSetStatus">
                    <option value="">批量状态…</option>
                    <option v-for="s in FLOW" :key="s" :value="s">{{ defName(s) }}</option>
                </select>
                <select class="batch-sel" v-model="bulkOwner" @change="bulkSetOwner">
                    <option value="">批量处理人…</option>
                    <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
                <select class="batch-sel" v-model="bulkModule" @change="bulkSetModule">
                    <option value="">批量模块…</option>
                    <option value="__none">未归类</option>
                    <option v-for="m in opts.modules" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
                <button v-if="canManage" class="ghost-btn batch-del" @click="confirmBulkDel = true">批量删除</button>
                <button class="batch-clear" @click="selected = []">取消选择</button>
            </div>
        </div>

        <!-- 缺陷详情弹窗（760 双栏 Jira 风格） -->
        <AppModal
            v-if="detail"
            :title="'缺陷 ' + detail.id"
            width="760"
            @close="
                detailId = null;
                editing = false;
            "
        >
            <div class="dhead">
                <div class="dtitle">{{ detail.title }}</div>
                <button class="watch-btn" :class="{ on: watching }" @click="doWatch">{{ watching ? '★ 已关注' : '☆ 关注' }}</button>
            </div>
            <div class="dsection">
                <div class="k">描述</div>
                <AppEditor :model-value="detail.desc || ''" readonly :min-height="48" placeholder="暂无描述" />
            </div>

            <!-- 附件 -->
            <div class="dsection">
                <div class="k">附件{{ attachSaving ? '（上传中…）' : '' }}</div>
                <AppUpload v-model="attachList" @change="onAttachChange" />
                <div v-if="attachErr" class="field-error">{{ attachErr }}</div>
            </div>

            <!-- 关联缺陷 -->
            <div class="dsection">
                <div class="k">关联缺陷</div>
                <div v-if="detail.relates.length" class="relate-list">
                    <div v-for="r in detail.relates" :key="r.id" class="relate-row">
                        <span class="rel-badge" :class="'rel-' + r.type">{{ REL_LABEL[r.type] || r.type }}</span>
                        <span class="rel-target" @click="focusDefect(r.target)"
                            >{{ r.target }}<em v-if="relTitle(r.target)"> {{ relTitle(r.target) }}</em></span
                        >
                        <button class="rel-del" title="取消关联" @click="removeRelate(r.id)">×</button>
                    </div>
                </div>
                <div v-else class="rel-empty">暂无关联缺陷</div>
                <div class="relate-add">
                    <select class="field-select rel-type" v-model="relNewType">
                        <option value="duplicate">重复</option>
                        <option value="blocks">阻塞</option>
                        <option value="regress">回归</option>
                    </select>
                    <select class="field-select rel-target-sel" v-model="relNewTarget">
                        <option value="">选择目标缺陷…</option>
                        <option v-for="d in relCandidates" :key="d.id" :value="d.id">{{ d.id }} {{ d.title }}</option>
                    </select>
                    <button class="btn" :disabled="!relNewTarget" @click="addRelate">关联</button>
                </div>
            </div>

            <!-- 回归验证 -->
            <div class="dsection">
                <div class="k">回归验证</div>
                <div v-if="detail.verifyLog.length" class="ver-list">
                    <div v-for="v in detail.verifyLog" :key="v.id" class="ver-row">
                        <div class="ver-head">
                            <span class="ver-badge" :class="v.result === '通过' ? 'ok' : 'no'">{{ v.result }}</span>
                            <span class="ver-by">{{ mmap[v.by]?.name || v.by }}</span>
                            <span class="ver-time">{{ v.time }}</span>
                        </div>
                        <div v-if="v.note" class="ver-note">{{ v.note }}</div>
                    </div>
                </div>
                <div v-else class="ver-empty">尚未验证</div>
                <div class="ver-form">
                    <label class="ver-opt" :class="{ on: verResult === '通过' }"><input type="radio" value="通过" v-model="verResult" />通过</label>
                    <label class="ver-opt" :class="{ on: verResult === '复现' }"><input type="radio" value="复现" v-model="verResult" />复现</label>
                    <input class="field-input ver-input" v-model="verNote" placeholder="验证备注，可不填" @keydown.enter="addVerify" />
                    <button class="btn" @click="addVerify">记录验证</button>
                </div>
            </div>

            <!-- 评论 -->
            <div class="dsection">
                <AppComments kind="defect" :ref-id="detail.id" />
            </div>
            <!-- 活动时间线 -->
            <div class="dsection">
                <div class="k">活动</div>
                <ActivityTimeline kind="defect" :ref-id="detail.id" :fallback="'创建于 ' + detail.date" />
            </div>
            <template #rail>
                <div class="flow-bar">
                    <button v-for="s in FLOW" :key="s" class="flow-btn" :class="{ on: detail.status === s }" @click="setStatus(s)">{{ defName(s) }}</button>
                </div>
                <div class="rail-row">
                    <div class="rail-k">状态</div>
                    <div class="rail-v">
                        <span class="status" :class="detail.status" :style="defStyle(detail.status)">{{ defName(detail.status) }}</span>
                    </div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">业务模块</div>
                    <div class="rail-v">{{ detail.moduleName || opts.moduleName(detail.moduleId) || '未归类' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">严重度</div>
                    <div class="rail-v">
                        <OptTag kind="severities" base="sev" :value="detail.severity" />
                    </div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">优先级</div>
                    <div class="rail-v">
                        <OptTag :value="detail.prio" />
                    </div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">环境</div>
                    <div class="rail-v">{{ detail.env }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">缺陷类型</div>
                    <div class="rail-v">{{ detail.defectType || '功能' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">根因</div>
                    <div class="rail-v">{{ detail.rootCause || '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">引入人</div>
                    <div class="rail-v">{{ detail.introducedBy ? mmap[detail.introducedBy]?.name || '—' : '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">发现版本</div>
                    <div class="rail-v mono">{{ detail.foundIn || '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">修复版本</div>
                    <div class="rail-v mono">{{ detail.fixedIn || '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">重开次数</div>
                    <div class="rail-v">{{ detail.reopenCount || 0 }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">处理人</div>
                    <div class="rail-v">{{ mmap[detail.owner]?.name }} · {{ mmap[detail.owner]?.role }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">报告人</div>
                    <div class="rail-v">{{ detail.reporter }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">发现日期</div>
                    <div class="rail-v">{{ detail.date }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">修复期限</div>
                    <div class="rail-v">{{ detail.deadline || '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">所属迭代</div>
                    <div class="rail-v">{{ detail.iter }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">关联需求</div>
                    <div class="rail-v mono">{{ detail.reqId || '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">修复 / 关闭时间</div>
                    <div class="rail-v">{{ detail.resolvedAt || '—' }} / {{ detail.closedAt || '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">已投入工时</div>
                    <div class="rail-v">{{ detail.spent ?? 0 }} h</div>
                </div>
            </template>
            <template #footer>
                <button v-if="canManage" class="ghost-btn del-text" @click="confirmDel = true">删除</button>
                <button class="ghost-btn" @click="startEdit">编辑</button>
            </template>
        </AppModal>

        <!-- 编辑弹窗（叠在详情之上） -->
        <AppModal v-if="editing && detail" :title="'编辑缺陷 ' + detail.id" :z-index="1010" @close="editing = false">
            <div class="field">
                <label class="field-label">缺陷标题<span class="req">*</span></label>
                <input class="field-input" :class="{ invalid: editErr }" v-model="editForm.title" @input="editErr = ''" />
                <div v-if="editErr" class="field-error">{{ editErr }}</div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">环境</label>
                    <select class="field-select" v-model="editForm.env">
                        <!-- 历史缺陷的环境已从设置里删掉时仍保留该值，避免下拉空白 -->
                        <option v-if="editForm.env && !opts.envs.includes(editForm.env)" :value="editForm.env">{{ editForm.env }}（已删除）</option>
                        <option v-for="e in opts.envs" :key="e" :value="e">{{ e }}</option>
                    </select>
                </div>
                <div class="field">
                    <label class="field-label">优先级</label>
                    <select class="field-select" v-model="editForm.prio">
                        <option v-for="p in opts.priorities" :key="p.key" :value="p.key">{{ p.name }}</option>
                    </select>
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">严重度</label>
                    <select class="field-select" v-model="editForm.severity">
                        <option v-for="s in opts.severities" :key="s.key" :value="s.key">{{ s.name }}</option>
                    </select>
                </div>
                <div class="field">
                    <label class="field-label">处理人</label>
                    <select class="field-select" v-model="editForm.owner">
                        <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                    </select>
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">业务模块</label>
                    <select class="field-select" v-model="editForm.moduleId">
                        <option value="">未归类（随关联需求）</option>
                        <option v-for="m in opts.modules" :key="m.id" :value="m.id">{{ m.name }}</option>
                    </select>
                </div>
                <div class="field">
                    <label class="field-label">缺陷类型</label>
                    <select class="field-select" v-model="editForm.defectType">
                        <option v-for="t in DEFECT_TYPES" :key="t" :value="t">{{ t }}</option>
                    </select>
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">根因</label>
                    <select class="field-select" v-model="editForm.rootCause">
                        <option v-for="c in ROOT_CAUSES" :key="c" :value="c">{{ c || '未分析' }}</option>
                    </select>
                </div>
                <div class="field">
                    <label class="field-label">引入人（仅复盘）</label>
                    <select class="field-select" v-model="editForm.introducedBy">
                        <option value="">未定位</option>
                        <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                    </select>
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">报告人</label>
                    <select class="field-select" v-model="editForm.reporterId">
                        <option value="">外部反馈人 / 未关联账号</option>
                        <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                    </select>
                </div>
                <div class="field">
                    <label class="field-label">外部报告人</label>
                    <input class="field-input" v-model="editForm.reporter" :disabled="!!editForm.reporterId" placeholder="如 客服-王芳（无成员账号时填写）" />
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">发现版本</label>
                    <input class="field-input" v-model="editForm.foundIn" placeholder="如 v2.24.0-rc.1" />
                </div>
                <div class="field">
                    <label class="field-label">修复版本</label>
                    <input class="field-input" v-model="editForm.fixedIn" placeholder="如 v2.24.0" />
                </div>
            </div>
            <div class="field">
                <label class="field-label">修复期限</label>
                <input type="datetime-local" class="field-input" v-model="editForm.deadline" />
            </div>
            <div class="field">
                <label class="field-label">描述</label>
                <AppEditor v-model="editForm.desc" :min-height="220" placeholder="现象 / 期望结果 / 实际结果；复现步骤请分条编号（含运行环境）" />
            </div>
            <template #footer>
                <button class="ghost-btn" @click="editing = false">取消</button>
                <button class="btn" @click="saveEdit">保存</button>
            </template>
        </AppModal>

        <!-- 删除确认 -->
        <AppConfirm v-if="confirmDel && detail" title="删除缺陷" :text="`确定删除缺陷 ${detail.id}「${detail.title}」？删除后不可恢复。`" @confirm="doDelete" @close="confirmDel = false" />

        <!-- 批量删除确认 -->
        <AppConfirm v-if="confirmBulkDel" title="批量删除缺陷" :text="`确定删除选中的 ${selected.length} 个缺陷？删除后不可恢复。`" @confirm="bulkDelete" @close="confirmBulkDel = false" />
    </template>
</template>

<style scoped>
/* 标题 + 双视图切换 */
.title-row {
    display: flex;
    align-items: center;
    gap: 14px;
}
.view-tabs {
    display: flex;
    gap: 2px;
    padding: 3px;
    border: 1px solid var(--border);
    border-radius: 9px;
    background: var(--track);
    overflow: hidden;
}
.view-tabs button {
    height: 26px;
    padding: 0 14px;
    border: none;
    border-radius: 6px;
    background: none;
    font-size:13px;
    color: var(--t2);
    cursor: pointer;
    font-family: inherit;
}
.view-tabs button.on {
    background: var(--card);
    color: var(--primary);
    font-weight:400;
    box-shadow: var(--shadow-xs);
}
.f-search {
    width: 220px;
    height: 32px;
    font-size:13px;
}

/* 看板 */
.board {
    display: flex;
    gap: 16px;
    overflow-x: auto;
    padding-bottom: 6px;
    margin-bottom: 16px;
}
/* 列宽/底色与任务看板一致，保证两处看板卡片同宽同高 */
.bcol {
    flex: 1 0 268px;
    background: var(--board-col);
    border: 1px solid transparent;
    border-radius: var(--radius-lg);
    padding: 10px;
    min-height: 320px;
    transition: background 0.15s var(--ease), border-color 0.15s var(--ease), box-shadow 0.2s var(--ease);
}
.bcol.dropping {
    background: var(--primary-bg);
    border-color: var(--primary-light);
    box-shadow: inset 0 0 0 1px var(--primary-light);
}
.col-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 8px 10px;
}
.col-dot {
    flex: none;
    width: 8px;
    height: 8px;
    border-radius: 50%;
}
.col-head .nm {
    font-size:14px;
    font-weight:700;
}
.col-head .ct {
    font-size:12px;
    color: var(--t2);
    background: var(--card);
    border-radius: 5px;
    padding: 0 7px;
    line-height: 18px;
    font-variant-numeric: tabular-nums;
}
.col-add {
    margin-left: auto;
    width: 20px;
    height: 20px;
    border: none;
    border-radius: 5px;
    background: none;
    color: var(--t2);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
    font-family: inherit;
}
.col-add:hover {
    background: var(--card);
    color: var(--primary);
}
.bug {
    background: var(--card);
    border: 1px solid var(--card-edge);
    border-radius: var(--radius-md);
    padding: 12px 13px;
    box-shadow: var(--shadow-xs);
    cursor: pointer;
    transition: box-shadow 0.2s var(--ease), transform 0.15s var(--ease), border-color 0.15s var(--ease);
}
.bug:hover {
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
    border-color: var(--border-accent);
}
/* 拖拽落点指示：卡片上/下沿一条主色线 */
.bug.drop-before {
    box-shadow: 0 -3px 0 0 var(--primary);
}
.bug.drop-after {
    box-shadow: 0 3px 0 0 var(--primary);
}
.bug.dragging {
    opacity: 0.5;
    cursor: grabbing;
    transform: rotate(1.5deg);
}
.bug-top {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 4px 6px;
    margin-bottom: 8px;
}
.bug h4 {
    font-size:14px;
    font-weight:400;
    line-height: 1.45;
}
.bug-top .bug-id {
    margin-left: auto;
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.bug-top .bug-mod {
    font-size:12px;
    color: var(--t3);
    background: var(--track);
    border-radius: 4px;
    padding: 1px 5px;
    max-width: 84px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.bug-top .bug-reopen {
    font-size:12px;
    color: var(--red);
}
.bug-foot {
    display: flex;
    align-items: center;
    gap: 7px;
    margin-top: 10px;
    flex-wrap: wrap;
}
.bug-foot .who {
    font-size: 12px;
    color: var(--t3);
}
.bug-foot .env {
    font-size:12px;
    color: var(--t3);
    background: var(--track);
    border-radius: 4px;
    padding: 1px 6px;
}
.bug-foot .it {
    font-size:12px;
    color: var(--t2);
}
.bug-foot .dt {
    margin-left: auto;
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.col-empty {
    padding: 14px 8px;
    font-size: 12px;
    color: var(--t3);
    text-align: center;
}

.trend-card {
    margin-bottom: 16px;
}
.trend-svg {
    width: 100%;
    height: auto;
    display: block;
}
.trend-legend {
    display: flex;
    gap: 20px;
    margin-top: 10px;
    font-size: 12px;
    color: var(--t2);
}
.trend-legend i {
    display: inline-block;
    width: 14px;
    height: 3px;
    border-radius: 1.5px;
    margin-right: 6px;
    vertical-align: 2px;
}

.col-check {
    width: 32px;
    flex: none;
}
.col-id {
    width: 72px;
    flex: none;
    font-variant-numeric: tabular-nums;
}
.col-title {
    flex: 1;
    min-width: 180px;
}
.col-mod {
    width: 92px;
    flex: none;
    font-size: 12px;
    color: var(--t2);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.col-sev {
    width: 56px;
    flex: none;
    text-align: center;
}
.col-prio {
    width: 78px;
    flex: none;
    text-align: center;
}
.col-status {
    width: 80px;
    flex: none;
    text-align: center;
}
.col-owner {
    width: 90px;
    flex: none;
}
.col-reporter {
    width: 90px;
    flex: none;
}
.col-date {
    width: 80px;
    flex: none;
    font-variant-numeric: tabular-nums;
}
.col-iter {
    width: 100px;
    flex: none;
}
.col-actions {
    width: 60px;
    flex: none;
    text-align: right;
}

.bug-id {
    font-size:13px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.bug-title {
    font-size:14px;
    color: var(--t1);
}
.bug-title .env {
    font-size:12px;
    color: var(--t3);
    margin-left: 8px;
    font-weight: 400;
}
.sev {
    display: inline-flex;
    align-items: center;
    font-size:12px;
    font-weight:400;
    padding: 1px 7px;
    border-radius: 5px;
}
/* 严重度配色由 OptTag 按「项目设置 → 选项配置 → 严重度」渲染，不再依赖 .sev.fatal 等固定 class */
.owner-cell {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size:13px;
    color: var(--t2);
}
.reporter-cell {
    font-size:13px;
    color: var(--t2);
}
.date-cell {
    font-size: 12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.iter-cell {
    font-size: 12px;
    color: var(--t2);
}
.row-actions {
    display: flex;
    gap: 4px;
    justify-content: flex-end;
}
.row-btn {
    width: 26px;
    height: 26px;
    border: none;
    background: none;
    border-radius: 6px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--t3);
}
.row-btn svg {
    width: 15px;
    height: 15px;
    stroke: currentColor;
    stroke-width: 1.7;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.empty {
    padding: 24px;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}

.rail-row {
    margin-bottom: 13px;
}
.rail-k {
    font-size:12px;
    color: var(--t3);
}
.rail-v {
    font-size: 13px;
    margin-top: 3px;
    line-height: 1.6;
}
.mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 12px;
}

.flow-bar {
    display: grid;
    grid-template-columns: 1fr 1fr;
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
.del-text {
    color: var(--red);
}

/* 关注按钮 */
.watch-btn {
    flex: none;
    height: 28px;
    padding: 0 10px;
    border: 1px solid var(--border);
    border-radius: 7px;
    background: var(--card);
    color: var(--t2);
    font-size:13px;
    cursor: pointer;
    font-family: inherit;
}
.watch-btn.on {
    background: var(--primary-bg);
    border-color: var(--primary);
    color: var(--primary);
    font-weight:400;
}

/* 底部批量操作条 */
.batch-bar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 20px;
    background: var(--primary-bg);
    border-top: 1px solid var(--border);
}
.batch-n {
    font-size:13px;
    font-weight:700;
    color: var(--primary);
    font-variant-numeric: tabular-nums;
}
.batch-sel {
    height: 30px;
    padding: 0 8px;
    font-size:13px;
    border: 1px solid var(--border);
    border-radius: 7px;
    background: var(--card);
    color: var(--t2);
    outline: none;
    font-family: inherit;
    cursor: pointer;
}
.batch-del {
    height: 30px;
    color: var(--red);
}
.batch-clear {
    margin-left: auto;
    border: none;
    background: none;
    font-size:13px;
    color: var(--t2);
    cursor: pointer;
    font-family: inherit;
}

/* 关联缺陷 */
.relate-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 0;
}
.relate-row + .relate-row {
    border-top: 1px solid var(--border);
}
.rel-badge {
    flex: none;
    font-size:12px;
    font-weight:400;
    padding: 1px 7px;
    border-radius: 5px;
}
.rel-badge.rel-duplicate {
    background: var(--amber-bg);
    color: var(--amber);
}
.rel-badge.rel-blocks {
    background: var(--red-bg);
    color: var(--red);
}
.rel-badge.rel-regress {
    background: var(--purple-bg);
    color: var(--purple);
}
.rel-target {
    font-size: 13px;
    color: var(--t1);
    cursor: pointer;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.rel-target em {
    font-style: normal;
    font-size: 12px;
    color: var(--t3);
}
.rel-del {
    margin-left: auto;
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
.rel-empty {
    font-size:13px;
    color: var(--t3);
    padding: 4px 0;
}
.relate-add {
    display: flex;
    gap: 8px;
    margin-top: 8px;
}
.rel-type {
    flex: none;
    width: 96px;
}
.rel-target-sel {
    flex: 1;
    width: auto;
    min-width: 0;
}
.relate-add .btn:disabled {
    opacity: 0.5;
    cursor: default;
}

/* 回归验证 */
.ver-row {
    padding: 8px 0;
}
.ver-row + .ver-row {
    border-top: 1px solid var(--border);
}
.ver-head {
    display: flex;
    align-items: center;
    gap: 8px;
}
.ver-badge {
    font-size:12px;
    font-weight:700;
    padding: 1px 7px;
    border-radius: 5px;
}
.ver-badge.ok {
    background: var(--green-bg);
    color: var(--green);
}
.ver-badge.no {
    background: var(--red-bg);
    color: var(--red);
}
.ver-by {
    font-size:13px;
    color: var(--t1);
}
.ver-time {
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.ver-note {
    font-size:13px;
    color: var(--t2);
    margin-top: 3px;
    line-height: 1.6;
}
.ver-empty {
    font-size:13px;
    color: var(--t3);
    padding: 4px 0;
}
.ver-form {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
    flex-wrap: wrap;
}
.ver-opt {
    display: inline-flex;
    align-items: center;
    height: 30px;
    padding: 0 12px;
    border: 1px solid var(--border);
    border-radius: 7px;
    background: var(--card);
    font-size:13px;
    color: var(--t2);
    cursor: pointer;
}
.ver-opt input {
    display: none;
}
.ver-opt.on {
    border-color: var(--primary);
    background: var(--primary-bg);
    color: var(--primary);
    font-weight:400;
}
.ver-input {
    flex: 1;
    width: auto;
    min-width: 140px;
    height: 30px;
}

/* 与任务看板的响应式断点一致，窄屏下两处看板列宽保持相同 */
@media (max-width: 1150px) {
    .bcol {
        flex-basis: 240px;
    }
}
@media (max-width: 880px) {
    .bcol {
        flex-basis: 76vw;
    }
}
@media (max-width: 1150px) {
    .table-head,
    .table-row {
        flex-wrap: wrap;
        gap: 8px;
    }
    .col-title {
        flex-basis: 100%;
    }
    .col-sev,
    .col-prio,
    .col-status,
    .col-owner,
    .col-mod,
    .col-reporter,
    .col-date,
    .col-iter {
        width: auto;
        text-align: left;
    }
}
</style>
