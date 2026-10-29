// ===== 成员共享 store：成员下拉 / 名称映射的全局数据源 =====
// 数据源为 GET /members/brief（全员可读，仅 id/姓名/头像/职位）；成员管理页的邮箱/角色等
// 全量字段仅系统管理员可读，由 MemberView / ProjectsView 自行拉取 GET /members。
// 成员低频变化：MainLayout 启动拉一次，成员写操作（成员页/邀请）后显式强刷
import { defineStore } from 'pinia';
import { api } from '../api';

export const useMembersStore = defineStore('members', {
    state: () => ({ list: [], loaded: false, inflight: null }),
    getters: {
        /** id → 成员（展示名/头像/角色统一从这里取） */
        map: (s) => Object.fromEntries(s.list.map((m) => [m.id, m]))
    },
    actions: {
        /** 拉取成员；已加载且非强刷时直接复用，避免多处同时打开重复请求 */
        async load(force = false) {
            if (!force && this.loaded) return;
            if (this.inflight) return this.inflight;
            this.inflight = (async () => {
                try {
                    this.list = await api.getMemberBriefs();
                    this.loaded = true;
                } finally {
                    this.inflight = null;
                }
            })();
            return this.inflight;
        }
    }
});
