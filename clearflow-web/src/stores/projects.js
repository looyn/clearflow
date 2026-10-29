// ===== 项目共享 store：项目列表 + 当前项目 =====
// 项目切换 / 项目增删归档后由写入方显式 load() 刷新；切项目也走 settingsTick 全量刷新
import { defineStore } from 'pinia';
import { api } from '../api';

export const useProjectsStore = defineStore('projects', {
    state: () => ({ list: [], current: null, inflight: null }),
    getters: {
        currentId: (s) => s.current?.id ?? null
    },
    actions: {
        async load() {
            if (this.inflight) return this.inflight;
            this.inflight = (async () => {
                try {
                    const [ps, cp] = await Promise.all([api.getProjects(), api.getCurrentProject()]);
                    this.list = ps;
                    this.current = cp;
                } finally {
                    this.inflight = null;
                }
            })();
            return this.inflight;
        },
        clear() {
            this.$reset();
        }
    }
});
