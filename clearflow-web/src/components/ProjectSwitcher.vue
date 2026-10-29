<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api';
import { useUiStore } from '../stores/ui';
import { usePermStore } from '../stores/perm';
import { useProjectsStore } from '../stores/projects';
import ProjectFormModal from './ProjectFormModal.vue';

const router = useRouter();
const ui = useUiStore();
const perm = usePermStore(); // 新建项目入口仅系统管理员可见（POST /projects 仅管理员）
const store = useProjectsStore();

// ===== 数据来自共享 projects store（MainLayout 启动加载，写方显式刷新）=====
const projects = computed(() => store.list);
const current = computed(() => store.current);

// 状态徽标
const STATE_LABEL = { active: '进行中', planned: '规划中', archived: '已归档' };
const stateCls = (s) => ({ active: 's-active', planned: 's-planned', archived: 's-archived' })[s] || 's-archived';

// ===== 下拉面板：开关 / 定位 / 外部点击与 Esc 关闭 =====
const open = ref(false);
const trigger = ref(null);
const pos = ref({ top: 0, left: 0 });

function toggle() {
    if (open.value) {
        close();
        return;
    }
    const r = trigger.value.getBoundingClientRect();
    pos.value = { top: r.bottom + 6, left: r.left };
    open.value = true;
}
function close() {
    open.value = false;
}
function onDocClick() {
    close();
}
function onKey(e) {
    if (e.key === 'Escape') close();
}
onMounted(() => {
    document.addEventListener('click', onDocClick);
    window.addEventListener('keydown', onKey);
});
onUnmounted(() => {
    document.removeEventListener('click', onDocClick);
    window.removeEventListener('keydown', onKey);
});

// ===== 切换项目 =====
async function switchProject(p) {
    if (p.id !== current.value?.id) {
        await api.setCurrentProject(p.id);
        await store.load(); // 刷新 current
        ui.notifySettings(); // 切项目后，选项配置（优先级/严重度/任务类型/标签）跟着换
    }
    close();
}
function go(path) {
    close();
    router.push(path);
}

// ===== 新建项目（局部 state 控制）=====
const formOpen = ref(false);
function openCreate() {
    close();
    formOpen.value = true;
}
</script>

<template>
    <!-- 触发器：色块 + 项目名 + 下拉箭头 -->
    <div ref="trigger" class="proj" @click.stop="toggle">
        <span class="proj-dot" :style="{ background: current?.color || 'var(--primary)' }"></span>
        {{ current?.name || '未选择项目' }}
        <svg class="caret" viewBox="0 0 12 12"><path d="m2.5 4.5 3.5 3.5 3.5-3.5" /></svg>
    </div>

    <Teleport to="body">
        <div v-if="open" class="ps-panel" :style="{ top: pos.top + 'px', left: pos.left + 'px' }" @click.stop>
            <!-- 项目区：列表由后端按可见范围过滤（管理员=全部；其余=自己负责或参与的项目） -->
            <div class="ps-sec">
                <span class="ps-sec-t">项目</span>
                <span class="ps-sec-note">{{ perm.isAdmin ? '全部项目' : '仅显示与你相关的项目' }}</span>
            </div>
            <div v-for="p in projects" :key="p.id" class="ps-row" :class="{ on: current?.id === p.id }" @click="switchProject(p)">
                <span class="ps-pdot" :style="{ background: p.color }"></span>
                <span class="ps-code">{{ p.code }}</span>
                <span class="ps-name">{{ p.name }}</span>
                <span class="ps-state" :class="stateCls(p.state)">{{ STATE_LABEL[p.state] }}</span>
                <svg v-if="current?.id === p.id" class="ps-check" viewBox="0 0 24 24">
                    <path d="m5 12.5 4.5 4.5L19 7.5" />
                </svg>
            </div>
            <div v-if="!projects.length" class="ps-empty">暂无项目</div>

            <!-- 底部操作行：项目管理 / 新建项目 均仅系统管理员可用 -->
            <div v-if="perm.isAdmin" class="ps-foot">
                <a class="ps-foot-i" @click="go('/projects')">项目管理</a>
                <a class="ps-foot-i" @click="openCreate">+ 新建项目</a>
            </div>
        </div>
    </Teleport>

    <ProjectFormModal :open="formOpen" :project="null" @close="formOpen = false" />
</template>

<style scoped>
/* 触发器：沿用原顶栏 .proj 视觉 */
.proj {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 14px;
    font-weight:700;
    cursor: pointer;
}
.proj .caret {
    width: 12px;
    height: 12px;
    stroke: var(--t3);
    stroke-width: 2;
    fill: none;
    stroke-linecap: round;
}
.proj-dot {
    width: 10px;
    height: 10px;
    border-radius: 3px;
    flex: none;
}

/* 下拉面板：Teleport 到 body，盖过 topbar 的 50 */
.ps-panel {
    position: fixed;
    width: 340px;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-lg);
    padding: 8px;
    z-index: 900;
    animation: ps-pop 0.18s var(--ease) both;
}
@keyframes ps-pop {
    from {
        opacity: 0;
        transform: translateY(-6px);
    }
    to {
        opacity: 1;
        transform: none;
    }
}

.ps-sec {
    display: flex;
    align-items: center;
    padding: 6px 10px 4px;
}
.ps-sec-t {
    font-size:12px;
    color: var(--t3);
}
.ps-sec-note {
    margin-left: auto;
    font-size:12px;
    color: var(--t3);
}

.ps-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 10px;
    border-radius: 8px;
    font-size: 13px;
    color: var(--t1);
    cursor: pointer;
}
.ps-row.on {
    background: var(--primary-bg);
    font-weight:700;
}
.ps-pdot {
    width: 9px;
    height: 9px;
    border-radius: 3px;
    flex: none;
}
.ps-code {
    font-size:12px;
    font-weight:700;
    color: var(--t2);
    background: var(--track);
    padding: 1px 6px;
    border-radius: 5px;
    flex: none;
}
.ps-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.ps-state {
    font-size:12px;
    padding: 1px 6px;
    border-radius: 5px;
    flex: none;
    font-weight:400;
}
.ps-state.s-active {
    background: var(--green-bg);
    color: var(--green);
}
.ps-state.s-planned {
    background: var(--primary-bg);
    color: var(--primary);
}
.ps-state.s-archived {
    background: var(--track);
    color: var(--t3);
}
.ps-row.on .s-planned {
    background: var(--card);
}
.ps-check {
    width: 15px;
    height: 15px;
    flex: none;
    stroke: var(--primary);
    stroke-width: 2.2;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}

.ps-empty {
    padding: 8px 10px;
    font-size: 12px;
    color: var(--t3);
}

.ps-foot {
    display: flex;
    gap: 16px;
    padding: 8px 10px 4px;
    margin-top: 6px;
    border-top: 1px solid var(--border);
}
.ps-foot-i {
    font-size:13px;
    color: var(--primary);
    cursor: pointer;
}
</style>
