<script setup>
import { ref, onMounted, computed, watch } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api';
import { useAuthStore } from '../stores/auth';
import { useOptionsStore } from '../stores/options';
import { useUiStore } from '../stores/ui';
import IterCountdown from '../components/IterCountdown.vue';
import LoadingMask from '../components/LoadingMask.vue';
import OptTag from '../components/OptTag.vue';

const router = useRouter()
const CURRENT_USER = useAuthStore().user?.id ?? ''
const auth = useAuthStore();
const ui = useUiStore();
const opts = useOptionsStore(); // 项目设置里的选项配置（含缺陷工作流状态）
const loading = ref(true);
const work = ref(null);
const allTasks = ref([]); // 全量任务（看板列展开，用于「我关注的」）
const allDefects = ref([]); // 全量缺陷（用于「我关注的」）
const showDone = ref(false);

async function fetchData() {
    const [w, b, ds] = await Promise.all([api.getMyWork(), api.getBoard(), api.getDefects()]);
    work.value = w;
    allTasks.value = b.columns.flatMap((c) => c.tasks);
    allDefects.value = ds;
}
onMounted(async () => {
    await fetchData();
    loading.value = false;
});
// 切换项目后重拉（我的任务/缺陷/关注都跟随当前项目）
watch(() => ui.settingsTick, fetchData);
async function refresh() {
    await fetchData();
}
async function startTask(t) {
    // 「开始」推进到任务工作流的下一档（只有一档时原地不动）
    await api.updateTask(t.id, { status: taskSecond.value });
    await refresh();
}
async function finishTask(t) {
    await api.updateTask(t.id, { status: taskTerminal.value });
    await refresh();
}
async function fixDefect(d) {
    // 「开始处理」推进到缺陷工作流的下一档（只有一档时原地不动）
    await api.updateDefect(d.id, { status: defSecond.value });
    await refresh();
}

// 缺陷状态档位：来自项目设置 → 工作流 → 缺陷工作流（顺序即流转顺序）
const defKeys = computed(() => opts.defectStatuses.map((s) => s.key));
const defFirst = computed(() => defKeys.value[0]);
const defSecond = computed(() => defKeys.value[1] || defKeys.value[0]);
const defTerminal = computed(() => defKeys.value[defKeys.value.length - 1]);

// 任务/发版状态档位：来自项目设置 → 工作流（顺序即流转顺序，最后一档为终态）
const taskKeys = computed(() => opts.keysOf('task'));
const taskFirst = computed(() => taskKeys.value[0]);
const taskSecond = computed(() => taskKeys.value[1] || taskKeys.value[0]);
const taskTerminal = computed(() => opts.terminalOf('task'));
// 进行中 = 除首档与终态外的中间档
const taskMiddle = computed(() => taskKeys.value.slice(1, -1));
const taskName = (k) => opts.nameOf('task', k);
const relTerminal = computed(() => opts.terminalOf('release'));
const relName = (k) => opts.nameOf('release', k);
// 进行中的发版 = 非终态档（「已回滚」不参与配置，也不算进行中）
const relActiveNames = computed(() => opts.keysOf('release').filter((k) => k !== relTerminal.value).map(relName).join(' · '));

// ===== 智能分组：按截止日期四档（今天取本机日期，本周截止到周日，非日期归入最后档）=====
const fmtDate = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const NOW = new Date();
const TODAY = fmtDate(NOW);
const WEEK_END = fmtDate(new Date(NOW.getFullYear(), NOW.getMonth(), NOW.getDate() + (6 - ((NOW.getDay() + 6) % 7))));
const isDate = (d) => /^\d{4}-\d{2}-\d{2}/.test(d || '');
function bucketOf(due, done) {
    if (!isDate(due)) return 'later';
    const dp = due.slice(0, 10);
    if (dp < TODAY) return done ? 'later' : 'overdue';
    if (dp === TODAY) return 'today';
    if (dp <= WEEK_END) return 'week';
    return 'later';
}
const GROUPS = [
    { key: 'overdue', name: '已逾期' },
    { key: 'today', name: '今天' },
    { key: 'week', name: '本周' },
    { key: 'later', name: '更晚 & 无截止' }
];
// 按档分组并保持档位顺序（逾期最前），空组不渲染
const groupBy = (rows, dueKey, isDone) =>
    GROUPS.map((g) => ({
        ...g,
        rows: rows.filter((r) => bucketOf(r[dueKey], isDone(r)) === g.key)
    })).filter((g) => g.rows.length);
const taskGroups = computed(() => groupBy(work.value?.tasks || [], 'due', () => false));
const defectGroups = computed(() => groupBy(work.value?.defects || [], 'deadline', (d) => d.status === defTerminal.value));

// ===== 今日聚焦 =====
const overdueCount = computed(() => (work.value?.tasks || []).filter((t) => bucketOf(t.due, false) === 'overdue').length);
const todayCount = computed(() => (work.value?.tasks || []).filter((t) => bucketOf(t.due, false) === 'today').length);
// 我关注的：watchers 含当前用户的任务 + 缺陷，默认取前 3 条，可展开全部
const watched = computed(() => [
    ...allTasks.value.filter((t) => (t.watchers || []).includes(CURRENT_USER)).map((t) => ({ id: t.id, title: t.title, path: '/board' })),
    ...allDefects.value.filter((d) => (d.watchers || []).includes(CURRENT_USER)).map((d) => ({ id: d.id, title: d.title, path: '/defects' }))
]);
const showAllWatched = ref(false);
const watchedTop = computed(() => (showAllWatched.value ? watched.value : watched.value.slice(0, 3)));
function goFocus(x) {
    router.push({ path: x.path, query: { focus: x.id } });
}
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else-if="work">
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>我的工作</h1>
                <div class="page-meta">聚焦与你相关的任务、缺陷与发版 · {{ auth.user?.name || '我' }}</div>
            </div>
            <div class="page-right">
                <IterCountdown />
            </div>
        </div>

        <!-- 今日聚焦 -->
        <div class="focus-row rise d2">
            <div class="fcard">
                <div class="v red">{{ overdueCount }}</div>
                <div class="k">逾期任务</div>
                <div class="sub">早于 {{ TODAY }} 未完成</div>
            </div>
            <div class="fcard">
                <div class="v amber">{{ todayCount }}</div>
                <div class="k">今日到期</div>
                <div class="sub">截止 {{ TODAY }}</div>
            </div>
            <div class="fcard">
                <div class="v">{{ work.releasesOwned.length }}</div>
                <div class="k">我负责的发版</div>
                <div class="sub">{{ relActiveNames }}</div>
            </div>
            <div class="fcard fwatch">
                <div class="k">
                    我关注的 <span class="n">{{ watched.length }}</span>
                    <button v-if="watched.length > 3" class="w-more" @click="showAllWatched = !showAllWatched">
                        {{ showAllWatched ? '收起' : '全部' }}
                    </button>
                </div>
                <div class="w-list" :class="{ expanded: showAllWatched }">
                    <div v-for="w in watchedTop" :key="w.id" class="w-row" @click="goFocus(w)">
                        <span class="w-id">{{ w.id }}</span>
                        <span class="w-title">{{ w.title }}</span>
                    </div>
                </div>
                <div v-if="!watchedTop.length" class="w-empty">暂无关注的条目</div>
            </div>
        </div>

        <!-- 统计条 -->
        <div class="stat-strip rise d2">
            <div class="stat">
                <div class="v">{{ work.tasks.filter((t) => taskMiddle.includes(t.status)).length }}</div>
                <div class="k">进行中的任务</div>
                <div class="sub">共 {{ work.tasks.length }} 项未完成</div>
            </div>
            <div class="stat danger">
                <div class="v">{{ work.defects.filter((d) => d.status === defFirst || d.status === defSecond).length }}</div>
                <div class="k">待处理缺陷</div>
                <div class="sub">共 {{ work.defects.length }} 个相关</div>
            </div>
            <div class="stat warn">
                <div class="v">{{ work.releasesOwned.length }}</div>
                <div class="k">进行中的发版</div>
                <div class="sub">{{ relActiveNames }}</div>
            </div>
            <div class="stat ok">
                <div class="v">{{ work.releasesReleased.length }}</div>
                <div class="k">我负责{{ relName(relTerminal) }}</div>
                <div class="sub">累计发布 {{ work.releasesReleased.length }} 个版本</div>
            </div>
        </div>

        <div class="cols-2">
            <!-- 我的任务（按截止日期四档分组） -->
            <div class="card rise d3">
                <div class="card-head">
                    <span class="card-title">我的任务</span>
                    <button class="link-btn" @click="router.push('/board')">进入看板 →</button>
                </div>
                <template v-for="g in taskGroups" :key="g.key">
                    <div class="grp-head">
                        <span class="grp-name" :class="{ over: g.key === 'overdue' }">{{ g.name }}</span>
                        <span class="grp-n" :class="{ over: g.key === 'overdue' }">{{ g.rows.length }}</span>
                    </div>
                    <div v-for="t in g.rows" :key="t.id" class="row clickable" @click="router.push({ path: '/board', query: { focus: t.id } })">
                        <OptTag kind="taskTypes" base="tag" :value="t.type" />
                        <span class="row-title" :class="{ over: g.key === 'overdue' }">{{ t.title }}</span>
                        <span v-if="g.key === 'overdue'" class="od-badge">逾期</span>
                        <OptTag :value="t.prio" />
                        <span class="status" :style="opts.styleOf('task', t.status)">{{ taskName(t.status) }}</span>
                        <span class="row-due">{{ t.due }}</span>
                        <button v-if="t.status === taskFirst && taskMiddle.length" class="mini-btn" @click.stop="startTask(t)">开始</button>
                        <button v-else-if="t.status !== taskTerminal" class="mini-btn" @click.stop="finishTask(t)">完成</button>
                    </div>
                </template>
                <div v-if="!taskGroups.length" class="empty">暂无进行中的任务</div>

                <div class="sub-head done-head" @click="showDone = !showDone">
                    {{ taskName(taskTerminal) }} · {{ work.doneTasks.length }}
                    <span class="fold">{{ showDone ? '收起 ▲' : '展开 ▼' }}</span>
                </div>
                <template v-if="showDone">
                    <div v-for="t in work.doneTasks" :key="t.id" class="row done-row clickable" @click="router.push({ path: '/board', query: { focus: t.id } })">
                        <OptTag kind="taskTypes" base="tag" :value="t.type" />
                        <span class="row-title">{{ t.title }}</span>
                        <span class="done-fin">✓ {{ taskName(taskTerminal) }}</span>
                        <span class="row-due">{{ t.due }}</span>
                    </div>
                    <div v-if="!work.doneTasks.length" class="empty">暂无{{ taskName(taskTerminal) }}任务</div>
                </template>
            </div>

            <!-- 我的发版 -->
            <div class="card rise d4">
                <div class="card-head">
                    <span class="card-title">我的发版</span>
                    <button class="link-btn" @click="router.push('/releases')">进入发版计划 →</button>
                </div>
                <div v-for="r in work.releasesOwned" :key="r.id" class="row clickable" @click="router.push({ path: '/releases', query: { focus: r.id } })">
                    <span class="mr-id">{{ r.version }}</span>
                    <span class="row-title">{{ r.name }}</span>
                    <span class="status" :class="r.status" :style="opts.styleOf('release', r.status)">{{ relName(r.status) }}</span>
                    <button class="mini-btn" @click.stop="router.push({ path: '/releases', query: { focus: r.id } })">查看</button>
                </div>
                <div v-if="!work.releasesOwned.length" class="empty">暂无进行中的发版计划</div>

                <div class="sub-head">{{ relName(relTerminal) }}</div>
                <div v-for="r in work.releasesReleased" :key="r.id" class="row clickable" @click="router.push({ path: '/releases', query: { focus: r.id } })">
                    <span class="mr-id">{{ r.version }}</span>
                    <span class="row-title">{{ r.name }}</span>
                    <span class="status" :class="r.status" :style="opts.styleOf('release', r.status)">{{ relName(r.status) }}</span>
                </div>
                <div v-if="!work.releasesReleased.length" class="empty">暂无{{ relName(relTerminal) }}版本</div>
            </div>
        </div>

        <div class="cols-2">
            <!-- 我的缺陷（按 deadline 同规则分组） -->
            <div class="card rise d4">
                <div class="card-head">
                    <span class="card-title">我的缺陷</span>
                    <button class="link-btn" @click="router.push('/defects')">进入缺陷 →</button>
                </div>
                <template v-for="g in defectGroups" :key="g.key">
                    <div class="grp-head">
                        <span class="grp-name" :class="{ over: g.key === 'overdue' }">{{ g.name }}</span>
                        <span class="grp-n" :class="{ over: g.key === 'overdue' }">{{ g.rows.length }}</span>
                    </div>
                    <div v-for="d in g.rows" :key="d.id" class="row clickable" @click="router.push({ path: '/defects', query: { focus: d.id } })">
                        <span class="mr-id">{{ d.id }}</span>
                        <span class="row-title" :class="{ over: g.key === 'overdue' }">{{ d.title }}</span>
                        <span v-if="g.key === 'overdue'" class="od-badge">逾期</span>
                        <OptTag :value="d.prio" />
                        <span class="status" :class="d.status" :style="opts.defStyle(d.status)">{{ opts.defName(d.status) }}</span>
                        <span class="row-due">{{ d.deadline }}</span>
                        <button v-if="d.status === defFirst && defSecond !== defTerminal" class="mini-btn" @click.stop="fixDefect(d)">开始处理</button>
                    </div>
                </template>
                <div v-if="!defectGroups.length" class="empty">暂无分配给我的缺陷</div>
            </div>

            <!-- 我负责的需求 -->
            <div class="card rise d5">
                <div class="card-head">
                    <span class="card-title">我负责的需求</span>
                    <button class="link-btn" @click="router.push('/requirements')">进入需求池 →</button>
                </div>
                <div v-for="r in work.requirements" :key="r.id" class="row clickable" @click="router.push({ path: '/requirements', query: { focus: r.id } })">
                    <span class="mr-id">{{ r.id }}</span>
                    <span class="row-title">{{ r.title }}</span>
                    <OptTag :value="r.prio" />
                    <span class="iter-chip">{{ r.iter }}</span>
                </div>
                <div v-if="!work.requirements.length" class="empty">暂无负责的需求</div>
            </div>
        </div>
    </template>
</template>

<style scoped>
.cols-2 {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 16px;
    margin-bottom: 16px;
}
.link-btn {
    margin-left: auto;
    border: none;
    background: none;
    color: var(--primary);
    font-size:13px;
    cursor: pointer;
    font-family: inherit;
}
.row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 0;
    border-bottom: 1px solid var(--border);
}
.row:last-child {
    border-bottom: none;
}
.row + .row {
    padding-top: 9px;
}
.row.clickable {
    cursor: pointer;
}
.row-title {
    flex: 1;
    font-size:14px;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.mr-id {
    flex: none;
    font-size: 12px;
    color: var(--t2);
    font-variant-numeric: tabular-nums;
    min-width: 44px;
}
.row-due {
    flex: none;
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.row-due.hot {
    color: var(--red);
    font-weight:400;
}
.sub-head {
    font-size: 12px;
    color: var(--t3);
    margin: 14px 0 4px;
    font-weight:400;
}
.done-head {
    cursor: pointer;
    display: flex;
    align-items: center;
    gap: 6px;
}
.done-head .fold {
    font-size:12px;
    color: var(--primary);
    font-weight: 400;
}
.done-row {
    opacity: 0.55;
}
.done-fin {
    flex: none;
    font-size:12px;
    color: var(--green);
    font-weight:400;
}
.empty {
    font-size:13px;
    color: var(--t3);
    padding: 12px 0;
}
.iter-chip {
    flex: none;
    font-size:12px;
    background: var(--track);
    color: var(--t2);
    padding: 1px 8px;
    border-radius: 5px;
}
.mini-btn {
    flex: none;
    font-size:12px;
    padding: 3px 10px;
    border: 1px solid var(--primary-bg);
    border-radius: 6px;
    background: var(--primary-bg);
    color: var(--primary);
    cursor: pointer;
    font-family: inherit;
}

/* 今日聚焦 */
.focus-row {
    display: grid;
    grid-template-columns: 1fr 1fr 1fr 1.8fr;
    gap: 16px;
    margin-bottom: 16px;
}
.fcard {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 14px 18px;
}
.fcard .v {
    font-size: 24px;
    font-weight:700;
    font-variant-numeric: tabular-nums;
}
.fcard .v.red {
    color: var(--red);
}
.fcard .v.amber {
    color: var(--amber);
}
.fcard .k {
    font-size: 12px;
    color: var(--t3);
    margin-top: 4px;
}
.fcard .sub {
    font-size:12px;
    color: var(--t3);
    margin-top: 2px;
}
.fwatch .k {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 4px;
}
.fwatch .n {
    background: var(--track);
    color: var(--t2);
    border-radius: 5px;
    padding: 0 7px;
    font-size:12px;
    line-height: 17px;
    font-variant-numeric: tabular-nums;
}
.w-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 3px 0;
    cursor: pointer;
}
.w-list.expanded{max-height:180px;overflow-y:auto}
.w-more{margin-left:auto;border:none;background:none;padding:0;font-size:12px;color:var(--primary);
    cursor:pointer;font-family:inherit}
.w-id {
    flex: none;
    font-size:12px;
    color: var(--t2);
    font-variant-numeric: tabular-nums;
}
.w-title {
    flex: 1;
    min-width: 0;
    font-size:13px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.w-empty {
    font-size: 12px;
    color: var(--t3);
}

/* 截止日期分档组头 + 逾期标记 */
.grp-head {
    display: flex;
    align-items: center;
    gap: 7px;
    margin: 12px 0 2px;
}
.card-head + .grp-head {
    margin-top: 0;
}
.grp-name {
    font-size:13px;
    font-weight:700;
    color: var(--t2);
}
.grp-name.over {
    color: var(--red);
}
.grp-n {
    font-size:12px;
    font-weight:400;
    color: var(--t3);
    background: var(--track);
    border-radius: 5px;
    padding: 0 7px;
    line-height: 17px;
    font-variant-numeric: tabular-nums;
}
.grp-n.over {
    background: var(--red-bg);
    color: var(--red);
}
.row-title.over {
    color: var(--red);
    font-weight:400;
}
.od-badge {
    flex: none;
    font-size:12px;
    padding: 1px 6px;
    border-radius: 5px;
    background: var(--red-bg);
    color: var(--red);
    font-weight:400;
}

@media (max-width: 1150px) {
    .cols-2 {
        grid-template-columns: 1fr;
    }
    .focus-row {
        grid-template-columns: 1fr 1fr;
    }
}
</style>
