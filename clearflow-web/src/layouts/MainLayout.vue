<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useUiStore } from '../stores/ui';
import { useAuthStore } from '../stores/auth';
import { usePermStore } from '../stores/perm';
import { useOptionsStore } from '../stores/options';
import { useIterationsStore } from '../stores/iterations';
import { useMembersStore } from '../stores/members';
import { useProjectsStore } from '../stores/projects';
import GlobalCreateModals from '../components/GlobalCreateModals.vue';
import GlobalSearch from '../components/GlobalSearch.vue';
import NotificationBell from '../components/NotificationBell.vue';
import ProjectSwitcher from '../components/ProjectSwitcher.vue';
import ProfileModal from '../components/ProfileModal.vue';
import { APP_VERSION } from '../utils/appVersion';

const route = useRoute();
const router = useRouter();
const ui = useUiStore();
const auth = useAuthStore();
const perm = usePermStore();
const opts = useOptionsStore();
const iters = useIterationsStore(); // 顶栏当前迭代 chip 与各处迭代下拉的数据源
const members = useMembersStore(); // 成员下拉/名称映射共享
const projects = useProjectsStore(); // 项目切换器/项目管理页共享

// ===== 移动端侧边栏抽屉 =====
const navOpen = ref(false);
watch(
    () => route.path,
    () => {
        navOpen.value = false;
    }
);

// ===== 退出登录 =====
const menuOpen = ref(false);
function logout() {
    menuOpen.value = false;
    auth.logout();
    perm.clear();
    projects.clear(); // 清掉上一个账号的项目列表，避免切换账号后切换器残留旧数据
    router.push('/login');
}
function closeMenu() {
    menuOpen.value = false;
}

// ===== 个人信息弹窗（查看个人信息 / 修改密码，tab 区分入口）=====
const profileOpen = ref(false);
const profileTab = ref('info');
function openProfile(tab) {
    profileTab.value = tab;
    profileOpen.value = true;
    menuOpen.value = false;
}

// module：权限模块（ PermConstants.MODULES ）；visibleMenus：按权限过滤后的菜单
const navs = [
    {
        group: '工作台',
        items: [
            { to: '/overview', label: '概览', icon: 'home' },
            { to: '/mywork', label: '我的工作', icon: 'user' }
        ]
    },
    {
        group: '研发',
        items: [
            { to: '/requirements', label: '需求池', icon: 'inbox', vis: 'module', module: '需求池' },
            { to: '/iterations', label: '迭代规划', icon: 'calendar', vis: 'module', module: '迭代规划' },
            { to: '/board', label: '任务看板', icon: 'board', vis: 'module', module: '任务看板' },
            { to: '/defects', label: '缺陷追踪', icon: 'bug', vis: 'module', module: '缺陷追踪' },
            { to: '/releases', label: '发版计划', icon: 'flag', vis: 'module', module: '发版计划' },
            { to: '/docs', label: '文档管理', icon: 'doc', vis: 'module', module: '文档管理' }
        ]
    },
    {
        group: '度量',
        items: [
            { to: '/metrics', label: '度量报表', icon: 'trend', vis: 'owner' },
            { to: '/worklog', label: '工时统计', icon: 'clock', vis: 'member' }
        ]
    },
    {
        group: '设置',
        items: [
            { to: '/projects', label: '项目管理', icon: 'board', vis: 'projects' },
            { to: '/settings', label: '项目设置', icon: 'gear', vis: 'owner' },
            { to: '/members', label: '成员', icon: 'users', vis: 'admin' },
            { to: '/perms', label: '权限矩阵', icon: 'gear', vis: 'owner' }
        ]
    }
];

// 项目可见性：系统管理员全量；其余成员只看到自己被分配的项目（后端 GET /projects 已过滤）
// ready：首屏权限/项目加载完成前不渲染内容，避免无项目成员触发一堆 403
const ready = ref(false);
const hasProject = computed(() => perm.isAdmin || projects.list.length > 0);

// 菜单可见性规则（与后端 @RequireAdmin / @RequireOwner / @RequireProject 对齐）：
//   module → 当前项目下该模块的查看权限
//   member → 当前项目成员即可（工时：本人登记本人，负责人/管理员看团队）
//   owner  → 当前项目的负责人（与后端 @RequireOwner 的 CURRENT 模式同源，非「任一项目」）
//   projects → 系统管理员，或任一项目的负责人（页面数据按可见范围过滤，按钮按 owns/isAdmin 门控）
//   admin  → 仅内置系统管理员账号
// 无 vis 的菜单常显（工作台）；有 vis 的菜单在没有任何可见项目时不展示
const VIS_RULES = {
    module: (it) => perm.canSee(it.module),
    member: () => hasProject.value,
    owner: () => perm.owns(projects.currentId),
    projects: () => perm.isAdmin || perm.ownedProjectIds.length > 0,
    admin: () => perm.isAdmin
};
const visibleNavs = computed(() =>
    navs
        .map((g) => ({
            ...g,
            items: g.items.filter((it) => {
                if (!it.vis) return true;
                if (!hasProject.value) return false;
                return VIS_RULES[it.vis](it);
            })
        }))
        .filter((g) => g.items.length)
);

// 受菜单约束的路径：直接输入网址访问时同样拦截，回退概览
const GUARDED_PATHS = navs.flatMap((g) => g.items.filter((it) => it.vis).map((it) => it.to));
const allowedPaths = computed(() => visibleNavs.value.flatMap((g) => g.items.map((it) => it.to)));
watch(
    [() => route.path, allowedPaths, ready],
    () => {
        if (ready.value && GUARDED_PATHS.includes(route.path) && !allowedPaths.value.includes(route.path)) {
            router.replace('/overview');
        }
    }
);

function onKey(e) {
    if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault();
        ui.searchOpen = true;
    }
}
onMounted(async () => {
    // 并行拉取：权限画像（菜单过滤/按钮显隐）、项目选项、迭代、成员、项目列表+当前项目
    // 用 allSettled：任一失败（例如暂无项目）都不阻断首屏
    await Promise.allSettled([perm.load(), opts.load(), iters.load(), members.load(), projects.load()]);
    ready.value = true;
    window.addEventListener('keydown', onKey);
    window.addEventListener('click', closeMenu); // 点击空白处关闭退出菜单
});
// 项目设置里的选项配置改动、或切换项目后重新拉取
watch(() => ui.settingsTick, () => {
    opts.load();
    projects.load(); // 切项目后 current 跟着换
});
// 任意写操作后重拉迭代（新建/变更迭代会改 chip 与下拉候选）
watch(() => ui.refreshTick, () => iters.load());
onUnmounted(() => {
    window.removeEventListener('keydown', onKey);
    window.removeEventListener('click', closeMenu);
});
</script>

<template>
    <div class="shell">
        <div v-if="navOpen" class="nav-mask" @click="navOpen = false"></div>
        <aside class="sidebar" :class="{ open: navOpen }">
            <div class="brand">
                <img class="logo" src="/logo-mark.png" alt="清流 ClearFlow" />
                <div>
                    <div class="brand-name">清流</div>
                    <div class="brand-sub">研发项目管理</div>
                </div>
            </div>
            <nav>
                <template v-for="g in visibleNavs" :key="g.group">
                    <div class="nav-title">{{ g.group }}</div>
                    <router-link v-for="n in g.items" :key="n.to" :to="n.to" class="nav-item" :class="{ active: route.path === n.to }">
                        <svg viewBox="0 0 24 24">
                            <template v-if="n.icon === 'home'">
                                <path d="M3.5 11 12 4l8.5 7" />
                                <path d="M5.5 9.8V20h13V9.8" />
                            </template>
                            <template v-else-if="n.icon === 'user'">
                                <rect x="4" y="4" width="16" height="16" rx="3" />
                                <circle cx="12" cy="10" r="2.4" />
                                <path d="M7.6 19c.8-2.3 2.4-3.5 4.4-3.5s3.6 1.2 4.4 3.5" />
                            </template>
                            <template v-else-if="n.icon === 'inbox'">
                                <path d="M4.5 13 6.6 6.4A2 2 0 0 1 8.5 5h7a2 2 0 0 1 1.9 1.4L19.5 13" />
                                <path d="M4.5 13v5a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-5" />
                                <path d="M4.5 13H9l1.3 2.4h3.4L15 13h4.5" />
                            </template>
                            <template v-else-if="n.icon === 'calendar'">
                                <rect x="4" y="5.5" width="16" height="15" rx="2.5" />
                                <path d="M8 3.2V7M16 3.2V7M4 10.5h16" />
                            </template>
                            <template v-else-if="n.icon === 'board'">
                                <rect x="4" y="4" width="4.6" height="16" rx="1.4" />
                                <rect x="10.4" y="4" width="4.6" height="10" rx="1.4" />
                                <rect x="16.8" y="4" width="3.2" height="13" rx="1.4" />
                            </template>
                            <template v-else-if="n.icon === 'bug'">
                                <circle cx="12" cy="13.5" r="4.8" />
                                <path d="M9.8 9.4a2.8 2.8 0 0 1 4.4 0" />
                                <path d="M12 8.7V6.3M8 10.5 6.2 8.7M16 10.5l1.8-1.8M7.2 13.5H4.6M19.4 13.5h-2.6M8 16.5l-1.8 1.8M16 16.5l1.8 1.8" />
                            </template>
                            <template v-else-if="n.icon === 'code'"><path d="m8.5 8-4.2 4 4.2 4M15.5 8l4.2 4-4.2 4" /></template>
                            <template v-else-if="n.icon === 'flag'">
                                <path d="M6 21V4" />
                                <path d="M6 5h11l-2.2 3.5L17 12H6" />
                            </template>
                            <template v-else-if="n.icon === 'doc'">
                                <path d="M6 4.5A1.5 1.5 0 0 1 7.5 3h6L18 7.5V19.5A1.5 1.5 0 0 1 16.5 21h-9A1.5 1.5 0 0 1 6 19.5z" />
                                <path d="M13.5 3v4.5H18" />
                                <path d="M9 12.5h6M9 16h4" />
                            </template>
                            <template v-else-if="n.icon === 'trend'">
                                <path d="m4 17 5-5 3.5 3.5L20 9" />
                                <path d="M14.8 9H20v5.2" />
                            </template>
                            <template v-else-if="n.icon === 'clock'">
                                <circle cx="12" cy="12" r="8.2" />
                                <path d="M12 7.6V12l3 2.1" />
                            </template>
                            <template v-else-if="n.icon === 'gear'">
                                <circle cx="12" cy="12" r="3" />
                                <path
                                    d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 9 19.4a1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"
                                />
                            </template>
                            <template v-else-if="n.icon === 'users'">
                                <circle cx="9" cy="9" r="3.2" />
                                <path d="M3.8 19c.7-3 2.7-4.6 5.2-4.6s4.5 1.6 5.2 4.6" />
                                <path d="M15.5 6.3a3.2 3.2 0 0 1 0 5.6M17.3 14.7c1.6.7 2.7 2.1 3.1 4.3" />
                            </template></svg
                        >{{ n.label }}
                    </router-link>
                </template>
            </nav>
            <div class="sidebar-foot">清流 v{{ APP_VERSION }}</div>
        </aside>

        <div class="main">
            <header class="topbar">
                <button class="nav-toggle" aria-label="打开导航" @click="navOpen = !navOpen">
                    <svg viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
                </button>
                <ProjectSwitcher />
                <span v-if="iters.active" class="chip">{{ iters.active.code }} {{ iters.active.stateLabel }}</span>
                <div class="topbar-right">
                    <div class="search" @click="ui.searchOpen = true">
                        <svg viewBox="0 0 24 24">
                            <circle cx="11" cy="11" r="6.5" />
                            <path d="m16 16 4.5 4.5" />
                        </svg>
                        搜索任务、缺陷、发版计划<span class="kbd">⌘K</span>
                    </div>
                    <button
                        class="icon-btn"
                        type="button"
                        :title="ui.theme === 'dark' ? '切换到浅色主题' : '切换到暗色主题'"
                        :aria-label="ui.theme === 'dark' ? '切换到浅色主题' : '切换到暗色主题'"
                        @click="ui.toggleTheme()"
                    >
                        <svg v-if="ui.theme === 'dark'" viewBox="0 0 24 24">
                            <circle cx="12" cy="12" r="4" />
                            <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
                        </svg>
                        <svg v-else viewBox="0 0 24 24">
                            <path d="M20.5 14.4A8.5 8.5 0 0 1 9.6 3.5a8.5 8.5 0 1 0 10.9 10.9z" />
                        </svg>
                    </button>
                    <NotificationBell />
                    <div class="ava-box" @click.stop="menuOpen = !menuOpen">
                        <div class="ava" :class="auth.user?.color || 'blue'">{{ auth.user?.short || (auth.user?.name || '未')[0] }}</div>
                        <div v-if="menuOpen" class="user-menu">
                            <div class="um-head">
                                <div class="um-name">{{ auth.user?.name || '未登录' }}</div>
                                <div class="um-role">{{ [auth.user?.role, auth.user?.perm].filter(Boolean).join(' · ') || '—' }}</div>
                            </div>
                            <button class="um-item plain" @click="openProfile('info')">
                                <svg viewBox="0 0 24 24">
                                    <circle cx="12" cy="8" r="4" />
                                    <path d="M4 20c.8-3.6 3.6-5.4 8-5.4s7.2 1.8 8 5.4" />
                                </svg>
                                查看个人信息
                            </button>
                            <button class="um-item plain" @click="openProfile('password')">
                                <svg viewBox="0 0 24 24">
                                    <rect x="4" y="10" width="16" height="11" rx="2" />
                                    <path d="M8 10V7a4 4 0 0 1 8 0v3" />
                                </svg>
                                修改密码
                            </button>
                            <router-link to="/notify" class="um-item plain" @click="menuOpen = false">
                                <svg viewBox="0 0 24 24">
                                    <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
                                    <path d="M13.7 21a2 2 0 0 1-3.4 0" />
                                </svg>
                                通知设置
                            </router-link>
                            <button class="um-item" @click="logout">
                                <svg viewBox="0 0 24 24">
                                    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                                    <path d="M16 17l5-5-5-5" />
                                    <path d="M21 12H9" />
                                </svg>
                                退出登录
                            </button>
                        </div>
                    </div>
                </div>
            </header>

            <main class="content" :class="`w-${route.meta.width || 'wide'}`">
                <!-- 首屏加载中：占位，避免无项目成员触发各页面的请求报错 -->
                <div v-if="!ready" class="boot"><span class="boot-spin"></span></div>
                <!-- 非管理员且未被分配到任何项目：全局空状态（项目由系统管理员分配） -->
                <div v-else-if="!hasProject" class="no-project">
                    <div class="np-icon">
                        <svg viewBox="0 0 24 24">
                            <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
                            <path d="M3.5 9.5h17M9 4.5v15" />
                        </svg>
                    </div>
                    <div class="np-title">你还没有被分配到任何项目</div>
                    <div class="np-sub">项目由系统管理员创建并分配成员，请联系管理员将你加入项目。</div>
                </div>
                <router-view v-else />
            </main>
        </div>

        <GlobalCreateModals />
        <GlobalSearch />
        <ProfileModal v-if="profileOpen" :tab="profileTab" @close="profileOpen = false" />
    </div>
</template>

<style scoped>
.shell {
    display: flex;
    min-height: 100vh;
}
.sidebar {
    width: 224px;
    flex: none;
    background: var(--sidebar-bg);
    border-right: 1px solid var(--border);
    position: sticky;
    top: 0;
    height: 100vh;
    display: flex;
    flex-direction: column;
    z-index: 40;
}
.brand {
    display: flex;
    align-items: center;
    gap: 11px;
    padding: 20px 20px 16px;
}
.logo {
    height: 26px;
    width: auto;
    flex: none;
}
.brand-name {
    font-size: 15px;
    font-weight:700;
}
.brand-sub {
    font-size:12px;
    color: var(--t3);
    margin-top: 1px;
}
nav {
    flex: 1;
    padding: 6px 12px 12px;
    overflow-y: auto;
    overflow-x: hidden;
}
nav::-webkit-scrollbar {
    width: 6px;
}
.nav-title {
    font-size:12px;
    font-weight:400;
    color: var(--t3);
    padding: 16px 12px 6px;
    letter-spacing: 0.03em;
}
.nav-item {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 12px;
    margin-bottom: 2px;
    border-radius: 9px;
    font-size:14px;
    color: var(--t2);
    text-decoration: none;
    transition: background 0.15s var(--ease), color 0.15s var(--ease), transform 0.15s var(--ease);
}
.nav-item:hover {
    background: var(--track);
    color: var(--t1);
}
.nav-item svg {
    width: 16px;
    height: 16px;
    stroke: currentColor;
    stroke-width: 1.7;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
    flex: none;
    transition: transform 0.18s var(--ease);
}
.nav-item:hover svg {
    transform: translateY(-1px);
}
.nav-item.active {
    background: var(--primary-bg);
    color: var(--primary);
    font-weight:400;
    box-shadow: inset 0 0 0 1px rgba(51, 112, 255, 0.12);
}
.nav-item.active::before {
    content: '';
    position: absolute;
    left: -12px;
    top: 50%;
    transform: translateY(-50%);
    width: 3px;
    height: 18px;
    border-radius: 0 3px 3px 0;
    background: var(--primary);
}
.sidebar-foot {
    border-top: 1px solid var(--border);
    padding: 14px 24px;
    font-size:12px;
    color: var(--t3);
}

.main {
    flex: 1;
    min-width: 0;
}
.topbar {
    position: sticky;
    top: 0;
    z-index: 50;
    background: var(--topbar-bg);
    backdrop-filter: saturate(1.6) blur(12px);
    -webkit-backdrop-filter: saturate(1.6) blur(12px);
    border-bottom: 1px solid var(--border);
    box-shadow: var(--topbar-shadow);
    display: flex;
    align-items: center;
    gap: 14px;
    height: 56px;
    padding: 0 28px;
}
.topbar-right {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 12px;
}

/* ===== 头像菜单 / 退出登录 ===== */
.ava-box {
    position: relative;
    cursor: pointer;
    border-radius: 50%;
    transition: box-shadow 0.15s var(--ease);
}
.ava-box:hover {
    box-shadow: var(--ring);
}
.user-menu {
    position: absolute;
    right: 0;
    top: 42px;
    min-width: 210px;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    overflow: hidden;
    z-index: 60;
    box-shadow: var(--shadow-lg);
    animation: menuIn 0.16s var(--ease) both;
}
@keyframes menuIn {
    from {
        opacity: 0;
        transform: translateY(-6px) scale(0.98);
    }
    to {
        opacity: 1;
        transform: none;
    }
}
.um-head {
    padding: 12px 14px 10px;
    border-bottom: 1px solid var(--border);
    background: var(--menu-head-bg);
}
.um-name {
    font-size:14px;
    font-weight:700;
}
.um-role {
    font-size: 12px;
    color: var(--t3);
    margin-top: 2px;
}
.um-item {
    width: 100%;
    height: 38px;
    padding: 0 14px;
    border: none;
    background: var(--card);
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: var(--red);
    cursor: pointer;
    font-family: inherit;
    text-align: left;
    text-decoration: none;
    transition: background 0.13s var(--ease);
}
.um-item svg {
    width: 15px;
    height: 15px;
    stroke: var(--red);
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}
/* 普通菜单项（个人信息/修改密码/通知设置）：中性色，与红色「退出登录」区分 */
.um-item.plain {
    color: var(--t1);
}
.um-item.plain svg {
    stroke: var(--t2);
}
.um-item.plain:hover,
.um-item:hover {
    background: var(--bg);
}
/* 退出登录前的分隔线 */
.um-item:not(.plain) {
    border-top: 1px solid var(--border);
}
.search {
    width: 340px;
    height: 32px;
    background: var(--bg);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 12px;
    font-size:13px;
    color: var(--t3);
    cursor: pointer;
    transition: border-color 0.15s var(--ease), background 0.15s var(--ease), box-shadow 0.15s var(--ease);
}
.search:hover {
    background: var(--card);
    border-color: var(--border);
    box-shadow: var(--shadow-xs);
}
.search svg {
    width: 14px;
    height: 14px;
    stroke: var(--t3);
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
    flex: none;
}
.search .kbd {
    margin-left: auto;
    font-size:12px;
    color: var(--t3);
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 5px;
    padding: 1px 5px;
    line-height: 1.4;
}

/* 内容宽度分档（route.meta.width）：
   wide 数据页 1680 / mid 卡片仪表盘 1440 / narrow 表单 1080 / full 看板铺满 */
.content {
    max-width: 1680px;
    margin: 0 auto;
    padding: 24px 28px 56px;
    width: 100%;
}
.content.w-wide {
    max-width: 1680px;
}
.content.w-mid {
    max-width: 1440px;
}
.content.w-narrow {
    max-width: 1080px;
}
.content.w-full {
    max-width: none;
}

/* ===== 首屏加载占位 / 无项目空状态 ===== */
.boot {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 120px 0;
}
.boot-spin {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    border: 2.5px solid var(--border);
    border-top-color: var(--primary);
    animation: bootSpin 0.7s linear infinite;
}
@keyframes bootSpin {
    to {
        transform: rotate(360deg);
    }
}
.no-project {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    padding: 96px 24px;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: var(--shadow-sm);
    margin-top: 8px;
}
.no-project .np-icon {
    width: 56px;
    height: 56px;
    border-radius: 16px;
    background: var(--primary-bg);
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 16px;
}
.no-project .np-icon svg {
    width: 27px;
    height: 27px;
    stroke: var(--primary);
    stroke-width: 1.7;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.no-project .np-title {
    font-size: 15px;
    font-weight:700;
    color: var(--t1);
}
.no-project .np-sub {
    font-size: 13px;
    color: var(--t3);
    margin-top: 6px;
    max-width: 380px;
    line-height: 1.7;
}

/* ===== 移动端：侧边栏改为抽屉 ===== */
.nav-toggle {
    display: none;
    width: 32px;
    height: 32px;
    flex: none;
    border: none;
    background: none;
    border-radius: 8px;
    cursor: pointer;
    align-items: center;
    justify-content: center;
    color: var(--t2);
}
.nav-toggle:hover {
    background: var(--track);
}
.nav-toggle svg {
    width: 18px;
    height: 18px;
    stroke: currentColor;
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
}
.nav-mask {
    position: fixed;
    inset: 0;
    z-index: 65;
    background: var(--overlay-mask);
}

@media (max-width: 880px) {
    .sidebar {
        position: fixed;
        left: 0;
        top: 0;
        bottom: 0;
        height: 100vh;
        z-index: 70;
        transform: translateX(-100%);
        transition: transform 0.22s var(--ease);
        box-shadow: var(--shadow-lg);
    }
    .sidebar.open {
        transform: none;
    }
    .search {
        display: none;
    }
    .nav-toggle {
        display: flex;
    }
    .topbar {
        padding: 0 12px;
        gap: 10px;
    }
    .topbar-right {
        gap: 8px;
    }
    .content {
        padding: 18px 14px 48px;
    }
}
</style>
