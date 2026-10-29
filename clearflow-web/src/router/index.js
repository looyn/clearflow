import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/login', name: '登录', component: () => import('../views/LoginView.vue') },
  { path: '/', component: MainLayout, redirect: '/overview', children: [
    { path: 'overview', name: '概览', component: () => import('../views/OverviewView.vue'), meta: { width: 'mid' } },
    { path: 'mywork', name: '我的工作', component: () => import('../views/MyWorkView.vue'), meta: { width: 'mid' } },
    { path: 'requirements', name: '需求池', component: () => import('../views/RequirementPoolView.vue') },
    { path: 'iterations', name: '迭代规划', component: () => import('../views/IterationPlanView.vue'), meta: { width: 'mid' } },
    { path: 'board', name: '任务看板', component: () => import('../views/TaskBoardView.vue'), meta: { width: 'full' } },
    { path: 'defects', name: '缺陷追踪', component: () => import('../views/DefectTrackView.vue') },
    { path: 'releases', name: '发版计划', component: () => import('../views/ReleasePlanView.vue') },
    { path: 'docs', name: '文档管理', component: () => import('../views/DocumentsView.vue') },
    { path: 'metrics', name: '度量报表', component: () => import('../views/MetricsView.vue'), meta: { width: 'mid' } },
    { path: 'worklog', name: '工时统计', component: () => import('../views/WorklogView.vue') },
    { path: 'projects', name: '项目管理', component: () => import('../views/ProjectsView.vue') },
    { path: 'settings', name: '项目设置', component: () => import('../views/SettingsView.vue'), meta: { width: 'mid' } },
    { path: 'notify', name: '通知设置', component: () => import('../views/NotifySettingsView.vue'), meta: { width: 'narrow' } },
    { path: 'members', name: '成员', component: () => import('../views/MemberView.vue') },
    { path: 'perms', name: '权限矩阵', component: () => import('../views/PermMatrixView.vue') }
  ] }
]

const router = createRouter({ history: createWebHistory(), routes })

// 登录守卫：未登录跳登录页（携带原路径），已登录访问登录页跳概览
router.beforeEach(to => {
  const auth = useAuthStore()
  if (!auth.logged && to.path !== '/login') return { path: '/login', query: { redirect: to.fullPath } }
  if (auth.logged && to.path === '/login') return '/overview'
})

export default router
