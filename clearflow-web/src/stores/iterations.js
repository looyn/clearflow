// ===== 迭代共享 store：下拉候选 / 进行中迭代 / 发布倒计时 =====
// 数据来自 GET /iterations/timeline（全站共享的精简迭代列表，不含复盘正文）
import { defineStore } from 'pinia';
import { api } from '../api';
import { useOptionsStore } from './options';

export const useIterationsStore = defineStore('iterations', {
    state: () => ({ list: [] }),
    getters: {
        /** 下拉候选：真实迭代（code · name）+「待排期」兜底 */
        options: (s) => [...s.list.map((i) => ({ code: i.code, label: `${i.code} · ${i.name}` })), { code: '待排期', label: '待排期' }],
        /** 进行中迭代（取工作流 iterColumns 第二档，与后端 activeState 判定一致） */
        active: (s) => {
            const keys = useOptionsStore().keysOf('iteration');
            const doing = keys[1] || keys[0] || 'active';
            return s.list.find((i) => i.state === doing) || null;
        },
        /** 距发布剩余天数（解析 range 的结束日期，无法解析或已到窗口返回 null） */
        daysLeft() {
            const end = String(this.active?.range || '').split('~')[1]?.trim();
            const d = end ? new Date(end) : null;
            if (!d || isNaN(d)) return null;
            const days = Math.ceil((d - new Date()) / 86400000);
            return days > 0 ? days : null;
        }
    },
    actions: {
        async load() {
            try {
                this.list = (await api.getIterations()) || [];
            } catch {
                // 拉取失败保留上次列表，不影响下拉渲染
            }
        }
    }
});
