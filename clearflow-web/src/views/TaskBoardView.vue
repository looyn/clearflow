<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api';
import { useAuthStore } from '../stores/auth';
import { usePermStore } from '../stores/perm';
const CURRENT_USER = useAuthStore().user?.id ?? '';
const perm = usePermStore(); // 创建/编辑入口显隐（与后端 @RequireProject 对齐）
// 删除任务需「任务看板」管理级（与后端 DELETE 接口同口径）
const canManage = computed(() => perm.canManage('任务看板'));
import { useUiStore } from '../stores/ui';
import LoadingMask from '../components/LoadingMask.vue';
import AppModal from '../components/AppModal.vue';
import AppConfirm from '../components/AppConfirm.vue';
import AppEditor from '../components/AppEditor.vue';
import AppUpload from '../components/AppUpload.vue';
import AppChecklist from '../components/AppChecklist.vue';
import AppComments from '../components/AppComments.vue';
import ActivityTimeline from '../components/ActivityTimeline.vue';
import AppPager from '../components/AppPager.vue';
import BoardColumnList from '../components/BoardColumnList.vue';
import { useBoardColumns } from '../composables/useBoardColumns';
import OptTag from '../components/OptTag.vue';
import { useOptionsStore } from '../stores/options';
import { useIterationsStore } from '../stores/iterations';
import { useMembersStore } from '../stores/members';
import IterCountdown from '../components/IterCountdown.vue';
import PageCreateButton from '../components/PageCreateButton.vue';

const opts = useOptionsStore(); // 项目设置里的选项配置（优先级/严重度/任务类型/标签）
const iters = useIterationsStore(); // 迭代筛选/下拉候选与页头当前迭代
const members = useMembersStore(); // 成员映射（负责人下拉/卡片头像）

const ui = useUiStore();
const route = useRoute();
const router = useRouter();
const loading = ref(true);
const mmap = computed(() => members.map); // 成员映射（共享 store）
const reqs = ref([]); // 关联需求候选（打开详情时按需拉取）
// 看板列 = 项目设置 → 工作流（任务状态）：配置改了即时生效，无需重进页面
const wfColumns = computed(() => opts.statuses('task'));

onMounted(async () => {
    // 深链：focus 打开详情，iter 从度量图下钻时带上迭代筛选
    if (route.query.iter) fIter.value = route.query.iter;
    if (route.query.module) fModule.value = route.query.module;
    if (route.query.status) fStatus.value = route.query.status;
    if (route.query.prio) fPrio.value = route.query.prio;
    if (route.query.assignee) fAssignee.value = route.query.assignee;
    if (route.query.q) fKw.value = route.query.q;
    if (route.query.sort) sortKey.value = route.query.sort;
    if (route.query.view === 'list') view.value = 'list';
    await fetchData();
    if (route.query.focus) openDetail(route.query.focus);
    loading.value = false;
});
watch(() => ui.refreshTick, () => fetchData().catch(() => {}));
const mmapArr = computed(() => Object.values(mmap.value));

// ===== 双视图（看板 / 列表）=====
const view = ref('board');

// 状态名与流转顺序来自项目设置 → 工作流（任务状态）：顺序即流转顺序，最后一档为终态
const taskKeys = computed(() => opts.keysOf('task'));
const taskTerminal = computed(() => opts.terminalOf('task'));
const taskName = (k) => opts.nameOf('task', k);

// ===== 筛选（关键字 / 状态 / 优先级 / 负责人 / 迭代）=====
const fStatus = ref('');
const fPrio = ref('');
const fAssignee = ref('');
const fIter = ref('');
const fModule = ref('');
const fKw = ref('');
const filterActive = computed(() => fStatus.value !== '' || fPrio.value !== '' || fAssignee.value !== '' || fIter.value !== '' || fModule.value !== '' || fKw.value.trim() !== '');
const prioOptions = computed(() => [{ value: '', label: '全部' }, ...opts.priorities.map((p) => ({ value: p.key, label: p.name }))]);
const prioColor = computed(() => opts.priorities.find((p) => p.key === fPrio.value)?.color || 'transparent');

const sortKey = ref('default');
// 筛选与排序条件下推服务端：看板按列窗口取数，列表按页取数
const filterParams = computed(() => ({
    q: fKw.value.trim(),
    status: fStatus.value,
    prio: fPrio.value,
    assignee: fAssignee.value,
    iter: fIter.value,
    module: fModule.value,
    sort: sortKey.value
}));
const { loadingAll, reload, refresh, loadMore, moveLocal, find: findLoaded, colOf, totalCount } = useBoardColumns(
    (limit) => api.getBoardColumns(filterParams.value, limit),
    (status, offset, limit) => api.getBoardColumn(status, offset, filterParams.value, limit)
);

async function fetchData() {
    await reload();
    if (view.value === 'list') await fetchList();
}

const statusOptions = computed(() => [
    { value: '', label: `全部 ${totalCount()}` },
    ...taskKeys.value.map((k) => ({ value: k, label: `${taskName(k)} ${colOf(k)?.total ?? 0}` }))
]);

// 看板列由工作流配置驱动：列头 name/color 取自 settings.workflow.columns，卡片取该列已加载窗口
const shownColumns = computed(() =>
    wfColumns.value.map((cfg) => ({
        ...cfg,
        col: colOf(cfg.key) || { key: cfg.key, total: 0, items: [], hasMore: false, loading: false }
    }))
);

// ===== 列表视图：服务端分页 =====
const listRows = ref([]);
const listTotal = ref(0);
const page = ref(1);
const pageSize = ref(10);
const paged = computed(() => listRows.value);
async function fetchList() {
    const r = await api.getTasks(filterParams.value, page.value, pageSize.value);
    listRows.value = r.list || [];
    listTotal.value = r.total || 0;
    if (!listRows.value.length && page.value > 1) page.value = 1; // 删除后页码越界回收
}
watch([fStatus, fPrio, fAssignee, fIter, fModule, fKw, sortKey], () => {
    page.value = 1;
    fetchData().catch(() => {});
}); // 筛选/排序变化：回到第一页并重取列窗口与列表
watch([page, pageSize], () => {
    if (view.value === 'list') fetchList().catch(() => {});
});
watch(view, (v) => {
    if (v === 'list') fetchList().catch(() => {});
});

// ===== 拖拽（跨列换状态 + 同列上下排序）=====
const dragId = ref(null);
const dragOver = ref(''); // 悬停中的列（高亮）
const dropRef = ref(''); // 落点参照卡片 id（空 = 追加到列尾）
const dropAfter = ref(false); // 落在参照卡片的下方
function onDragStart(e, t) {
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', t.id);
    dragId.value = t.id;
}
function onCardDragOver(e, t) {
    if (!dragId.value || t.id === dragId.value) return;
    const r = e.currentTarget.getBoundingClientRect();
    dropRef.value = t.id;
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
    // 跨列先走领域 update（保留「状态 X → Y」动态与指派通知），再按锚点落位
    try {
        if (fromKey !== colKey) await api.updateTask(id, { status: colKey });
        await api.moveCard('task', colKey, id, anchor, after);
    } finally {
        await refresh().catch(() => {}); // 被服务端拒绝（非法流转/无权限）时也要回到真实顺序
    }
}

// ===== 详情 / 编辑 =====
const detailId = ref(null);
// 深链 focus 与前置任务可能不在任何列窗口里，按 id 单独补拉
const extraTasks = ref({});
const detail = computed(() => findLoaded(detailId.value) || extraTasks.value[detailId.value] || null);
async function loadExtra(id) {
    if (!id || findLoaded(id) || extraTasks.value[id]) return;
    try {
        extraTasks.value = { ...extraTasks.value, [id]: await api.getTask(id) };
    } catch {
        /* 无权限或已删除：详情保持空态，由弹窗提示 */
    }
}
function openDetail(id) {
    detailId.value = id;
}
watch(detailId, (id) => loadExtra(id));
watch(detail, (d) => (d?.predIds || []).forEach(loadExtra), { immediate: true });
const editing = ref(false);
const confirmDel = ref(false);
const editForm = reactive({ title: '', type: 'fe', prio: 'p1', assignee: 'm1', iter: '', due: '', estimate: 0, reqId: null, moduleId: '', labels: [], desc: '' });
const editErr = ref('');
// 详情/编辑需要关联需求候选：打开详情时按需拉取（页面打开不再预取）
// 任务看板本轮不改造，候选仍按前 200 条取（缺陷同理，见 api.getDefects）
watch(detailId, async (id) => {
    if (!id) return;
    reqs.value = (await api.getRequirementOptions('', 1, 200)).list || [];
});
// 编辑弹窗的标签候选：来自「项目设置 → 选项配置 → 标签管理」
const labelSuggest = computed(() => opts.labels.filter((l) => !(editForm.labels || []).includes(l)).slice(0, 8));

function startEdit() {
    const due = detail.value.due;
    Object.assign(editForm, {
        title: detail.value.title,
        type: detail.value.type,
        prio: detail.value.prio,
        assignee: detail.value.assignee,
        iter: detail.value.iter,
        due: due && due !== '—' ? due.replace(' ', 'T').slice(0, 16) : '',
        estimate: detail.value.estimate,
        reqId: detail.value.reqId,
        moduleId: detail.value.moduleId ?? '',
        labels: [...(detail.value.labels || [])],
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
    const due = editForm.due;
    await api.updateTask(detailId.value, {
        ...editForm,
        due: due ? due.replace('T', ' ') + ':00' : ''
    });
    editing.value = false;
    await fetchData();
}

// ===== 状态流转 / 删除 =====
async function setStatus(s) {
    if (!detail.value || detail.value.status === s) return;
    await api.updateTask(detailId.value, { status: s });
    await fetchData();
}
async function doDelete() {
    const id = detailId.value;
    confirmDel.value = false;
    detailId.value = null;
    editing.value = false;
    await api.deleteTask(id);
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
    await api.toggleWatch('task', detailId.value);
    await fetchData();
}

// ===== 子任务（AppChecklist 受控，diff 后走端点并重拉任务）=====
const subtasks = ref([]);
function syncSubtasks() {
    subtasks.value = detail.value ? [...detail.value.subtasks] : [];
}
// ===== 附件（AppUpload 受控，diff 后逐个持久化并重拉实体）=====
const attachList = ref([]);
const attachIds = ref([]); // 上次同步到服务端的附件 id
const attachSaving = ref(false);
const attachErr = ref('');
watch(detailId, () => {
    syncSubtasks();
    attachList.value = detail.value ? [...detail.value.attachments] : [];
    attachIds.value = attachList.value.map((x) => x.id);
});
async function onSubtasksChange(next) {
    const prev = subtasks.value;
    subtasks.value = next;
    const tid = detailId.value;
    const prevMap = Object.fromEntries(prev.map((x) => [x.id, x]));
    const nextIds = next.map((x) => x.id);
    for (const p of prev) if (!nextIds.includes(p.id)) await api.removeSubtask(tid, p.id);
    for (const n of next) {
        const p = prevMap[n.id];
        if (!p) await api.addSubtask(tid, n.title);
        else if (p.done !== n.done) await api.toggleSubtask(tid, n.id);
    }
    await fetchData();
    syncSubtasks(); // 以任务实体上的 subtasks 为唯一数据源
}
async function onAttachChange() {
    const now = attachList.value;
    const nowIds = now.map((x) => x.id);
    const added = now.filter((x) => !attachIds.value.includes(x.id));
    const removed = attachIds.value.filter((id) => !nowIds.includes(id));
    if (!added.length && !removed.length) return;
    const tid = detailId.value;
    attachSaving.value = true;
    attachErr.value = '';
    try {
        for (const it of added) await api.saveAttachment('task', tid, it);
        for (const id of removed) await api.removeAttachment('task', tid, id);
    } catch (e) {
        attachErr.value = '附件上传失败：' + (e?.message || '请稍后重试');
    } finally {
        attachSaving.value = false;
        await fetchData().catch(() => {});
        attachList.value = detail.value ? [...detail.value.attachments] : [];
        attachIds.value = attachList.value.map((x) => x.id);
    }
}

// ===== 关联需求选择（编辑弹窗） =====
const showEditReqPicker = ref(false);
const editReqSearch = ref('');
const editReqPage = ref(1);
const editReqPageSize = ref(10);
const editReqFiltered = computed(() => {
    const q = editReqSearch.value.trim().toLowerCase();
    return reqs.value.filter((r) => !q || (r.id + r.title).toLowerCase().includes(q));
});
const editReqPaged = computed(() => {
    const start = (editReqPage.value - 1) * editReqPageSize.value;
    return editReqFiltered.value.slice(start, start + editReqPageSize.value);
});
function pickEditReq(id) {
    editForm.reqId = id;
    showEditReqPicker.value = false;
    editReqSearch.value = '';
    editReqPage.value = 1;
}

// 前置任务：选择入口在「创建任务」表单，详情只做展示
const predTasks = computed(() =>
    (detail.value?.predIds || []) // 已选前置任务本体列表（展示编号+标题+状态等）
        .map((id) => findLoaded(id) || extraTasks.value[id])
        .filter(Boolean)
);

// ===== 卡片跳转 =====
function goReq(id) {
    router.push({ path: '/requirements', query: { focus: id } });
}
function goPred(id) {
    detailId.value = id; // 已在看板页时直接切换，深链同时更新 URL
    router.push({ path: '/board', query: { focus: id } }).catch(() => {});
}
// 子任务进度
const subDone = (t) => (t.subtasks || []).filter((s) => s.done).length;
const subPct = (t) => (t.subtasks && t.subtasks.length ? Math.round((subDone(t) / t.subtasks.length) * 100) : 0);

// ===== 列表批量操作 =====
const selected = ref([]);
// 全选作用于当前页，且不清掉其他页已选中的（跨页保留选择）
const allChecked = computed(() => paged.value.length > 0 && paged.value.every((t) => selected.value.includes(t.id)));
function toggleAll(e) {
    const ids = paged.value.map((t) => t.id);
    if (e.target.checked) selected.value = [...new Set([...selected.value, ...ids])];
    else selected.value = selected.value.filter((id) => !ids.includes(id));
}
const bulkStatus = ref('');
const bulkOwner = ref('');
const bulkModule = ref('');
const confirmBulkDel = ref(false);
async function bulkSetStatus() {
    if (!bulkStatus.value) return;
    await api.bulkUpdate('task', selected.value, { status: bulkStatus.value });
    bulkStatus.value = '';
    selected.value = [];
}
async function bulkSetOwner() {
    if (!bulkOwner.value) return;
    await api.bulkUpdate('task', selected.value, { assignee: bulkOwner.value });
    bulkOwner.value = '';
    selected.value = [];
}
async function bulkSetModule() {
    if (!bulkModule.value) return;
    await api.bulkUpdate('task', selected.value, { moduleId: bulkModule.value === '__none' ? null : bulkModule.value });
    bulkModule.value = '';
    selected.value = [];
}
async function bulkDelete() {
    confirmBulkDel.value = false;
    const ids = [...selected.value];
    selected.value = [];
    for (const id of ids) await api.deleteTask(id);
    ui.notifyRefresh();
}

// ===== 状态同步到 URL：刷新 / 分享 / 下钻都保持筛选、视图与打开的详情 =====
function syncQuery() {
    const q = {};
    if (fStatus.value) q.status = fStatus.value;
    if (fPrio.value) q.prio = fPrio.value;
    if (fAssignee.value) q.assignee = fAssignee.value;
    if (fIter.value) q.iter = fIter.value;
    if (fModule.value) q.module = fModule.value;
    if (fKw.value.trim()) q.q = fKw.value.trim();
    if (sortKey.value !== 'default') q.sort = sortKey.value;
    if (view.value === 'list') q.view = 'list';
    if (detailId.value) q.focus = detailId.value;
    router.replace({ path: route.path, query: q }).catch(() => {});
}
watch([fStatus, fPrio, fAssignee, fIter, fModule, fKw, sortKey, view, detailId], syncQuery);
// 同页深链（通知/搜索跳转）也响应：地址栏变化时打开对应详情
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
                <div class="title-row">
                    <h1>任务看板</h1>
                    <div class="view-tabs">
                        <button :class="{ on: view === 'board' }" @click="view = 'board'">看板</button>
                        <button :class="{ on: view === 'list' }" @click="view = 'list'">列表</button>
                    </div>
                </div>
                <div class="page-meta">
                    {{ iters.active ? `${iters.active.code} · ${iters.active.name}` : '迭代视图' }} · {{ totalCount() }} 项任务 · 拖拽卡片流转状态
                    <template v-if="loadingAll"> · 刷新中…</template>
                </div>
            </div>
            <div class="page-right">
                <IterCountdown />
                <PageCreateButton type="task" />
            </div>
        </div>

        <!-- 筛选条 -->
        <div class="filter-bar rise d2">
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
                <select class="filter-select" v-model="fAssignee">
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
                    <option v-for="o in iters.options" :key="o.code" :value="o.code">{{ o.label }}</option>
                </select>
            </div>
            <div class="filter-item">
                <span class="filter-label">关键字</span>
                <input class="field-input f-search" v-model="fKw" placeholder="搜索 ID / 标题 / 标签…" />
            </div>
            <div class="filter-item">
                <span class="filter-label">排序</span>
                <select class="filter-select" v-model="sortKey">
                    <option value="default">默认</option>
                    <option value="due">截止最近</option>
                    <option value="prio">优先级</option>
                    <option value="estimate">预估工时</option>
                    <option value="id">编号</option>
                </select>
            </div>
        </div>

        <!-- 看板 -->
        <div v-if="view === 'board'" class="board rise d3">
            <div
                v-for="col in shownColumns"
                :key="col.key"
                class="col"
                :class="{ dropping: dragOver === col.key }"
                @dragover.prevent="dragOver = col.key"
                @dragleave="dragOver = ''"
                @drop.prevent="onDrop(col.key)"
            >
                <div class="col-head">
                    <span class="col-dot" :style="{ background: col.color }"></span>
                    <span class="nm">{{ col.name }}</span>
                    <span class="ct">{{ col.col.total }}</span>
                    <button v-if="perm.canWrite('任务看板')" class="col-add" title="新建任务" @click.stop="ui.openCreate('task', { status: col.key })">+</button>
                </div>
                <BoardColumnList
                    :items="col.col.items"
                    :total="col.col.total"
                    :has-more="col.col.hasMore"
                    :loading="col.col.loading"
                    @load-more="loadMore(col.key)"
                >
                    <template #item="{ item: t }">
                        <article
                            class="task"
                            :class="{
                                done: t.status === taskTerminal,
                                dragging: dragId === t.id,
                                'drop-before': dropRef === t.id && !dropAfter && dragId !== t.id,
                                'drop-after': dropRef === t.id && dropAfter && dragId !== t.id
                            }"
                            draggable="true"
                            @dragstart="onDragStart($event, t)"
                            @dragend="resetDrag"
                            @dragover="onCardDragOver($event, t)"
                            @click="openDetail(t.id)"
                        >
                            <div class="task-top">
                                <OptTag kind="taskTypes" base="tag" :value="t.type" />
                                <span v-if="t.moduleName" class="mini-chip mod">{{ t.moduleName }}</span>
                                <OptTag v-if="t.status !== taskTerminal" :value="t.prio" />
                                <span v-else class="fin">✓ {{ taskName(taskTerminal) }}</span>
                            </div>
                            <h4>{{ t.title }}</h4>
                            <div v-if="t.labels && t.labels.length" class="labels">
                                <span v-for="l in t.labels" :key="l" class="ltag">{{ l }}</span>
                            </div>
                            <!-- 子任务进度 -->
                            <div v-if="t.subtasks && t.subtasks.length" class="subprog">
                                <span class="sub-n">{{ subDone(t) }}/{{ t.subtasks.length }}</span>
                                <span class="sub-track"><i :style="{ width: subPct(t) + '%' }"></i></span>
                            </div>
                            <div class="task-foot">
                                <span class="ava mini-ava" :class="mmap[t.assignee]?.color">{{ mmap[t.assignee]?.short || '?' }}</span>
                                <span class="who">{{ mmap[t.assignee]?.name || '—' }}</span>
                                <span class="est">{{ t.estimate }}h</span>
                                <button v-if="t.reqId" class="mini-chip req" @click.stop="goReq(t.reqId)">{{ t.reqId }}</button>
                                <button v-for="pid in (t.predIds || []).slice(0, 2)" :key="pid" class="mini-chip pred" @click.stop="goPred(pid)">前置 {{ pid }}</button>
                                <span v-if="(t.predIds || []).length > 2" class="mini-chip pred">+{{ t.predIds.length - 2 }}</span>
                                <span class="due" :class="{ hot: t.hot }">{{ t.due }}</span>
                            </div>
                        </article>
                    </template>
                    <template #empty>
                        <div class="col-more">{{ filterActive ? '无匹配任务' : '暂无任务' }}</div>
                    </template>
                </BoardColumnList>
            </div>
        </div>

        <!-- 列表态 -->
        <div v-else class="card table-card rise d3">
            <div class="table-head">
                <div class="col-check"><input type="checkbox" :checked="allChecked" @change="toggleAll" /></div>
                <div class="col-id">ID</div>
                <div class="col-title">标题</div>
                <div class="col-mod">模块</div>
                <div class="col-status">状态</div>
                <div class="col-owner">负责人</div>
                <div class="col-prio">优先级</div>
                <div class="col-iter">迭代</div>
                <div class="col-due">截止</div>
                <div class="col-est">预估</div>
            </div>

            <div v-for="t in paged" :key="t.id" class="table-row clickable" @click="openDetail(t.id)">
                <div class="col-check" @click.stop><input type="checkbox" :value="t.id" v-model="selected" /></div>
                <div class="col-id t-id">{{ t.id }}</div>
                <div class="col-title">
                    <div class="t-title">{{ t.title }}</div>
                </div>
                <div class="col-mod">{{ t.moduleName || opts.moduleName(t.moduleId) || '—' }}</div>
                <div class="col-status">
                    <span class="status" :class="t.status" :style="opts.styleOf('task', t.status)">{{ taskName(t.status) }}</span>
                </div>
                <div class="col-owner">
                    <div class="owner-cell">
                        <span class="mini-ava ava" :class="mmap[t.assignee]?.color">{{ mmap[t.assignee]?.short || '?' }}</span
                        >{{ mmap[t.assignee]?.name || '—' }}
                    </div>
                </div>
                <div class="col-prio">
                    <OptTag :value="t.prio" />
                </div>
                <div class="col-iter iter-cell">{{ t.iter }}</div>
                <div class="col-due date-cell">{{ t.due }}</div>
                <div class="col-est est-cell">{{ t.estimate }}h</div>
            </div>

            <div v-if="!listRows.length" class="empty">没有符合条件的任务</div>
            <!-- 分页（全站统一组件，仅列表视图） -->
            <AppPager v-else :total="listTotal" :page="page" :page-size="pageSize" @update:page="page = $event" @update:pageSize="pageSize = $event" />

            <!-- 底部批量操作条 -->
            <div v-if="selected.length" class="batch-bar">
                <span class="batch-n">已选 {{ selected.length }} 项</span>
                <select class="batch-sel" v-model="bulkStatus" @change="bulkSetStatus">
                    <option value="">批量状态…</option>
                    <option v-for="s in taskKeys" :key="s" :value="s">{{ taskName(s) }}</option>
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
        </div>

        <!-- 任务详情弹窗（760 双栏 Jira 风格） -->
        <AppModal
            v-if="detail"
            :title="'任务 ' + detail.id"
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

            <!-- 前置任务（描述下方，仅展示；选择入口在创建任务表单） -->
            <div class="dsection">
                <div class="k">前置任务</div>
                <div v-if="predTasks.length" class="pred-list">
                    <div v-for="pt in predTasks" :key="pt.id" class="pred-info">
                        <div class="pi-top">
                            <b class="pi-id" @click="goPred(pt.id)">{{ pt.id }}</b>
                            <span class="status" :class="pt.status" :style="opts.styleOf('task', pt.status)">{{ taskName(pt.status) }}</span>
                            <OptTag :value="pt.prio" />
                        </div>
                        <div class="pi-title" :title="pt.title">{{ pt.title }}</div>
                        <div class="pi-meta">
                            <span>负责人 {{ mmap[pt.assignee]?.name || '—' }}</span>
                            <span>迭代 {{ pt.iter }}</span>
                            <span>截止 {{ pt.due }}</span>
                        </div>
                    </div>
                </div>
                <div v-else class="pred-none">暂无前置任务</div>
            </div>

            <!-- 子任务 -->
            <div class="dsection">
                <AppChecklist :model-value="subtasks" label="子任务" placeholder="输入子任务，回车添加" @update:model-value="onSubtasksChange" />
            </div>

            <!-- 附件 -->
            <div class="dsection">
                <div class="k">附件{{ attachSaving ? '（上传中…）' : '' }}</div>
                <AppUpload v-model="attachList" @change="onAttachChange" />
                <div v-if="attachErr" class="field-error">{{ attachErr }}</div>
            </div>

            <!-- 评论 -->
            <div class="dsection">
                <AppComments kind="task" :ref-id="detail.id" />
            </div>
            <!-- 活动时间线 -->
            <div class="dsection">
                <div class="k">活动</div>
                <ActivityTimeline kind="task" :ref-id="detail.id" :fallback="'创建于 ' + detail.created" />
            </div>
            <template #rail>
                <div class="flow-bar">
                    <button v-for="s in taskKeys" :key="s" class="flow-btn" :class="{ on: detail.status === s }" @click="setStatus(s)">{{ taskName(s) }}</button>
                </div>
                <div class="rail-row">
                    <div class="rail-k">状态</div>
                    <div class="rail-v">{{ taskName(detail.status) }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">负责人</div>
                    <div class="rail-v">{{ mmap[detail.assignee]?.name }} · {{ mmap[detail.assignee]?.role }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">优先级</div>
                    <div class="rail-v">
                        <OptTag :value="detail.prio" />
                    </div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">类型</div>
                    <div class="rail-v">
                        <OptTag kind="taskTypes" base="tag" :value="detail.type" />
                    </div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">业务模块</div>
                    <div class="rail-v">{{ detail.moduleName || opts.moduleName(detail.moduleId) || '未归类' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">所属迭代</div>
                    <div class="rail-v">{{ detail.iter }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">截止日期</div>
                    <div class="rail-v">{{ detail.due }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">预估 · 已投入</div>
                    <div class="rail-v">{{ detail.estimate }}h / {{ detail.spent }}h</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">关联需求</div>
                    <div class="rail-v mono">{{ detail.reqId || '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">标签</div>
                    <div v-if="detail.labels && detail.labels.length" class="rail-tags">
                        <span v-for="l in detail.labels" :key="l" class="ltag">{{ l }}</span>
                    </div>
                    <div v-else class="rail-v">—</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">创建人</div>
                    <div class="rail-v">{{ mmap[detail.creator]?.name || '—' }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">创建时间</div>
                    <div class="rail-v">{{ detail.created }}</div>
                </div>
                <div class="rail-row">
                    <div class="rail-k">更新时间</div>
                    <div class="rail-v">{{ detail.updated }}</div>
                </div>
            </template>
            <template #footer>
                <button v-if="canManage" class="ghost-btn del-text" @click="confirmDel = true">删除</button>
                <button class="ghost-btn" @click="startEdit">编辑</button>
            </template>
        </AppModal>

        <!-- 关联需求搜索弹窗（编辑弹窗内使用） -->
        <Teleport to="body">
            <div v-if="showEditReqPicker" class="picker-overlay">
                <div class="picker-dialog">
                    <div class="picker-head">
                        <span>选择关联需求</span>
                        <button class="picker-close" title="关闭" @click="showEditReqPicker = false">
                            <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                        </button>
                    </div>
                    <input class="field-input picker-search" v-model="editReqSearch" placeholder="搜索需求编号或标题…" autofocus @input="editReqPage = 1" />
                    <div class="picker-list">
                        <div class="picker-item" @click="pickEditReq(null)"><em>不关联</em></div>
                        <div v-for="r in editReqPaged" :key="r.id" class="picker-item" @click="pickEditReq(r.id)">
                            <b>{{ r.id }}</b> {{ r.title }}
                        </div>
                        <div v-if="!editReqFiltered.length" class="picker-empty">无匹配需求</div>
                    </div>
                    <AppPager :total="editReqFiltered.length" :page="editReqPage" :page-size="editReqPageSize" @update:page="editReqPage = $event" @update:pageSize="editReqPageSize = $event" />
                </div>
            </div>
        </Teleport>

        <!-- 编辑弹窗（叠在详情之上） -->
        <AppModal v-if="editing && detail" :title="'编辑任务 ' + detail.id" :z-index="1010" @close="editing = false">
            <div class="field">
                <label class="field-label">任务标题<span class="req">*</span></label>
                <input class="field-input" :class="{ invalid: editErr }" v-model="editForm.title" @input="editErr = ''" />
                <div v-if="editErr" class="field-error">{{ editErr }}</div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">类型</label>
                    <select class="field-select" v-model="editForm.type">
                        <option v-for="t in opts.taskTypes" :key="t.key" :value="t.key">{{ t.name }}</option>
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
                    <label class="field-label">负责人</label>
                    <select class="field-select" v-model="editForm.assignee">
                        <option v-for="m in mmapArr" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                    </select>
                </div>
                <div class="field">
                    <label class="field-label">截止日期</label>
                    <input type="datetime-local" class="field-input" v-model="editForm.due" />
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
                    <label class="field-label">所属迭代</label>
                    <select class="field-select" v-model="editForm.iter">
                        <option v-for="o in iters.options" :key="o.code" :value="o.code">{{ o.label }}</option>
                        <option v-if="editForm.iter && !iters.options.some((o) => o.code === editForm.iter)" :value="editForm.iter">{{ editForm.iter }}（已结束）</option>
                    </select>
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">预估（h）</label>
                    <input class="field-input" type="number" min="0" v-model.number="editForm.estimate" />
                </div>
                <div class="field">
                    <label class="field-label">标签</label>
                    <input
                        class="field-input"
                        :value="editForm.labels.join(' ')"
                        @input="editForm.labels = $event.target.value.split(/[\s,，]+/).filter(Boolean)"
                        placeholder="空格分隔，或点下方标签快捷添加"
                    />
                    <div class="label-suggest">
                        <a v-for="l in labelSuggest" :key="l" class="suggest-chip" @click="editForm.labels = [...editForm.labels, l]">+ {{ l }}</a>
                        <span v-if="!labelSuggest.length" class="suggest-none">项目设置里的标签已全部添加</span>
                    </div>
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field-label">关联需求</label>
                    <div class="picker-input-wrap">
                        <input
                            class="field-input picker-input"
                            readonly
                            :value="editForm.reqId ? reqs.find((r) => r.id === editForm.reqId)?.id + ' ' + reqs.find((r) => r.id === editForm.reqId)?.title : ''"
                            placeholder="搜索选择关联需求…"
                            @click="showEditReqPicker = true"
                        />
                        <button class="picker-search-btn" @click="showEditReqPicker = true" title="搜索关联需求">
                            <svg viewBox="0 0 24 24">
                                <circle cx="11" cy="11" r="8" />
                                <path d="m21 21-4.35-4.35" />
                            </svg>
                        </button>
                    </div>
                </div>
            </div>
            <div class="field">
                <label class="field-label">描述</label>
                <AppEditor v-model="editForm.desc" :min-height="220" placeholder="补充任务描述…" />
            </div>
            <template #footer>
                <button class="ghost-btn" @click="editing = false">取消</button>
                <button class="btn" @click="saveEdit">保存</button>
            </template>
        </AppModal>

        <!-- 删除确认 -->
        <AppConfirm v-if="confirmDel && detail" title="删除任务" :text="`确定删除任务 ${detail.id}「${detail.title}」？删除后不可恢复。`" @confirm="doDelete" @close="confirmDel = false" />

        <!-- 批量删除确认 -->
        <AppConfirm v-if="confirmBulkDel" title="批量删除任务" :text="`确定删除选中的 ${selected.length} 个任务？删除后不可恢复。`" @confirm="bulkDelete" @close="confirmBulkDel = false" />
    </template>
</template>

<style scoped>
/* 标题 + 双视图切换 */
.title-row {
    display: flex;
    align-items: center;
    gap: 14px;
}
.f-search {
    width: 220px;
    height: 32px;
    font-size:13px;
}
.view-tabs {
    display: flex;
    gap: 2px;
    padding: 3px;
    border: 1px solid var(--border);
    border-radius: 9px;
    background: var(--track);
    box-shadow: var(--shadow-xs);
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
    transition: background 0.15s var(--ease), color 0.15s var(--ease), box-shadow 0.2s var(--ease);
}
.view-tabs button:hover {
    color: var(--t1);
}
.view-tabs button.on {
    background: var(--card);
    color: var(--primary);
    font-weight:700;
    box-shadow: var(--shadow-sm);
}

/* 列表态 */
.col-check {
    width: 32px;
    flex: none;
}
.col-id {
    width: 64px;
    flex: none;
    font-variant-numeric: tabular-nums;
}
.col-title {
    flex: 1;
    min-width: 160px;
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
.col-prio {
    width: 78px;
    flex: none;
    text-align: center;
}
.col-iter {
    width: 70px;
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
.col-due {
    width: 70px;
    flex: none;
}
.col-est {
    width: 60px;
    flex: none;
    text-align: right;
}
.t-id {
    font-size:13px;
    color: var(--t3);
}
.t-title {
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
.iter-cell {
    font-size: 12px;
    color: var(--t2);
}
.date-cell {
    font-size: 12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
/* 预估工时是属性值，不用日期的弱化色 */
.est-cell {
    font-size: 12px;
    color: var(--t2);
    font-variant-numeric: tabular-nums;
}
.status.todo {
    background: var(--track);
    color: var(--t2);
}
.status.doing {
    background: var(--primary-bg);
    color: var(--primary);
}
.status.done {
    background: var(--green-bg);
    color: var(--green);
}
.empty {
    padding: 24px;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
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

.board {
    display: flex;
    gap: 16px;
    overflow-x: auto;
    padding-bottom: 6px;
    margin-bottom: 16px;
}
.col {
    flex: 1 0 268px;
    background: var(--board-col);
    border: 1px solid transparent;
    border-radius: var(--radius-lg);
    padding: 10px;
    min-height: 320px;
    transition: background 0.15s var(--ease), border-color 0.15s var(--ease), box-shadow 0.2s var(--ease);
}
.col.dropping {
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
    box-shadow: 0 0 0 3px var(--card);
}
.col-head .nm {
    font-size:14px;
    font-weight:700;
}
.col-head .ct {
    font-size:12px;
    color: var(--t2);
    background: var(--card);
    border-radius: 999px;
    padding: 0 8px;
    line-height: 18px;
    font-variant-numeric: tabular-nums;
    box-shadow: var(--shadow-xs);
}
.col-add {
    flex: none;
    width: 18px;
    height: 18px;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--card);
    color: var(--t3);
    font-size: 12px;
    line-height: 1;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    font-family: inherit;
    transition: background 0.15s var(--ease), color 0.15s var(--ease), border-color 0.15s var(--ease), transform 0.12s var(--ease);
}
.col-add:hover {
    background: var(--primary-fill);
    border-color: var(--primary-fill);
    color: #fff;
    transform: scale(1.08);
}
.task {
    background: var(--card);
    border: 1px solid var(--card-edge);
    border-radius: var(--radius-md);
    padding: 12px 13px;
    box-shadow: var(--shadow-xs);
    cursor: pointer;
    transition: box-shadow 0.2s var(--ease), transform 0.15s var(--ease), border-color 0.15s var(--ease);
}
.task:hover {
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
    border-color: var(--border-accent);
}
/* 拖拽落点指示：卡片上/下沿一条主色线 */
.task.drop-before {
    box-shadow: 0 -3px 0 0 var(--primary);
}
.task.drop-after {
    box-shadow: 0 3px 0 0 var(--primary);
}
.task.dragging {
    opacity: 0.5;
    cursor: grabbing;
    transform: rotate(1.5deg);
}
.task-top {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 4px 6px;
    margin-bottom: 8px;
}
.task h4 {
    font-size:14px;
    font-weight:400;
    line-height: 1.45;
}
.labels {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 7px;
}
.ltag {
    font-size:12px;
    padding: 1px 6px;
    border-radius: 4px;
    background: var(--track);
    color: var(--t2);
}
/* 编辑弹窗：标签快捷添加（候选来自项目设置） */
.label-suggest {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 6px;
}
.suggest-chip {
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 6px;
    background: var(--primary-bg);
    color: var(--primary);
    cursor: pointer;
}
.suggest-none {
    font-size: 12px;
    color: var(--t3);
}
.task-foot {
    display: flex;
    align-items: center;
    gap: 7px;
    margin-top: 10px;
    flex-wrap: wrap;
}
.who {
    font-size: 12px;
    color: var(--t3);
}
.est {
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
/* 需求 / 前置任务 chip（点击跳转） */
.mini-chip {
    border: none;
    padding: 1px 6px;
    border-radius: 4px;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size:12px;
    cursor: pointer;
    font-weight:400;
}
.mini-chip.req {
    background: var(--primary-bg);
    color: var(--primary);
}
.mini-chip.pred {
    background: var(--purple-bg);
    color: var(--purple);
}
.mini-chip.mod {
    background: var(--track);
    color: var(--t2);
    font-family: inherit;
    cursor: default;
}
/* 子任务进度 */
.subprog {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
}
.sub-n {
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.sub-track {
    flex: 1;
    height: 4px;
    border-radius: 2px;
    background: var(--track);
    overflow: hidden;
}
.sub-track i {
    display: block;
    height: 100%;
    border-radius: 2px;
    background: var(--green);
}
.due {
    margin-left: auto;
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.due.hot {
    color: var(--red);
    font-weight:400;
}
.task.done {
    opacity: 0.6;
}
.task.done .fin {
    margin-left: auto;
    font-size:12px;
    color: var(--green);
    font-weight:400;
}
.col-more {
    padding: 10px 8px 4px;
    font-size: 12px;
    color: var(--t3);
    text-align: center;
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
.rail-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 5px;
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
    padding: 0 11px;
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
    font-weight:400;
}

/* 前置任务下拉 */
.pred-select {
    height: 30px;
    margin-top: 4px;
    font-size:13px;
}

.pred-link {
    color: var(--primary);
    cursor: pointer;
    font-weight:400;
}
.pred-link:hover {
    text-decoration: underline;
}

/* 前置任务信息卡（详情区纯展示） */
.pred-list {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 10px;
}
.pred-info {
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 10px 12px;
    background: var(--card);
}
.pi-top {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 6px;
}
.pi-id {
    font-size:13px;
    color: var(--primary);
    font-weight:700;
    cursor: pointer;
}
.pi-title {
    font-size: 13px;
    color: var(--t1);
    line-height: 1.5;
    margin-bottom: 6px;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    line-clamp: 2;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
}
.pi-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    font-size: 12px;
    color: var(--t3);
}
.pred-none {
    font-size: 13px;
    color: var(--t3);
}

/* 输入框+搜索按钮组合 */
.picker-input-wrap {
    display: flex;
    align-items: stretch;
    gap: 0;
    width: 100%;
}
.picker-input {
    flex: 1;
    min-width: 0;
    border-top-right-radius: 0 !important;
    border-bottom-right-radius: 0 !important;
    cursor: pointer;
    height: 30px;
}
.picker-search-btn {
    flex: none;
    width: 32px;
    border: 1px solid var(--border);
    border-left: none;
    border-radius: 0 7px 7px 0;
    background: var(--card);
    color: var(--t3);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;
}
.picker-search-btn svg {
    width: 14px;
    height: 14px;
    stroke: currentColor;
    stroke-width: 2;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.picker-search-btn:hover {
    color: var(--primary);
}

/* 搜索弹窗（共用 pred / req） */
.picker-overlay {
    position: fixed;
    z-index: 11000;
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
.picker-search {
    width: calc(100% - 40px);
    margin: 0 20px 10px;
}
.picker-list {
    overflow-y: auto;
    padding: 0 0 6px;
    flex: 1;
    min-height: 220px;
}
.picker-item {
    padding: 10px 20px;
    font-size: 13px;
    color: var(--t2);
    cursor: pointer;
}
.picker-item b {
    color: var(--t1);
    margin-right: 8px;
    font-weight:700;
}
.picker-item em {
    color: var(--t3);
    font-style: normal;
}
.picker-item:hover {
    background: var(--track);
}
.picker-empty {
    padding: 20px;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}
/* 统一分页条在弹窗内补足左右留白（与标题/列表 20px 内边距对齐） */
.picker-dialog .pager {
    padding: 12px 20px 12px;
}

@media (max-width: 1150px) {
    .col {
        flex-basis: 240px;
    }
}
@media (max-width: 880px) {
    .col {
        flex-basis: 76vw;
    }
}
</style>
