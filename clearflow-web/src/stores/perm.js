// ===== 权限画像 store：菜单过滤与按钮显隐的数据源 =====
import { defineStore } from 'pinia';
import { api } from '../api';

// 等级权重（与后端 PermConstants.rank 一致）
const RANK = { 无: 0, 查看: 1, 编辑: 2, 管理: 3 };

export const usePermStore = defineStore('perm', {
    state: () => ({
        perm: '',
        isAdmin: false,
        levels: {},           // { 模块名: 等级 }
        ownedProjectIds: []   // 当前用户负责的项目 id 列表
    }),
    getters: {
        /** 模块可见（等级高于「无」） */
        canSee: (s) => (module) => s.isAdmin || (RANK[s.levels[module] ?? '查看'] ?? 1) > 0,
        /** 模块可写（等级 ≥ 编辑） */
        canWrite: (s) => (module) => s.isAdmin || (RANK[s.levels[module] ?? '查看'] ?? 1) >= RANK['编辑'],
        /** 模块可删（等级 = 管理）：删除类入口按此显隐，与后端 DELETE 接口同口径 */
        canManage: (s) => (module) => s.isAdmin || (RANK[s.levels[module] ?? '查看'] ?? 1) >= RANK['管理'],
        /** 是否项目（id）负责人 */
        owns: (s) => (projectId) => s.isAdmin || s.ownedProjectIds.includes(projectId)
    },
    actions: {
        async load() {
            const me = await api.getPermMe();
            this.perm = me.perm || '';
            this.isAdmin = !!me.isAdmin;
            this.levels = me.levels || {};
            this.ownedProjectIds = me.ownedProjectIds || [];
        },
        clear() {
            this.$reset();
        }
    }
});
