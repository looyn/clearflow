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
import AppChecklist from '../components/AppChecklist.vue';
import AppUpload from '../components/AppUpload.vue';
import AppComments from '../components/AppComments.vue';
import ActivityTimeline from '../components/ActivityTimeline.vue';
import AppPager from '../components/AppPager.vue';
import OptTag from '../components/OptTag.vue';
import { useOptionsStore } from '../stores/options';
import { useIterationsStore } from '../stores/iterations';
import { useMembersStore } from '../stores/members';
import { usePermStore } from '../stores/perm';
import PageCreateButton from '../components/PageCreateButton.vue';

const opts = useOptionsStore(); // 项目设置里的选项配置（优先级/严重度/任务类型/标签）
const iters = useIterationsStore(); // 统计文案与编辑弹窗的迭代下拉
const members = useMembersStore(); // 成员映射（负责人/头像）
const perm = usePermStore();
// 删除需求需「需求池」管理级（与后端 DELETE 接口同口径）
const canManage = computed(() => perm.canManage('需求池'));
// AI 拆分只生成草稿，但批量落库走 POST /tasks：两处写权限都要有
const canSplit = computed(() => perm.canWrite('需求池') && perm.canWrite('任务看板'));

const ui = useUiStore();
const CURRENT_USER = useAuthStore().user?.id ?? '';
const route = useRoute();
const router = useRouter();
const loading = ref(true);
const rows = ref([]); // 当前页需求（服务端分页）
const total = ref(0); // 筛选后的总条数（后端返回，分页器用）
const stat = ref(null); // 统计条与状态下拉计数（后端 SQL 聚合，不受分页影响）
const mmap = computed(() => members.map); // 成员映射（共享 store）
const links = ref({ tasks: [], defects: [] }); // 详情「追踪链」：本需求关联的任务 / 缺陷

// 筛选/排序变化会连续发请求，只认最后一次响应（避免旧结果覆盖新结果）
let fetchSeq = 0;
async function fetchStats() {
    const seq = fetchSeq;
    const s = await api.getRequirementStats(iters.active?.code);
    if (seq !== fetchSeq) return;
    stat.value = s;
}
async function fetchData() {
    const seq = ++fetchSeq;
    const r = await api.getRequirements(filterParams.value, page.value, pageSize.value);
    if (seq !== fetchSeq) return;
    rows.value = r.list || [];
    total.value = r.total || 0;
    if (!rows.value.length && page.value > 1) page.value = 1; // 删除后页码越界回收
    await fetchStats();
}
// 活跃迭代由共享 store 异步就绪，就绪后统计口径（迭代内研发中数量）要重算
watch(() => iters.active?.code, (c, old) => { if (c !== old) fetchStats().catch(() => {}); });
onMounted(async () => {
    // 先落 URL 条件再取数，避免深链进来后重复请求
    if (route.query.status) fStatus.value = route.query.status;
    if (route.query.prio) fPrio.value = route.query.prio;
    if (route.query.owner) fOwner.value = route.query.owner;
    if (route.query.iter) fIter.value = route.query.iter;
    if (route.query.module) fModule.value = route.query.module;
    if (route.query.q) fKw.value = route.query.q;
    if (route.query.sort) sortKey.value = route.query.sort;
    if (route.query.focus) detailId.value = route.query.focus;
    await fetchData();
    loading.value = false;
});
watch(() => ui.refreshTick, fetchData);

const mmapArr = computed(() => Object.values(mmap.value));

// ===== 状态流（项目设置 → 工作流 → 需求工作流）：顺序即流转顺序，最后一档为终态 =====
const reqKeys = computed(() => opts.keysOf('requirement'));
const reqTerminal = computed(() => opts.terminalOf('requirement'));
const reqName = (k) => opts.nameOf('requirement', k);
// 统计条中间档：倒数第二档（默认「研发中」）；档位不足时不出这张卡的语义值
const reqDoingKey = computed(() => {
    const ks = reqKeys.value;
    return ks.length > 2 ? ks[ks.length - 2] : null;
});

// ===== 统计（后端 SQL 聚合：总数 / 各状态 / 本季度新增 / 活跃迭代内分布）=====
const pctOf = (n) => (stat.value?.total ? Math.round((n / stat.value.total) * 1000) / 10 : 0);
const stats = computed(() => {
    const by = stat.value?.byStatus || {};
    const iterBy = stat.value?.iterByStatus || {};
    return {
        total: stat.value?.total ?? 0,
        doing: reqDoingKey.value ? by[reqDoingKey.value] || 0 : 0,
        doingInActive: reqDoingKey.value ? iterBy[reqDoingKey.value] || 0 : 0,
        pending: by[reqKeys.value[0]] || 0,
        released: by[reqTerminal.value] || 0,
        quarterNew: stat.value?.quarterNew ?? 0
    };
});

// ===== 筛选（状态 / 优先级 / 负责人 / 迭代 / 模块 / 关键词，全部下推服务端）=====
const fStatus = ref('');
const fPrio = ref('');
const fOwner = ref('');
const fIter = ref('');
const fModule = ref('');
const fKw = ref('');
const sortKey = ref('default');
const filterParams = computed(() => ({
    q: fKw.value.trim(),
    status: fStatus.value,
    prio: fPrio.value,
    owner: fOwner.value,
    iter: fIter.value,
    module: fModule.value,
    sort: sortKey.value === 'default' ? '' : sortKey.value
}));
const statusOptions = computed(() => {
    const by = stat.value?.byStatus || {};
    return [
        { value: '', label: `全部 ${stat.value?.total ?? 0}` },
        ...reqKeys.value.map((k) => ({ value: k, label: `${reqName(k)} ${by[k] || 0}` }))
    ];
});
const prioOptions = computed(() => [{ value: '', label: '全部' }, ...opts.priorities.map((p) => ({ value: p.key, label: p.name }))]);
// 优先级下拉无法在 option 里放色点，用左侧小点回显当前选中项
const prioColor = computed(() => opts.priorities.find((p) => p.key === fPrio.value)?.color || 'transparent');
// 迭代候选取需求表实际出现过的排期值（后端 DISTINCT，含「待排期」）
const iterOptions = computed(() => stat.value?.iters || []);

// ===== 分页（服务端分页，paged 即当前页）=====
const page = ref(1);
const pageSize = ref(10);
const paged = computed(() => rows.value);
watch([fStatus, fPrio, fOwner, fIter, fModule, fKw, sortKey], () => {
    // 回第一页由 page 监听取数，已经是第一页时直接取数，避免重复请求
    if (page.value === 1) fetchData().catch(() => {});
    else page.value = 1;
});
watch([page, pageSize], () => fetchData().catch(() => {}));

const TYPE_CLS = { 功能: 'fn', 优化: 'opt', 技术: 'tech', 体验: 'ux' };

// ===== 导出 CSV（后端按当前筛选生成全量明细，不受分页窗口截断）=====
const exporting = ref(false);
async function exportCsv() {
    if (exporting.value) return;
    exporting.value = true;
    try {
        const blob = await api.exportRequirements(filterParams.value);
        const url = URL.createObjectURL(blob);
        const a = Object.assign(document.createElement('a'), { href: url, download: 'requirements.csv' });
        a.click();
        URL.revokeObjectURL(url);
    } finally {
        exporting.value = false;
    }
}

// ===== 详情 / 编辑（详情按 id 取，列表行不含描述与协作明细）=====
const detailId = ref(null);
const detail = ref(null);
let detailSeq = 0;

function openDetail(r) {
    detail.value = r; // 先用当前行渲染弹窗，正文/验收/关注等异步回填
    detailId.value = r.id;
}

async function fetchDetail() {
    const seq = ++detailSeq;
    const id = detailId.value;
    if (!id) {
        detail.value = null;
        return;
    }
    const full = await api.getRequirement(id);
    if (seq !== detailSeq || detailId.value !== id) return;
    detail.value = full;
    syncAcceptance();
    attachList.value = [...(full.attachments || [])];
    attachIds.value = attachList.value.map((x) => x.id);
}

// 列表与详情一起刷新（改状态 / 编辑 / 勾验收后两侧都要同步）
async function reloadAll() {
    await Promise.all([fetchData(), fetchDetail()]);
}
const editing = ref(false);
const editForm = reactive({ title: '', type: '功能', prio: '', owner: '', iter: '', release: '', moduleId: '', desc: '' });
const editErr = ref('');

// 编辑表单要带描述正文（列表行不含），先确保详情回填完成再取初值
async function startEdit() {
    if (!detailId.value) return;
    await fetchDetail();
    if (!detail.value) return;
    Object.assign(editForm, {
        title: detail.value.title,
        type: detail.value.type,
        prio: detail.value.prio,
        owner: detail.value.owner,
        iter: detail.value.iter,
        release: detail.value.release,
        moduleId: detail.value.moduleId ?? '',
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
    await api.updateRequirement(detailId.value, { ...editForm });
    editing.value = false;
    await reloadAll();
}

// ===== 状态流转（流转按钮 = 配置顺序）=====
async function changeStatus(s) {
    if (!detail.value || detail.value.status === s) return;
    await api.setRequirementStatus(detail.value.id, s);
    await reloadAll();
}

// ===== AI 拆分任务：生成草稿 → 人工维护 → 逐条 createTask（等同批量导入）=====
const splitOpen = ref(false);
const splitLoading = ref(false);
const splitCreating = ref(false);
const splitRows = ref([]);
const splitMock = ref(false);
const splitErr = ref('');
// 新任务默认落在进行中的迭代；没有则沿用需求自己的迭代
const splitIter = computed(() => iters.active?.code || detail.value?.iter || '待排期');
// 默认截止日期取迭代区间结束日，需求没有迭代时留空（任务按「待排期」处理）
const splitDue = computed(() => {
    const it = iters.active || iters.list.find((i) => i.code === detail.value?.iter);
    const end = String(it?.range || '').split('~')[1]?.trim() || '';
    return /^\d{4}-\d{2}-\d{2}$/.test(end) ? end : '';
});
const splitSelected = computed(() => splitRows.value.filter((r) => r.sel));
const splitEstimate = computed(() => splitSelected.value.reduce((n, r) => n + (Number(r.estimate) || 0), 0));
const allSplitChecked = computed(() => splitRows.value.length > 0 && splitRows.value.every((r) => r.sel));
const someSplitChecked = computed(() => splitSelected.value.length > 0 && !allSplitChecked.value);

// AI 返回的是富文本，预览里显示纯文本；用户没改过就保留原 HTML
const splitText = (html) =>
    String(html || '')
        .replace(/<br\s*\/?>/gi, '\n')
        .replace(/<\/(p|li|div)>/gi, '\n')
        .replace(/<[^>]*>/g, '')
        .replace(/&nbsp;/g, ' ')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&amp;/g, '&')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
const splitHtml = (text) => {
    const lines = String(text || '')
        .split(/\n+/)
        .map((l) => l.trim())
        .filter(Boolean);
    const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return lines.map((l) => '<p>' + esc(l) + '</p>').join('');
};

function openSplit() {
    splitOpen.value = true; // 先开弹窗给反馈，再异步等 AI
    splitErr.value = '';
    loadSplit();
}
function closeSplit() {
    if (splitCreating.value) return;
    splitOpen.value = false;
}
async function loadSplit() {
    if (!detail.value || splitLoading.value) return;
    const id = detail.value.id;
    splitLoading.value = true;
    splitErr.value = '';
    splitRows.value = [];
    try {
        const r = await api.splitRequirementTasks(id);
        splitMock.value = !!r.mock;
        splitRows.value = (r.tasks || []).map((t) => ({
            sel: true,
            title: t.title || '',
            descText: splitText(t.desc),
            descHtml: t.desc || '',
            descDirty: false,
            type: t.type || opts.taskTypes[0]?.key || '',
            prio: t.prio || detail.value.prio || opts.priorities[1]?.key || 'p1',
            estimate: Number(t.estimate) || 1,
            assignee: detail.value.owner ?? '',
            due: splitDue.value
        }));
        if (!splitRows.value.length) splitErr.value = 'AI 没有给出可用的任务，请重新生成';
    } catch (e) {
        splitErr.value = e?.message || 'AI 拆分失败，请稍后重试';
    } finally {
        splitLoading.value = false;
    }
}
function toggleAllSplit(e) {
    splitRows.value.forEach((r) => (r.sel = e.target.checked));
}
function addSplitRow() {
    splitErr.value = '';
    splitRows.value.push({
        sel: true,
        title: '',
        descText: '',
        descHtml: '',
        descDirty: false,
        type: opts.taskTypes[0]?.key || '',
        prio: detail.value?.prio || opts.priorities[1]?.key || 'p1',
        estimate: 2,
        assignee: detail.value?.owner ?? '',
        due: splitDue.value
    });
}
async function createSplitTasks() {
    if (splitCreating.value || !detail.value) return;
    const chosen = splitSelected.value;
    if (!chosen.length) {
        splitErr.value = '请至少选择一个任务';
        return;
    }
    if (chosen.some((r) => !String(r.title || '').trim())) {
        splitErr.value = '任务标题不能为空';
        return;
    }
    const d = detail.value;
    splitCreating.value = true;
    splitErr.value = '';
    let created = 0;
    try {
        for (const row of chosen) {
            const payload = {
                reqId: d.id,
                moduleId: d.moduleId ?? '',
                title: row.title.trim(),
                desc: row.descDirty ? splitHtml(row.descText) : row.descHtml || splitHtml(row.descText),
                type: row.type,
                prio: row.prio,
                estimate: Math.max(1, Math.min(40, Number(row.estimate) || 1)),
                assignee: row.assignee === '' ? null : row.assignee,
                iter: splitIter.value
            };
            if (row.due) payload.due = row.due;
            await api.createTask(payload);
            row.sel = false; // 已创建的退出选择，失败重试不会重复建
            created++;
        }
        splitOpen.value = false;
        await reloadAll();
    } catch (e) {
        splitErr.value = `已创建 ${created} 个，剩余 ${chosen.length - created} 个未创建：${e?.message || '请稍后重试'}`;
    } finally {
        splitCreating.value = false;
        if (created) await loadLinked(d.id).catch(() => {});
    }
}

// ===== 删除 =====
const confirmDel = ref(false);
async function doDelete() {
    const id = detailId.value;
    confirmDel.value = false;
    detailId.value = null;
    editing.value = false;
    await api.deleteRequirement(id);
    ui.notifyRefresh();
}

// ===== 追踪链：本需求关联的任务 / 缺陷（后端按 req_id 直查）=====
const linkedTasks = computed(() => links.value.tasks);
const linkedDefects = computed(() => links.value.defects);
// 关联任务状态：项目设置 → 工作流（任务状态）
const taskName = (k) => opts.nameOf('task', k);

// ===== 验收标准（AppChecklist 受控，勾选走端点并重拉需求）=====
const acceptance = ref([]);
function syncAcceptance() {
    acceptance.value = detail.value ? [...detail.value.acceptance] : [];
}
async function onAcceptChange(next) {
    const prev = acceptance.value;
    acceptance.value = next;
    const prevMap = Object.fromEntries(prev.map((x) => [x.id, x]));
    const changed = next.find((x) => {
        const p = prevMap[x.id];
        return p && p.done !== x.done;
    });
    if (!changed) return;
    await api.toggleAcceptance(detailId.value, changed.id);
    await reloadAll();
    syncAcceptance(); // 以需求实体上的 acceptance 为唯一数据源
}

// ===== 附件（AppUpload 受控，diff 后逐个持久化并重拉需求）=====
const attachList = ref([]);
const attachIds = ref([]); // 上次同步到服务端的附件 id
const attachSaving = ref(false);
const attachErr = ref('');
// 追踪链按需求 id 直查关联任务 / 缺陷（打开详情时取，批量建任务后复用它刷新）
async function loadLinked(id) {
    links.value = id ? await api.getRequirementLinks(id) : { tasks: [], defects: [] };
}
watch(detailId, async (id) => {
    if (!id) {
        detail.value = null;
        acceptance.value = [];
        attachList.value = [];
        attachIds.value = [];
        links.value = { tasks: [], defects: [] };
        return;
    }
    // 先用已渲染的列表行对齐（行内验收/附件为空，避免显示上一需求的残留），再异步回填
    syncAcceptance();
    attachList.value = [...(detail.value?.attachments || [])];
    attachIds.value = attachList.value.map((x) => x.id);
    links.value = { tasks: [], defects: [] };
    await fetchDetail();
    await loadLinked(id);
});
async function onAttachChange() {
    const now = attachList.value;
    const nowIds = now.map((x) => x.id);
    const added = now.filter((x) => !attachIds.value.includes(x.id));
    const removed = attachIds.value.filter((id) => !nowIds.includes(id));
    if (!added.length && !removed.length) return;
    const rid = detailId.value;
    attachSaving.value = true;
    attachErr.value = '';
    try {
        for (const it of added) await api.saveAttachment('req', rid, it);
        for (const id of removed) await api.removeAttachment('req', rid, id);
    } catch (e) {
        attachErr.value = '附件上传失败：' + (e?.message || '请稍后重试');
    } finally {
        attachSaving.value = false;
        await fetchDetail().catch(() => {}); // 附件与验收都以详情接口为准回填
    }
}

// ===== 关注（⭐）=====
const watching = computed(() => !!detail.value?.watchers?.includes(CURRENT_USER));
async function doWatch() {
    const r = detail.value;
    if (!r) return;
    const before = [...(r.watchers || [])];
    r.watchers = watching.value ? before.filter((x) => x !== CURRENT_USER) : [...before, CURRENT_USER];
    try {
        await api.toggleWatch('req', r.id);
        await fetchDetail(); // 关注状态以服务端为准（列表行不含 watchers）
    } catch (e) {
        r.watchers = before; // 失败回滚星标，避免界面与后端不一致
        throw e;
    }
}

// ===== 批量选择 =====
const selected = ref([]);
// 全选作用于当前页，且不清掉其他页已选中的（跨页保留选择）
const allChecked = computed(() => paged.value.length > 0 && paged.value.every((r) => selected.value.includes(r.id)));
function toggleAll(e) {
    const ids = paged.value.map((r) => r.id);
    if (e.target.checked) selected.value = [...new Set([...selected.value, ...ids])];
    else selected.value = selected.value.filter((id) => !ids.includes(id));
}
const bulkStatus = ref('');
const bulkOwner = ref('');
const bulkModule = ref('');
async function bulkSetStatus() {
    if (!bulkStatus.value) return;
    await api.bulkUpdate('requirement', [...selected.value], { status: bulkStatus.value });
    bulkStatus.value = '';
    selected.value = [];
    await fetchData();
}
async function bulkSetOwner() {
    if (!bulkOwner.value) return;
    await api.bulkUpdate('requirement', [...selected.value], { owner: bulkOwner.value });
    bulkOwner.value = '';
    selected.value = [];
    await fetchData();
}
async function bulkSetModule() {
    if (!bulkModule.value) return;
    const moduleId = bulkModule.value === '__none' ? null : bulkModule.value;
    await api.bulkUpdate('requirement', [...selected.value], { moduleId });
    bulkModule.value = '';
    selected.value = [];
    await fetchData();
}
const confirmBulkDel = ref(false);
async function bulkDelete() {
    confirmBulkDel.value = false;
    const ids = [...selected.value];
    selected.value = [];
    for (const id of ids) await api.deleteRequirement(id);
    await fetchData();
    ui.notifyRefresh();
}

// ===== 筛选/详情同步 URL =====
function syncQuery() {
    const q = {};
    if (fStatus.value) q.status = fStatus.value;
    if (fPrio.value) q.prio = fPrio.value;
    if (fOwner.value) q.owner = fOwner.value;
    if (fIter.value) q.iter = fIter.value;
    if (fModule.value) q.module = fModule.value;
    if (fKw.value.trim()) q.q = fKw.value.trim();
    if (sortKey.value !== 'default') q.sort = sortKey.value;
    if (detailId.value) q.focus = detailId.value;
    router.replace({ path: route.path, query: q }).catch(() => {});
}
watch([fStatus, fPrio, fOwner, fIter, fModule, fKw, sortKey, detailId], syncQuery);
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
    <template v-else>
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>需求池</h1>
                <div class="page-meta">收集、评审和排期所有产品需求</div>
            </div>
            <div class="page-right">
                <button class="ghost-btn" :disabled="exporting" @click="exportCsv">{{ exporting ? '导出中…' : '导出' }}</button>
                <PageCreateButton type="requirement" />
            </div>
        </div>

        <!-- 统计条 -->
        <div class="stat-strip rise d2">
            <div class="stat">
                <div class="v">{{ stats.total }}</div>
                <div class="k">需求总数</div>
                <div class="sub">本季度新增 {{ stats.quarterNew }}</div>
            </div>
            <div class="stat warn" v-if="reqDoingKey">
                <div class="v">{{ stats.doing }}</div>
                <div class="k">{{ reqName(reqDoingKey) }}</div>
                <div class="sub">{{ iters.active ? `${iters.active.code} 关联 ${stats.doingInActive} 个` : `占全部 ${pctOf(stats.doing)}%` }}</div>
            </div>
            <div class="stat danger">
                <div class="v">{{ stats.pending }}</div>
                <div class="k">{{ reqName(reqKeys[0]) }}</div>
                <div class="sub">占全部 {{ pctOf(stats.pending) }}% · 待评审排期</div>
            </div>
            <div class="stat ok">
                <div class="v">{{ stats.released }}</div>
                <div class="k">{{ reqName(reqTerminal) }}</div>
                <div class="sub">占全部 {{ pctOf(stats.released) }}%</div>
            </div>
        </div>

        <!-- 筛选栏 -->
        <div class="filter-bar rise d3">
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
                <span class="filter-label">负责人</span>
                <select class="filter-select" v-model="fOwner">
                    <option value="">全部</option>
                    <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
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
                <span class="filter-label">迭代</span>
                <select class="filter-select" v-model="fIter">
                    <option value="">全部</option>
                    <option v-for="it in iterOptions" :key="it" :value="it">{{ it }}</option>
                </select>
            </div>
            <div class="filter-item">
                <span class="filter-label">关键字</span>
                <input class="field-input f-search" v-model="fKw" placeholder="搜索编号 / 标题 / 来源…" />
            </div>
            <div class="filter-item">
                <span class="filter-label">排序</span>
                <select class="filter-select" v-model="sortKey">
                    <option value="default">默认</option>
                    <option value="estimate">预估点数</option>
                    <option value="prio">优先级</option>
                    <option value="created">创建时间</option>
                    <option value="id">编号</option>
                </select>
            </div>
        </div>

        <!-- 需求列表 -->
        <div class="card table-card rise d4">
            <div class="table-head">
                <div class="col-check"><input type="checkbox" :checked="allChecked" @change="toggleAll" /></div>
                <div class="col-id">编号</div>
                <div class="col-title">标题</div>
                <div class="col-mod">模块</div>
                <div class="col-type">类型</div>
                <div class="col-prio">优先级</div>
                <div class="col-status">状态</div>
                <div class="col-owner">负责人</div>
                <div class="col-src">来源</div>
                <div class="col-iter">迭代</div>
                <div class="col-est">预估</div>
                <div class="col-date">创建</div>
            </div>
            <div v-for="r in paged" :key="r.id" class="table-row clickable" @click="openDetail(r)">
                <div class="col-check" @click.stop><input type="checkbox" :value="r.id" v-model="selected" /></div>
                <div class="col-id rid">{{ r.id }}</div>
                <div class="col-title rtitle">{{ r.title }}</div>
                <div class="col-mod">{{ r.moduleName || opts.moduleName(r.moduleId) || '—' }}</div>
                <div class="col-type">
                    <span class="ttag" :class="TYPE_CLS[r.type]">{{ r.type }}</span>
                </div>
                <div class="col-prio">
                    <OptTag :value="r.prio" />
                </div>
                <div class="col-status">
                    <span class="status" :style="opts.styleOf('requirement', r.status)">{{ reqName(r.status) }}</span>
                </div>
                <div class="col-owner">
                    <div class="owner-cell">
                        <span class="mini-ava ava" :class="mmap[r.owner]?.color">{{ mmap[r.owner]?.short }}</span
                        >{{ mmap[r.owner]?.name || '—' }}
                    </div>
                </div>
                <div class="col-src">{{ r.source }}</div>
                <div class="col-iter">{{ r.iter }}</div>
                <div class="col-est">{{ r.estimate }} 点</div>
                <div class="col-date">{{ r.created }}</div>
            </div>
            <div v-if="!rows.length" class="empty">没有符合条件的需求</div>
            <!-- 分页（全站统一组件，total 取后端筛选后的总条数） -->
            <AppPager v-else :total="total" :page="page" :page-size="pageSize" @update:page="page = $event" @update:pageSize="pageSize = $event" />
        </div>

        <!-- 底部批量操作条 -->
        <div v-if="selected.length" class="batch-bar">
            <span class="batch-n">已选 {{ selected.length }}</span>
            <select class="batch-sel" v-model="bulkStatus" @change="bulkSetStatus">
                <option value="">批量状态…</option>
                <option v-for="s in reqKeys" :key="s" :value="s">{{ reqName(s) }}</option>
            </select>
            <select class="batch-sel" v-model="bulkOwner" @change="bulkSetOwner">
                <option value="">批量负责人…</option>
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

        <!-- 需求详情弹窗（760 双栏：主区 + 属性栏） -->
        <AppModal
            v-if="detail"
            :title="'需求 ' + detail.id"
            width="760"
            @close="
                detailId = null;
                editing = false;
            "
        >
            <div class="dhead">
                <div class="dtitle">{{ detail.title }}</div>
                <button v-if="canSplit" class="ai-btn" @click="openSplit">
                    <svg viewBox="0 0 24 24"><path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M18.4 5.6l-2.8 2.8M8.4 15.6l-2.8 2.8" /></svg>
                    AI 拆分任务
                </button>
                <button class="watch-btn" :class="{ on: watching }" @click="doWatch">{{ watching ? '★ 已关注' : '☆ 关注' }}</button>
            </div>
            <div class="dsection">
                <div class="k">描述</div>
                <AppEditor :model-value="detail.desc" readonly placeholder="暂无描述" />
            </div>
            <div class="dsection">
                <AppChecklist v-if="acceptance.length" :model-value="acceptance" @update:model-value="onAcceptChange" label="验收标准" :addable="false" :removable="false" placeholder="暂无验收标准" />
                <template v-else>
                    <div class="k">验收标准</div>
                    <div class="accept-empty">暂无验收标准</div>
                </template>
            </div>
            <!-- 追踪链：逐项列出关联任务 / 关联缺陷，整行可点击跳转 -->
            <div class="dsection">
                <div class="track-card">
                    <div class="tc-title">追踪链</div>
                    <div class="tc-group">
                        <div class="tc-label">
                            关联任务<span v-if="linkedTasks.length" class="tc-count">{{ linkedTasks.length }} 项</span>
                        </div>
                        <template v-if="linkedTasks.length">
                            <div v-for="t in linkedTasks" :key="t.id" class="tc-row" @click="router.push({ path: '/board', query: { focus: t.id } })">
                                <b class="tc-id">{{ t.id }}</b>
                                <span class="tc-name">{{ t.title }}</span>
                                <span class="status" :style="opts.styleOf('task', t.status)">{{ taskName(t.status) }}</span>
                                <span class="tc-owner">{{ mmap[t.member]?.name || '—' }}</span>
                            </div>
                        </template>
                        <div v-else class="tc-none">暂无</div>
                    </div>
                    <div class="tc-group">
                        <div class="tc-label">
                            关联缺陷<span v-if="linkedDefects.length" class="tc-count">{{ linkedDefects.length }} 项</span>
                        </div>
                        <template v-if="linkedDefects.length">
                            <div v-for="d in linkedDefects" :key="d.id" class="tc-row" @click="router.push({ path: '/defects', query: { focus: d.id } })">
                                <b class="tc-id">{{ d.id }}</b>
                                <span class="tc-name">{{ d.title }}</span>
                                <span class="status" :class="d.status" :style="opts.defStyle(d.status)">{{ opts.defName(d.status) }}</span>
                                <span class="tc-owner">{{ mmap[d.member]?.name || '—' }}</span>
                            </div>
                        </template>
                        <div v-else class="tc-none">暂无</div>
                    </div>
                </div>
            </div>
            <div class="dsection">
                <div class="k">附件{{ attachSaving ? '（上传中…）' : '' }}</div>
                <AppUpload v-model="attachList" @change="onAttachChange" />
                <div v-if="attachErr" class="field-error">{{ attachErr }}</div>
            </div>
            <div class="dsection">
                <AppComments kind="req" :ref-id="detail.id" />
            </div>
            <div class="dsection">
                <div class="k">活动</div>
                <ActivityTimeline kind="req" :ref-id="detail.id" :fallback="'创建于 ' + detail.created" />
            </div>
            <template #rail>
                <div class="flow-btns">
                    <button v-for="s in reqKeys" :key="s" class="flow-btn" :class="{ cur: detail.status === s }" @click="changeStatus(s)">{{ reqName(s) }}</button>
                </div>
                <div class="rail-kv">
                    <div class="item">
                        <div class="k">状态</div>
                        <div class="v">
                            <span class="status" :style="opts.styleOf('requirement', detail.status)">{{ reqName(detail.status) }}</span>
                        </div>
                    </div>
                    <div class="item">
                        <div class="k">优先级</div>
                        <div class="v">
                            <OptTag :value="detail.prio" />
                        </div>
                    </div>
                    <div class="item">
                        <div class="k">模块</div>
                        <div class="v">{{ detail.moduleName || opts.moduleName(detail.moduleId) || '未归类' }}</div>
                    </div>
                    <div class="item">
                        <div class="k">类型</div>
                        <div class="v">
                            <span class="ttag" :class="TYPE_CLS[detail.type]">{{ detail.type }}</span>
                        </div>
                    </div>
                    <div class="item">
                        <div class="k">负责人</div>
                        <div class="v">{{ mmap[detail.owner]?.name }} · {{ mmap[detail.owner]?.role }}</div>
                    </div>
                    <div class="item">
                        <div class="k">来源</div>
                        <div class="v">{{ detail.source }}</div>
                    </div>
                    <div class="item">
                        <div class="k">目标迭代</div>
                        <div class="v">{{ detail.iter }}</div>
                    </div>
                    <div class="item">
                        <div class="k">预估规模</div>
                        <div class="v">{{ detail.estimate }} 点</div>
                    </div>
                    <div class="item">
                        <div class="k">目标版本</div>
                        <div class="v">{{ detail.release }}</div>
                    </div>
                    <div class="item">
                        <div class="k">创建时间</div>
                        <div class="v">{{ detail.created }}</div>
                    </div>
                </div>
            </template>
            <template #footer>
                <button v-if="canManage" class="ghost-btn danger" @click="confirmDel = true">删除</button>
                <button class="btn" @click="startEdit">编辑</button>
            </template>
        </AppModal>

        <!-- 编辑弹窗（叠在详情之上，不关闭详情） -->
        <AppModal v-if="editing && detail" :title="'编辑需求 ' + detail.id" :z-index="1010" @close="editing = false">
            <div class="field">
                <label class="field-label">需求标题<span class="req">*</span></label>
                <input class="field-input" :class="{ invalid: editErr }" v-model="editForm.title" @input="editErr = ''" />
                <div v-if="editErr" class="field-error">{{ editErr }}</div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">类型</label>
                    <select class="field-select" v-model="editForm.type">
                        <option>功能</option>
                        <option>优化</option>
                        <option>技术</option>
                        <option>体验</option>
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
                    <label class="field-label">业务模块</label>
                    <select class="field-select" v-model="editForm.moduleId">
                        <option value="">未归类</option>
                        <option v-for="m in opts.modules" :key="m.id" :value="m.id">{{ m.name }}</option>
                    </select>
                </div>
                <div class="field">
                    <label class="field-label">负责人</label>
                    <select class="field-select" v-model="editForm.owner">
                        <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                    </select>
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">目标迭代</label>
                    <select class="field-select" v-model="editForm.iter">
                        <option v-for="o in iters.options" :key="o.code" :value="o.code">{{ o.label }}</option>
                        <option v-if="editForm.iter && !iters.options.some((o) => o.code === editForm.iter)" :value="editForm.iter">{{ editForm.iter }}（已结束）</option>
                    </select>
                </div>
                <div class="field">
                    <label class="field-label">目标版本</label>
                    <input class="field-input" v-model="editForm.release" placeholder="如 v2.24.0" />
                </div>
            </div>
            <div class="field">
                <label class="field-label">描述</label>
                <AppEditor v-model="editForm.desc" :min-height="220" />
            </div>
            <template #footer>
                <button class="ghost-btn" @click="editing = false">取消</button>
                <button class="btn" @click="saveEdit">保存</button>
            </template>
        </AppModal>

        <!-- AI 拆分任务：草稿维护弹窗（叠在详情之上，人工确认后批量创建） -->
        <AppModal
            v-if="splitOpen && detail"
            title="AI 拆分任务"
            :sub="'需求 ' + detail.id + ' · 预览与调整后批量创建，AI 不会直接写入'"
            :width="1080"
            :z-index="1010"
            @close="closeSplit"
        >
            <div v-if="splitLoading" class="ai-loading">
                <div class="ai-loading-head">
                    <div class="ai-spin"></div>
                    <div>
                        <div class="ai-loading-title">正在拆分需求…</div>
                        <div class="ai-loading-sub">AI 正在阅读需求描述与验收标准，通常需要几秒</div>
                    </div>
                </div>
                <div class="ai-table ai-skeleton-table">
                    <div v-for="n in 3" :key="n" class="ai-skeleton-row">
                        <span class="sk sk-check"></span>
                        <div class="sk-main">
                            <span class="sk sk-title"></span>
                            <span class="sk sk-desc"></span>
                        </div>
                        <span class="sk sk-pill"></span>
                        <span class="sk sk-pill"></span>
                        <span class="sk sk-num"></span>
                        <span class="sk sk-pill"></span>
                        <span class="sk sk-date"></span>
                    </div>
                </div>
            </div>
            <template v-else>
                <div v-if="splitMock" class="ai-note">
                    <span class="ai-note-dot"></span>
                    演示数据 · 未配置 AI Key，当前由本地规则模拟拆分
                </div>

                <div class="ai-toolbar">
                    <label class="ai-all"><input type="checkbox" :checked="allSplitChecked" :indeterminate.prop="someSplitChecked" @change="toggleAllSplit" />全选</label>
                    <span class="ai-count">已选 <b>{{ splitSelected.length }}</b> / {{ splitRows.length }} 项</span>
                    <span class="ai-divider"></span>
                    <span class="ai-chip">合计 {{ splitEstimate }} h</span>
                    <span class="ai-chip">迭代 {{ splitIter }}</span>
                    <div class="ai-toolbar-right">
                        <button class="ai-tool" :disabled="splitLoading" @click="loadSplit">
                            <svg viewBox="0 0 24 24"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6" /></svg>
                            重新生成
                        </button>
                        <button class="ai-tool" @click="addSplitRow">
                            <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>
                            添加任务
                        </button>
                    </div>
                </div>

                <div v-if="splitRows.length" class="ai-table">
                    <div class="ai-thead">
                        <span></span>
                        <span>任务标题 / 说明</span>
                        <span>类型</span>
                        <span>优先级</span>
                        <span class="ta-c">工时（h）</span>
                        <span>负责人</span>
                        <span>截止日期</span>
                        <span></span>
                    </div>
                    <div v-for="(row, i) in splitRows" :key="i" class="ai-row" :class="{ off: !row.sel }">
                        <div class="ai-cell ai-cell-check"><input v-model="row.sel" class="ai-sel" type="checkbox" /></div>
                        <div class="ai-cell-main">
                            <input v-model="row.title" class="ai-in ai-in-title" placeholder="任务标题" />
                            <textarea v-model="row.descText" class="ai-in ai-in-desc" rows="1" placeholder="任务说明（可选）" @input="row.descDirty = true"></textarea>
                        </div>
                        <select v-model="row.type" class="ai-in ai-select">
                            <option v-for="t in opts.taskTypes" :key="t.key" :value="t.key">{{ t.name }}</option>
                        </select>
                        <select v-model="row.prio" class="ai-in ai-select">
                            <option v-for="p in opts.priorities" :key="p.key" :value="p.key">{{ p.name }}</option>
                        </select>
                        <input v-model.number="row.estimate" class="ai-in ai-num" type="number" min="1" max="40" />
                        <select v-model="row.assignee" class="ai-in ai-select">
                            <option value="">未指派</option>
                            <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }}</option>
                        </select>
                        <input v-model="row.due" class="ai-in ai-date" type="date" />
                        <div class="ai-cell ai-cell-del">
                            <button class="ai-del" title="移除该任务" @click="splitRows.splice(i, 1)">
                                <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                            </button>
                        </div>
                    </div>
                </div>
                <div v-else class="ai-empty">
                    <div class="ai-empty-title">没有可创建的任务</div>
                    <div class="ai-empty-sub">{{ splitErr || '调整需求描述后重新生成试试' }}</div>
                    <button class="ghost-btn" @click="loadSplit">重新生成</button>
                </div>
            </template>
            <template #footer>
                <span v-if="splitErr && splitRows.length" class="ai-foot-err">{{ splitErr }}</span>
                <button class="ghost-btn" :disabled="splitCreating" @click="closeSplit">取消</button>
                <button v-if="!splitLoading && splitRows.length" class="btn" :disabled="splitCreating || !splitSelected.length" @click="createSplitTasks">
                    {{ splitCreating ? '创建中…' : '创建 ' + splitSelected.length + ' 个任务' }}
                </button>
            </template>
        </AppModal>

        <!-- 删除确认 -->
        <AppConfirm
            v-if="confirmDel && detail"
            title="删除需求"
            :text="'确定删除需求 ' + detail.id + '「' + detail.title + '」？删除后不可恢复。'"
            confirm-text="删除"
            @confirm="doDelete"
            @close="confirmDel = false"
        />

        <!-- 批量删除确认 -->
        <AppConfirm
            v-if="confirmBulkDel"
            title="批量删除需求"
            :text="'确定删除已选中的 ' + selected.length + ' 个需求？删除后不可恢复。'"
            confirm-text="删除"
            @confirm="bulkDelete"
            @close="confirmBulkDel = false"
        />
    </template>
</template>

<style scoped>
.f-search {
    width: 220px;
    height: 32px;
    font-size:13px;
}
.col-check {
    width: 34px;
    flex: none;
}
.col-check input {
    display: block;
    width: 15px;
    height: 15px;
    accent-color: var(--primary);
    cursor: pointer;
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
.col-type {
    width: 52px;
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
    width: 100px;
    flex: none;
}
.col-src {
    width: 90px;
    flex: none;
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
.col-iter {
    width: 80px;
    flex: none;
}
.col-est {
    width: 64px;
    flex: none;
    text-align: center;
}
.col-date {
    width: 150px;
    flex: none;
    font-variant-numeric: tabular-nums;
}

.rid {
    font-size:13px;
    color: var(--t3);
}
.rtitle {
    font-size:14px;
    color: var(--t1);
}
.owner-cell {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size:13px;
    color: var(--t2);
}
.col-src,
.col-iter {
    font-size: 12px;
    color: var(--t2);
}
.col-est {
    font-size: 12px;
    color: var(--t2);
    font-variant-numeric: tabular-nums;
}
.col-date {
    font-size: 12px;
    color: var(--t3);
}
.status.pending {
    background: var(--track);
    color: var(--t2);
}
.status.scheduled {
    background: var(--primary-bg);
    color: var(--primary);
}
.status.doing {
    background: var(--amber-bg);
    color: var(--amber);
}
.status.done {
    background: var(--green-bg);
    color: var(--green);
}
.ttag {
    display: inline-flex;
    font-size:12px;
    padding: 1px 7px;
    border-radius: 5px;
    font-weight:400;
    background: var(--track);
    color: var(--t2);
}
.ttag.fn {
    background: var(--primary-bg);
    color: var(--primary);
}
.ttag.opt {
    background: var(--green-bg);
    color: var(--green);
}
.ttag.tech {
    background: var(--amber-bg);
    color: var(--amber);
}
.empty {
    padding: 24px;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}

/* 详情右侧属性栏 */
.flow-btns {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 6px;
}
.flow-btn {
    font-size: 12px;
    padding: 5px 0;
    border: 1px solid var(--border);
    border-radius: 7px;
    background: var(--card);
    color: var(--t2);
    cursor: pointer;
    font-family: inherit;
}
.flow-btn.cur {
    background: var(--primary-fill);
    border-color: var(--primary-fill);
    color: #fff;
    font-weight:400;
    cursor: default;
}
.rail-kv {
    margin-top: 16px;
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

/* 详情头部布局见 global.css 的 .dhead */
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
    transition: border-color 0.15s var(--ease), color 0.15s var(--ease), background 0.15s var(--ease);
}
.watch-btn:hover {
    border-color: var(--amber);
    color: var(--amber);
}
.watch-btn.on {
    background: var(--primary-bg);
    border-color: var(--primary);
    color: var(--primary);
}

/* 验收标准 */
.accept-empty {
    font-size: 13px;
    color: var(--t3);
}

/* 追踪链：独立卡片，逐项列出、行高宽松 */
.track-card {
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 14px 18px;
}
.tc-title {
    font-size:14px;
    font-weight:700;
    margin-bottom: 8px;
}
.tc-group + .tc-group {
    margin-top: 14px;
}
.tc-label {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 700;
    margin-bottom: 2px;
}
.tc-count {
    font-size:12px;
    font-weight:400;
    background: var(--track);
    color: var(--t2);
    padding: 1px 8px;
    border-radius: 5px;
}
.tc-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 0;
    font-size: 14px;
    line-height: 1.7;
    cursor: pointer;
}
.tc-row + .tc-row {
    border-top: 1px solid var(--border);
}
.tc-id {
    flex: none;
    font-size:14px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
}
.tc-name {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    color: var(--t1);
}
.tc-owner {
    flex: none;
    font-size: 13px;
    color: var(--t2);
}
.tc-none {
    font-size:14px;
    color: var(--t3);
    padding: 4px 0;
}
.ghost-btn.danger {
    color: var(--red);
}

/* 底部批量操作条 */
.batch-bar {
    position: fixed;
    left: 50%;
    bottom: 24px;
    transform: translateX(-50%);
    z-index: 900;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 16px;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
    box-shadow: var(--shadow-lg);
}
.batch-n {
    font-size: 13px;
    font-weight:700;
}
.batch-sel {
    height: 30px;
    min-width: 110px;
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
    color: var(--red);
}
.batch-clear {
    border: none;
    background: none;
    padding: 0;
    font-size:13px;
    color: var(--t3);
    cursor: pointer;
    font-family: inherit;
}

/* ===== AI 拆分任务 ===== */
.ai-btn {
    display: flex;
    align-items: center;
    gap: 5px;
    flex: none;
    height: 28px;
    padding: 0 10px;
    border: 1px solid rgba(51, 112, 255, 0.35);
    border-radius: 7px;
    background: var(--card);
    color: var(--primary);
    font-size:13px;
    font-family: inherit;
    cursor: pointer;
    transition: border-color 0.15s var(--ease), color 0.15s var(--ease), background 0.15s var(--ease);
}
.ai-btn:hover {
    color: var(--primary);
    border-color: var(--primary);
    background: var(--primary-soft);
}
.ai-btn svg {
    width: 13px;
    height: 13px;
    stroke: currentColor;
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
}
.ai-spin {
    width: 20px;
    height: 20px;
    flex: none;
    border: 2px solid var(--border);
    border-top-color: var(--primary);
    border-radius: 50%;
    animation: ai-spin 0.7s linear infinite;
}
@keyframes ai-spin {
    to {
        transform: rotate(360deg);
    }
}

/* 顶部提示 / 状态 */
.ai-note {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
    padding: 8px 12px;
    border-radius: 8px;
    background: var(--primary-soft);
    color: var(--t2);
    font-size: 12px;
    line-height: 1.5;
}
.ai-note-dot {
    width: 6px;
    height: 6px;
    flex: none;
    border-radius: 50%;
    background: var(--primary);
}
.ai-loading {
    padding: 2px 0;
}
.ai-loading-head {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 14px;
}
.ai-loading-title {
    font-size: 13px;
    font-weight:700;
    color: var(--t1);
}
.ai-loading-sub {
    margin-top: 2px;
    font-size: 12px;
    color: var(--t3);
}

/* 工具栏 */
.ai-toolbar {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
    margin-bottom: 10px;
    padding: 8px 12px;
    border-radius: 10px;
    background: var(--track);
    color: var(--t2);
    font-size: 12px;
}
.ai-all {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--t1);
    cursor: pointer;
}
.ai-all input {
    width: 15px;
    height: 15px;
    accent-color: var(--primary);
    cursor: pointer;
}
.ai-count b {
    color: var(--t1);
    font-weight:700;
    font-variant-numeric: tabular-nums;
}
.ai-divider {
    width: 1px;
    height: 14px;
    background: var(--border);
}
.ai-chip {
    padding: 2px 8px;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--card);
    color: var(--t2);
    font-variant-numeric: tabular-nums;
}
.ai-toolbar-right {
    display: flex;
    gap: 6px;
    margin-left: auto;
}
.ai-tool {
    display: flex;
    align-items: center;
    gap: 5px;
    height: 26px;
    padding: 0 9px;
    border: 1px solid transparent;
    border-radius: 6px;
    background: none;
    color: var(--t2);
    font-size: 12px;
    font-family: inherit;
    cursor: pointer;
    transition: background 0.15s var(--ease), border-color 0.15s var(--ease), color 0.15s var(--ease);
}
.ai-tool:hover {
    border-color: var(--border);
    background: var(--card);
    color: var(--t1);
}
.ai-tool:disabled {
    opacity: 0.5;
    cursor: default;
}
.ai-tool svg {
    width: 13px;
    height: 13px;
    stroke: currentColor;
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}

/* 草稿表格：列与表头共用一套网格 */
.ai-table {
    border: 1px solid var(--border);
    border-radius: 10px;
    background: var(--card);
    overflow-x: auto;
    overflow-y: hidden;
}
.ai-table::-webkit-scrollbar {
    height: 8px;
}
.ai-table::-webkit-scrollbar-thumb {
    border-radius: 4px;
    background: var(--border);
}
.ai-table::-webkit-scrollbar-track {
    background: transparent;
}
.ai-thead,
.ai-row {
    display: grid;
    grid-template-columns: 26px minmax(0, 1fr) 88px 92px 60px 118px 132px 30px;
    gap: 8px;
    min-width: 820px;
    padding: 0 12px;
}
.ai-thead {
    align-items: center;
    height: 36px;
    border-bottom: 1px solid var(--border);
    background: var(--track);
    color: var(--t3);
    font-size:12px;
    font-weight:400;
}
.ta-c {
    text-align: center;
}
.ai-row {
    align-items: center;
    padding-top: 8px;
    padding-bottom: 8px;
    border-bottom: 1px solid var(--border);
    transition: background 0.15s var(--ease), opacity 0.15s var(--ease);
}
.ai-row:last-child {
    border-bottom: none;
}
.ai-row:hover {
    background: var(--primary-faint);
}
.ai-row.off {
    opacity: 0.5;
    background: var(--track);
}
.ai-cell {
    display: flex;
    align-items: center;
    min-height: 28px;
}
.ai-cell-check {
    justify-content: center;
}
.ai-cell-del {
    justify-content: center;
}
.ai-sel {
    width: 15px;
    height: 15px;
    accent-color: var(--primary);
    cursor: pointer;
}
.ai-cell-main {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
}

/* 表格内输入：默认无边框，悬停/聚焦再浮现 */
.ai-in {
    width: 100%;
    min-width: 0;
    height: 28px;
    padding: 0 7px;
    border: 1px solid transparent;
    border-radius: 6px;
    background: transparent;
    color: var(--t1);
    font-size:13px;
    font-family: inherit;
    line-height: 1.5;
    outline: none;
    transition: border-color 0.15s var(--ease), background 0.15s var(--ease), box-shadow 0.15s var(--ease);
}
.ai-in::placeholder {
    color: var(--t3);
}
.ai-in:hover {
    border-color: var(--border);
    background: var(--card);
}
.ai-in:focus {
    border-color: var(--primary);
    background: var(--card);
    box-shadow: 0 0 0 3px var(--ring);
}
.ai-in-title {
    font-weight:400;
}
.ai-in-desc {
    min-height: 54px;
    padding: 4px 7px;
    color: var(--t2);
    font-size: 12px;
    line-height: 18px;
    resize: none;
    overflow: auto;
    field-sizing: content;
    max-height: 126px;
}
.ai-select {
    padding-right: 2px;
    cursor: pointer;
}
.ai-num {
    padding: 0 4px;
    text-align: center;
    font-variant-numeric: tabular-nums;
}
.ai-num::-webkit-outer-spin-button,
.ai-num::-webkit-inner-spin-button {
    margin: 0;
    -webkit-appearance: none;
}
.ai-date {
    padding: 0 4px;
    font-variant-numeric: tabular-nums;
}
.ai-date::-webkit-calendar-picker-indicator {
    opacity: 0.45;
    cursor: pointer;
}
.ai-date::-webkit-calendar-picker-indicator:hover {
    opacity: 1;
}
.ai-del {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    flex: none;
    border: none;
    border-radius: 6px;
    background: none;
    color: var(--t3);
    cursor: pointer;
    transition: background 0.15s var(--ease), color 0.15s var(--ease);
}
.ai-del:hover {
    background: var(--red-bg);
    color: var(--red);
}
.ai-del svg {
    width: 14px;
    height: 14px;
    stroke: currentColor;
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
}
.ai-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 48px 0;
    text-align: center;
}
.ai-empty-title {
    font-size:14px;
    font-weight:700;
    color: var(--t1);
}
.ai-empty-sub {
    margin: 6px 0 14px;
    font-size: 12px;
    color: var(--t3);
}
.ai-foot-err {
    margin-right: auto;
    align-self: center;
    color: var(--red);
    font-size: 12px;
    line-height: 1.4;
}

/* 骨架屏 */
.ai-skeleton-row {
    display: grid;
    grid-template-columns: 26px minmax(0, 1fr) 88px 92px 60px 118px 132px;
    align-items: center;
    gap: 8px;
    min-width: 820px;
    padding: 13px 12px;
    border-bottom: 1px solid var(--border);
}
.ai-skeleton-row:last-child {
    border-bottom: none;
}
.sk {
    display: block;
    height: 12px;
    border-radius: 4px;
    background: linear-gradient(90deg, var(--track) 25%, var(--border) 37%, var(--track) 63%);
    background-size: 400% 100%;
    animation: ai-shimmer 1.4s ease infinite;
}
.sk-check {
    width: 15px;
    height: 15px;
    justify-self: center;
}
.sk-main {
    display: flex;
    flex-direction: column;
    gap: 7px;
}
.sk-title {
    width: 52%;
    height: 12px;
}
.sk-desc {
    width: 78%;
    height: 10px;
}
.sk-pill {
    height: 18px;
    border-radius: 999px;
}
.sk-num {
    width: 30px;
    height: 14px;
    justify-self: center;
}
.sk-date {
    height: 14px;
}
@keyframes ai-shimmer {
    from {
        background-position: 100% 0;
    }
    to {
        background-position: 0 0;
    }
}
@media (max-width: 900px) {
    .ai-toolbar-right {
        flex-basis: 100%;
        margin-left: 0;
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
    .col-type,
    .col-prio,
    .col-status,
    .col-owner,
    .col-src,
    .col-mod,
    .col-iter,
    .col-est,
    .col-date {
        width: auto;
        text-align: left;
    }
}
</style>
