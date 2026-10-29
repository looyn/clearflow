<script setup>
import { ref, computed, onMounted, watch } from 'vue';
import { api } from '../api';
import { usePermStore } from '../stores/perm';
import { useProjectsStore } from '../stores/projects';
import LoadingMask from '../components/LoadingMask.vue';

const perm = usePermStore();
const projects = useProjectsStore(); // 矩阵按「当前项目」取数
const loading = ref(true);

// ===== 权限矩阵（当前项目成员 × 模块；有效等级由后端统一下发，规则只在后端一份）=====
const matrix = ref({ projectId: null, owner: null, members: [], grants: {}, effective: {} });
const MODULES = ref([]);
const LEVELS = ref([]);
const levelCls = { 无: 'lv-none', 查看: 'lv-view', 编辑: 'lv-edit', 管理: 'lv-admin' };
// 单元格取值 = 后端算好的有效等级（项目授权覆盖 | 角色默认）
const cellVal = (memberId, mod) => matrix.value.effective?.[memberId]?.[mod] ?? '';
// 是否显式授权（区别于角色默认），用于单元格提示
const explicit = (memberId, mod) => !!matrix.value.grants?.[memberId]?.[mod];
const isOwner = (memberId) => memberId === matrix.value.owner;
// 矩阵可写：系统管理员，或该项目的负责人（与后端 @RequireOwner 对齐）
const canEditMatrix = computed(
    () => perm.isAdmin || (matrix.value.projectId != null && perm.owns(matrix.value.projectId))
);
// 负责人一行只允许系统管理员调整（本人不可改自己的权限）
const cellLocked = (memberId) => !canEditMatrix.value || (isOwner(memberId) && !perm.isAdmin);
// 页面可见性（与菜单 vis:'owner' 同源）；perm 画像未就绪时不发请求
const allowed = computed(() => perm.isAdmin || perm.owns(projects.currentId));

async function fetchData() {
    await projects.load();
    if (!allowed.value) {
        matrix.value = { projectId: null, owner: null, members: [], grants: {}, effective: {} };
        MODULES.value = [];
        LEVELS.value = [];
        return;
    }
    const pid = projects.currentId;
    if (!pid) {
        matrix.value = { projectId: null, owner: null, members: [], grants: {}, effective: {} };
        MODULES.value = [];
        LEVELS.value = [];
        return;
    }
    const pm = await api.getPermMatrix(pid);
    matrix.value = pm;
    MODULES.value = pm.modules || [];
    LEVELS.value = pm.levels || [];
}
onMounted(async () => {
    await fetchData();
    loading.value = false;
});
// 切换项目、或权限画像加载完成后（补）拉取
watch([allowed, () => projects.currentId], fetchData);

// 单元格提示：区分「显式授权」与「默认」（负责人默认全权，其余按角色默认）
function cellTitle(memberId, mod) {
    if (isOwner(memberId)) {
        return explicit(memberId, mod)
            ? '负责人已被显式授权（点击调整）'
            : '负责人默认对本项目全权（仅系统管理员可调整）';
    }
    return explicit(memberId, mod) ? '项目内显式授权（点击调整）' : '角色默认（点击调整）';
}

// ===== 单元格变更（按成员逐格写本项目授权）=====
const savedMod = ref('');
let savedTimer = null;
async function changeCell(memberId, mod, level) {
    const pid = matrix.value.projectId;
    if (pid == null || cellLocked(memberId)) return;
    if (cellVal(memberId, mod) === level) return;
    await api.updatePerm(pid, memberId, mod, level);
    matrix.value.grants[memberId] = { ...(matrix.value.grants[memberId] || {}), [mod]: level };
    matrix.value.effective[memberId] = { ...(matrix.value.effective[memberId] || {}), [mod]: level };
    savedMod.value = mod;
    clearTimeout(savedTimer);
    savedTimer = setTimeout(() => {
        savedMod.value = '';
    }, 1500);
}
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else>
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>权限矩阵</h1>
                <div class="page-meta">{{ projects.current?.name || '当前项目' }} · 授权只在本项目生效</div>
            </div>
            <span v-if="savedMod" class="saved">已保存 · {{ savedMod }}</span>
        </div>

        <!-- 成员 × 模块矩阵 -->
        <div class="card table-card rise d2">
            <div class="card-head" style="padding: 20px 20px 0; margin-bottom: 14px">
                <span class="card-title">成员 × 模块</span>
                <span class="card-extra">仅展示本项目成员；项目负责人默认对本项目全权，其各行只能由系统管理员调整；系统管理员为内置账号 admin；切换顶部项目可查看其他项目的矩阵</span>
            </div>
            <div class="table-head">
                <div class="col-mod">成员</div>
                <div v-for="mod in MODULES" :key="mod" class="col-role">{{ mod }}</div>
            </div>
            <div v-for="m in matrix.members" :key="m.id" class="table-row">
                <div class="col-mod mod">
                    <span class="mini-ava ava" :class="m.color">{{ m.short }}</span>
                    <span class="m-name">{{ m.name }}</span>
                    <span class="m-role">{{ m.role }}</span>
                    <span v-if="isOwner(m.id)" class="me-tag">负责人</span>
                </div>
                <div v-for="mod in MODULES" :key="mod" class="col-role">
                    <select
                        class="perm-select lv"
                        :class="levelCls[cellVal(m.id, mod)]"
                        :value="cellVal(m.id, mod)"
                        :disabled="cellLocked(m.id)"
                        :title="cellTitle(m.id, mod)"
                        @change="changeCell(m.id, mod, $event.target.value)"
                    >
                        <option v-for="lv in LEVELS" :key="lv" :value="lv">{{ lv }}</option>
                    </select>
                </div>
            </div>
            <div v-if="!matrix.members.length" class="empty">当前项目暂无成员</div>
        </div>
    </template>
</template>

<style scoped>
.m-name {
    font-size:14px;
    font-weight:400;
}
.m-role {
    font-size: 12px;
    color: var(--t2);
    flex: none;
}
.me-tag {
    font-size:12px;
    color: var(--t3);
    background: var(--track);
    padding: 1px 6px;
    border-radius: 5px;
}
.empty {
    padding: 24px;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}
.col-mod {
    flex: 1;
    min-width: 200px;
    display: flex;
    align-items: center;
    gap: 9px;
}
.col-role {
    width: 140px;
    flex: none;
    text-align: center;
}
.mod {
    font-size:14px;
    color: var(--t1);
}
.lv {
    font-size:12px;
    padding: 2px 10px;
    border-radius: 5px;
    font-weight:400;
}
.lv-none {
    background: var(--track);
    color: var(--t3);
}
.lv-view {
    background: var(--track);
    color: var(--t2);
}
.lv-edit {
    background: var(--primary-bg);
    color: var(--primary);
}
.lv-admin {
    background: var(--amber-bg);
    color: var(--amber);
}
.perm-select {
    font-family: inherit;
    border: 1px solid transparent;
    cursor: pointer;
    outline: none;
    max-width: 100%;
}
.saved {
    font-size:12px;
    color: var(--green);
    white-space: nowrap;
}
</style>
