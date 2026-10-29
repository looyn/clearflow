<script setup>
import { ref, computed, onMounted, watch } from 'vue';
import { api } from '../api';
import { useUiStore } from '../stores/ui';
import { usePermStore } from '../stores/perm';
import { useAuthStore } from '../stores/auth';
import LoadingMask from '../components/LoadingMask.vue';
import AppConfirm from '../components/AppConfirm.vue';
import { useProjectsStore } from '../stores/projects';
import { useMembersStore } from '../stores/members';
import AppModal from '../components/AppModal.vue';
import ProjectFormModal from '../components/ProjectFormModal.vue';
import AppPager from '../components/AppPager.vue';

const ui = useUiStore();
const perm = usePermStore();
const auth = useAuthStore(); // 非管理员只看自己负责的项目
const pstore = useProjectsStore(); // 项目列表 + 当前项目（共享；本页是写操作入口）
const mstore = useMembersStore(); // 非管理员的成员精简视图（添加成员用）
// 编辑/管理成员：管理员或该项目负责人；归档/恢复/删除：仅管理员
const canManage = (p) => perm.owns(p.id);
const canDelete = computed(() => perm.isAdmin);
const loading = ref(true);
// 项目卡片分页（管理员看全量；负责人只看自己负责的项目——与旧版客户端过滤同口径，改为传 owner）
const rows = ref([]);
const total = ref(0);
const pool = ref(null); // 概览：可见项目总数 / 各状态计数 / 负责人候选
const allMembers = computed(() => mstore.list); // 名称与头像映射（精简视图，全员可读）
const currentId = computed(() => pstore.currentId);

const filterParams = computed(() => ({
    keyword: kw.value.trim(),
    state: fState.value === 'all' ? '' : fState.value,
    owner: perm.isAdmin ? fOwner.value : auth.user?.id || ''
}));

// 筛选/分页连打时只认最后一次响应
let fetchSeq = 0;
async function fetchData() {
    const seq = ++fetchSeq;
    const [r, s] = await Promise.all([
        api.getProjectsPage(filterParams.value, page.value, pageSize.value),
        api.getProjectStats()
    ]);
    if (seq !== fetchSeq) return;
    rows.value = r.list || [];
    total.value = r.total || 0;
    pool.value = s;
    if (!rows.value.length && page.value > 1) page.value = 1; // 删除后页码越界回收
}
async function loadAll() {
    await Promise.all([pstore.load(), mstore.load(), fetchData()]);
}
onMounted(async () => {
    await loadAll();
    loading.value = false;
});
watch(() => ui.refreshTick, () => { pstore.load().catch(() => {}); fetchData().catch(() => {}); });

const mmap = computed(() => Object.fromEntries(allMembers.value.map((m) => [m.id, m])));

// ===== 状态筛选 + 负责人 + 关键词搜索（全部下推服务端）=====
const TABS = [
    { key: 'all', label: '全部' },
    { key: 'active', label: '进行中' },
    { key: 'planned', label: '规划中' },
    { key: 'archived', label: '已归档' }
];
const fState = ref('all');
const fOwner = ref('');
const kw = ref('');
// 负责人候选来自可见项目的 owner 集合（后端聚合，不依赖当前页）
const ownerOpts = computed(() => (pool.value?.owners || []).map((id) => mmap.value[id]).filter(Boolean));

// ===== 分页（服务端分页，paged 即当前页）=====
const page = ref(1);
const pageSize = ref(10);
const paged = computed(() => rows.value);
watch([fState, fOwner, kw], () => {
    // 回第一页由 page 监听取数，已经是第一页时直接取数，避免重复请求
    if (page.value === 1) fetchData().catch(() => {});
    else page.value = 1;
});
watch([page, pageSize], () => fetchData().catch(() => {}));

const STATE_META = {
    active: { label: '进行中', cls: 'st-active' },
    planned: { label: '规划中', cls: 'st-planned' },
    archived: { label: '已归档', cls: 'st-archived' }
};

// ===== 新建 / 编辑 =====
const formOpen = ref(false);
const formProject = ref(null);
function openCreate() {
    formProject.value = null;
    formOpen.value = true;
}
function openEdit(p) {
    formProject.value = p;
    formOpen.value = true;
}

// ===== 归档 / 恢复 / 删除 / 移除成员（均经 AppConfirm 确认）=====
const confirm = ref(null); // { p, act: 'archive' | 'restore' | 'delete' | 'remove-member', mid? }
function askArchive(p) {
    confirm.value = { p, act: p.state === 'archived' ? 'restore' : 'archive' };
}
function askDelete(p) {
    confirm.value = { p, act: 'delete' };
}
function askRemoveMember(p, mid) {
    confirm.value = { p, act: 'remove-member', mid };
}
async function doConfirm() {
    const { p, act, mid } = confirm.value;
    confirm.value = null;
    if (act === 'archive') await api.archiveProject(p.id);
    else if (act === 'restore') await api.updateProject(p.id, { state: 'active' });
    else if (act === 'remove-member') {
        const ids = pMembers.value.map((m) => m.id).filter((x) => x !== mid);
        await api.updateProject(p.id, { members: ids });
    } else await api.deleteProject(p.id);
    await Promise.all([fetchData(), pstore.load(), refreshMembers()]);
}
const confirmBox = computed(() => {
    const c = confirm.value;
    if (!c) return null;
    const n = `「${c.p.name}」`;
    if (c.act === 'archive') return { title: '归档项目', text: `确定归档项目${n}吗？归档后将不再作为进行中项目展示。`, confirmText: '归档' };
    if (c.act === 'remove-member') return { title: '移除成员', text: `确定将成员「${mmap.value[c.mid]?.name || ''}」移出项目${n}吗？`, confirmText: '移除' };
    if (c.act === 'restore') return { title: '恢复项目', text: `确定将项目${n}恢复为进行中吗？`, confirmText: '恢复' };
    return { title: '删除项目', text: `确定删除项目${n}吗？删除后数据不可恢复。`, confirmText: '删除' };
});

// ===== 项目成员管理（管理弹窗 + 多选 picker）=====
// 成员明细按项目 id 取（管理员可见邮箱），候选列表走服务端搜索 + 分页
const manageP = ref(null); // 管理中的项目（打开时从卡片行播种，标题即时渲染）
const pMembers = ref([]);
let membersSeq = 0;
async function refreshMembers() {
    const seq = ++membersSeq;
    const p = manageP.value;
    if (!p) return;
    const list = await api.getProjectMembers(p.id);
    if (seq !== membersSeq || manageP.value?.id !== p.id) return;
    pMembers.value = list || [];
}
function openManage(p) {
    manageP.value = p;
    pMembers.value = [];
    refreshMembers().catch(() => {}); // 先开弹窗，成员异步回填
}

const addP = ref(null); // 添加成员的目标项目
const addSel = ref([]); // 已选成员 id
const addSearch = ref('');
const addPage = ref(1);
const addPageSize = ref(10);
const addRows = ref([]);
const addTotal = ref(0);
let addSeq = 0;
async function fetchCandidates() {
    if (!addP.value) return;
    const seq = ++addSeq;
    const id = addP.value.id;
    const r = await api.getProjectMemberCandidates(id, addSearch.value.trim(), addPage.value, addPageSize.value);
    if (seq !== addSeq || addP.value?.id !== id) return;
    addRows.value = r.list || [];
    addTotal.value = r.total || 0;
}
function openAdd(p) {
    addP.value = p; // 先开弹窗，候选异步回填
    addSel.value = [];
    addSearch.value = '';
    addPage.value = 1;
    addRows.value = [];
    addTotal.value = 0;
    fetchCandidates().catch(() => {});
}
watch(addSearch, () => {
    if (addPage.value === 1) fetchCandidates().catch(() => {});
    else addPage.value = 1;
});
watch([addPage, addPageSize], () => fetchCandidates().catch(() => {}));
function toggleAdd(id) {
    const i = addSel.value.indexOf(id);
    if (i >= 0) addSel.value.splice(i, 1);
    else addSel.value.push(id);
}
async function confirmAdd() {
    if (!addSel.value.length) return;
    const ids = [...pMembers.value.map((m) => m.id), ...addSel.value];
    await api.updateProject(addP.value.id, { members: ids });
    addP.value = null;
    await Promise.all([fetchData(), pstore.load(), refreshMembers()]);
}
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else>
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>项目管理</h1>
                <div class="page-meta">共 {{ pool?.total ?? 0 }} 个项目</div>
            </div>
            <div class="page-right">
                <button v-if="perm.isAdmin" class="btn" @click="openCreate">
                    <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>新建项目
                </button>
            </div>
        </div>

        <!-- 筛选行 -->
        <div class="filter-bar rise d2">
            <div class="filter-item">
                <span class="filter-label">状态</span>
                <select class="filter-select" v-model="fState">
                    <option v-for="t in TABS" :key="t.key" :value="t.key">{{ t.label }}</option>
                </select>
            </div>
            <div v-if="perm.isAdmin" class="filter-item">
                <span class="filter-label">负责人</span>
                <select class="filter-select" v-model="fOwner">
                    <option value="">全部</option>
                    <option v-for="m in ownerOpts" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
            </div>
            <input class="field-input f-search" v-model="kw" placeholder="按名称 / 代号搜索" />
        </div>

        <!-- 卡片网格 -->
        <div v-if="rows.length" class="pgrid rise d3">
            <div v-for="p in paged" :key="p.id" class="card pcard">
                <div class="pbar" :style="{ background: p.color }"></div>
                <div class="phead">
                    <span v-if="p.id === currentId" class="cur-tag">当前</span>
                    <span class="pcode">{{ p.code }}</span>
                    <span class="pname">{{ p.name }}</span>
                    <span class="pstate" :class="STATE_META[p.state].cls">{{ STATE_META[p.state].label }}</span>
                </div>

                <div class="prow">
                    <span class="plabel">负责人</span>
                    <span class="ava" :class="mmap[p.owner]?.color" style="width: 22px; height: 22px; font-size:12px">
                        {{ mmap[p.owner]?.short }}
                    </span>
                    <span class="pval">{{ mmap[p.owner]?.name || '—' }}</span>
                </div>

                <div class="prow">
                    <span class="plabel">成员</span>
                    <span class="stack">
                        <span
                            v-for="m in p.members.slice(0, 5)"
                            :key="m"
                            class="ava mem-ava"
                            :class="mmap[m]?.color"
                            style="width: 22px; height: 22px; font-size:12px"
                            :title="'点击移除 ' + (mmap[m]?.name || '')"
                            @click="askRemoveMember(p, m)"
                            >{{ mmap[m]?.short }}</span
                        >
                        <span v-if="p.members.length > 5" class="ava more">+{{ p.members.length - 5 }}</span>
                    </span>
                    <button v-if="canManage(p)" class="add-mem" @click="openManage(p)">管理成员</button>                </div>

                <div class="pprogress">
                    <div class="pp-top">
                        <span>进度</span><b>{{ p.progress }}%</b>
                    </div>
                    <div class="ptrack"><span class="pfill" :style="{ width: p.progress + '%', background: p.color }"></span></div>
                </div>

                <div class="pfoot">
                    <span class="pdate">{{ p.start }} ~ {{ p.end }}</span>
                    <span class="pacts">
                        <a v-if="canManage(p)" @click="openEdit(p)">编辑</a>
                        <a v-if="canDelete" @click="askArchive(p)">{{ p.state === 'archived' ? '恢复' : '归档' }}</a>
                        <a v-if="canDelete" class="danger" @click="askDelete(p)">删除</a>
                    </span>
                </div>
            </div>
            <!-- 分页（全站统一组件，total 取后端筛选后的总条数） -->
            <AppPager :total="total" :page="page" :page-size="pageSize" @update:page="page = $event" @update:pageSize="pageSize = $event" />
        </div>

        <!-- 空态 -->
        <div v-else class="empty rise d3">
            <div class="empty-t">没有符合条件的项目</div>
            <div class="empty-s">换个状态或关键词试试，也可以直接新建一个项目</div>
            <button
                v-if="kw || fState !== 'all' || fOwner"
                class="ghost-btn"
                @click="
                    kw = '';
                    fState = 'all';
                    fOwner = '';
                "
            >
                清空筛选
            </button>
        </div>

        <ProjectFormModal :open="formOpen" :project="formProject" @close="formOpen = false" />
        <AppConfirm v-if="confirmBox" v-bind="confirmBox" @confirm="doConfirm" @close="confirm = null" />

        <!-- 项目成员管理弹窗（成员明细按项目 id 取，管理员可见邮箱） -->
        <AppModal v-if="manageP" :title="'管理成员 · ' + manageP.name" sub="移除或添加该项目的成员；新增的成员将同时进入系统成员池" @close="manageP = null">
            <div class="mm-list">
                <div v-for="m in pMembers" :key="m.id" class="mm-row">
                    <span class="ava" :class="m.color" style="width: 30px; height: 30px; font-size: 12px">{{ m.short }}</span>
                    <div class="mm-info">
                        <b>{{ m.name || '—' }}</b>
                        <span>{{ m.role }}{{ m.email ? ' · ' + m.email : '' }}</span>
                    </div>
                    <button class="mm-del" @click="askRemoveMember(manageP, m.id)">移除</button>
                </div>
                <div v-if="!pMembers.length" class="mm-empty">该项目暂无成员，点击下方「添加成员」引入</div>
            </div>
            <template #footer>
                <button class="ghost-btn" @click="manageP = null">完成</button>
                <button class="btn" @click="openAdd(manageP)">添加成员</button>
            </template>
        </AppModal>

        <!-- 添加项目成员（服务端搜索 + 分页，已排除项目内成员） -->
        <Teleport to="body">
            <div v-if="addP" class="picker-overlay">
                <div class="picker-dialog">
                    <div class="picker-head">
                        <span>添加成员 · {{ addP.name }}</span>
                        <button class="picker-close" title="关闭" @click="addP = null">
                            <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                        </button>
                    </div>
                    <input class="field-input picker-search" v-model="addSearch" placeholder="搜索姓名 / 邮箱 / 角色…" />
                    <div class="picker-list">
                        <div v-for="m in addRows" :key="m.id" class="picker-item" :class="{ on: addSel.includes(m.id) }" @click="toggleAdd(m.id)">
                            <span class="ava" :class="m.color" style="width: 22px; height: 22px; font-size:12px">{{ m.short }}</span>
                            <b>{{ m.name }}</b> {{ m.role }}{{ m.email ? ' · ' + m.email : '' }}
                        </div>
                        <div v-if="!addRows.length" class="picker-empty">没有可添加的成员</div>
                    </div>
                    <AppPager :total="addTotal" :page="addPage" :page-size="addPageSize" @update:page="addPage = $event" @update:pageSize="addPageSize = $event" />
                    <div class="picker-act">
                        <span class="act-n">已选 {{ addSel.length }} 项</span>
                        <button class="ghost-btn" @click="addP = null">取消</button>
                        <button class="btn" :disabled="!addSel.length" @click="confirmAdd">确定</button>
                    </div>
                </div>
            </div>
        </Teleport>
    </template>
</template>

<style scoped>
.f-search {
    width: 220px;
    margin-left: auto;
}

.pgrid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 16px;
}
.pgrid .pager {
    grid-column: 1/-1;
} /* 分页条横跨整行 */
.pcard {
    position: relative;
    padding-top: 24px;
    transition: box-shadow 0.22s var(--ease), transform 0.22s var(--ease), border-color 0.22s var(--ease);
}
.pcard:hover {
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
    border-color: var(--border-accent);
}
.pbar {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 4px;
    border-radius: 12px 12px 0 0;
}

.phead {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
}
.cur-tag {
    font-size:12px;
    padding: 1px 6px;
    border-radius: 5px;
    background: var(--primary-bg);
    color: var(--primary);
    font-weight:400;
    flex: none;
}
.pcode {
    font-size:12px;
    font-weight:700;
    color: var(--t2);
    background: var(--track);
    padding: 2px 7px;
    border-radius: 5px;
    flex: none;
}
.pname {
    font-size: 15px;
    font-weight:700;
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.pstate {
    margin-left: auto;
    font-size:12px;
    padding: 2px 8px;
    border-radius: 6px;
    font-weight:400;
    flex: none;
}
.st-active {
    background: var(--green-bg);
    color: var(--green);
}
.st-planned {
    background: var(--primary-bg);
    color: var(--primary);
}
.st-archived {
    background: var(--track);
    color: var(--t3);
}

.prow {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
}
.plabel {
    width: 44px;
    flex: none;
    font-size: 12px;
    color: var(--t3);
}
.pval {
    font-size: 13px;
    color: var(--t1);
}
.stack {
    display: flex;
    align-items: center;
}
.stack .ava {
    margin-left: -7px;
    border: 2px solid var(--card);
}
.stack .ava:first-child {
    margin-left: 0;
}
.ava.more {
    background: var(--track);
    color: var(--t2);
    font-size:12px;
}

.pprogress {
    margin-top: 4px;
}
.pp-top {
    display: flex;
    justify-content: space-between;
    font-size: 12px;
    color: var(--t3);
    margin-bottom: 6px;
}
.pp-top b {
    font-weight:700;
    color: var(--t1);
}
.ptrack {
    height: 6px;
    background: var(--track);
    border-radius: 3px;
    overflow: hidden;
}
.pfill {
    display: block;
    height: 100%;
    border-radius: 3px;
}

.pfoot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 14px;
    padding-top: 12px;
    border-top: 1px solid var(--border);
}
.pdate {
    font-size: 12px;
    color: var(--t3);
}
.pacts {
    display: flex;
    gap: 12px;
    font-size:13px;
}
.pacts a {
    cursor: pointer;
    color: var(--primary);
}
.pacts a.danger {
    color: var(--red);
}

/* ===== 添加成员 picker（沿用全局 picker 结构）===== */
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
    overflow: hidden;
    background: var(--card);
    border-radius: 12px;
    box-shadow: var(--shadow-lg);
    display: flex;
    flex-direction: column;
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
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 20px;
    font-size: 13px;
    color: var(--t2);
    cursor: pointer;
}
.picker-item b {
    color: var(--t1);
    font-weight:700;
}
.picker-item:hover {
    background: var(--track);
}
.picker-empty {
    padding: 18px;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}
.picker-dialog .pager {
    padding: 12px 20px 12px;
}
.picker-item.on {
    background: var(--primary-bg);
}
.picker-item.on b {
    color: var(--primary);
}
.picker-act {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 20px 14px;
    border-top: 1px solid var(--border);
}
.picker-act .ghost-btn,
.picker-act .btn {
    height: 30px;
}
.picker-act .btn:disabled {
    opacity: 0.5;
    cursor: default;
}
.act-n {
    margin-right: auto;
    font-size:13px;
    color: var(--t2);
}

.mem-ava {
    cursor: pointer;
}
.add-mem {
    margin-left: auto;
    flex: none;
    height: 26px;
    padding: 0 12px;
    font-size: 12px;
    font-family: inherit;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--card);
    color: var(--primary);
    cursor: pointer;
}

/* ===== 项目成员管理弹窗 ===== */
.mm-list {
    display: flex;
    flex-direction: column;
}
.mm-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 2px;
    border-bottom: 1px solid var(--border);
}
.mm-row:last-child {
    border-bottom: none;
}
.mm-info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
}
.mm-info b {
    font-size:14px;
    font-weight:700;
}
.mm-info span {
    font-size: 12px;
    color: var(--t3);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.mm-del {
    flex: none;
    height: 26px;
    padding: 0 12px;
    font-size: 12px;
    font-family: inherit;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--card);
    color: var(--red);
    cursor: pointer;
}
.mm-empty {
    padding: 32px 0;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}

.empty {
    text-align: center;
    padding: 56px 0;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
}
.empty-t {
    font-size: 15px;
    font-weight:700;
}
.empty-s {
    font-size: 13px;
    color: var(--t3);
    margin-top: 6px;
}
.empty .ghost-btn {
    margin-top: 14px;
}

@media (max-width: 880px) {
    .pgrid {
        grid-template-columns: 1fr;
    }
}
</style>
