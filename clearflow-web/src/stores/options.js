// ===== 项目选项共享 store（优先级 / 严重度 / 任务类型 / 标签 / 环境 / 各类状态流）=====
// 数据来自「项目设置 → 选项配置 / 工作流」：/projects/{id}/settings 的 options、workflow 分区。
// 新建/编辑表单、筛选器、优先级标签、状态胶囊统一从这里取，保证设置改了全线生效。
import { defineStore } from 'pinia';
import { api } from '../api';
import { useProjectsStore } from './projects';

const clone = (v) => JSON.parse(JSON.stringify(v));

// 兜底配置（与种子数据一致）：接口不可用或项目未配置时使用，界面不会出现空下拉
const DEFAULT_OPTIONS = {
    priorities: [
        { key: 'p0', name: 'P0 紧急', color: '#F54A45' },
        { key: 'p1', name: 'P1 高', color: '#FF7D00' },
        { key: 'p2', name: 'P2 普通', color: '#3370FF' }
    ],
    severities: [
        { key: '致命', name: '致命', color: '#F54A45' },
        { key: '严重', name: '严重', color: '#FF7D00' },
        { key: '一般', name: '一般', color: '#3370FF' },
        { key: '轻微', name: '轻微', color: '#8F959E' }
    ],
    taskTypes: [
        { key: 'fe', name: '前端', color: '#3370FF' },
        { key: 'be', name: '后端', color: '#22C55E' },
        { key: 'qa', name: '测试', color: '#7A5AF8' },
        { key: 'de', name: '设计', color: '#FF7D00' }
    ],
    labels: ['核心链路', '支付', '性能', '对账', '体验', '文档'],
    // 环境为纯字符串列表（缺陷里 env 直接存字符串，没有 key/name 结构）
    envs: ['生产', '预发', '测试']
};

// ===== 状态流（项目设置 → 工作流）：每类实体一份 [{key,name,color}] =====
// 顺序即单向流转顺序（只进不退），**最后一档为终态**；key 与数据里存的 status 对应。
// 发版的「已回滚 rolled」为发版特有状态，不进配置（见 EXTRA_NAMES），前后端都按特例处理。
export const DEFAULT_WORKFLOW = {
    // 任务状态（任务看板列）
    columns: [
        { key: 'todo', name: '待处理', color: '#8F959E' },
        { key: 'doing', name: '进行中', color: '#3370FF' },
        { key: 'review', name: '待验收', color: '#7A5AF8' },
        { key: 'done', name: '已完成', color: '#22C55E' }
    ],
    // 缺陷状态（缺陷看板列）
    defectColumns: [
        { key: 'open', name: '待处理', color: '#F54A45' },
        { key: 'fixing', name: '修复中', color: '#FF7D00' },
        { key: 'fixed', name: '已修复', color: '#3370FF' },
        { key: 'verified', name: '已验证', color: '#2EA86E' },
        { key: 'closed', name: '已关闭', color: '#8F959E' }
    ],
    // 需求状态：key 即存量数据里的中文状态值，改名只改展示
    reqColumns: [
        { key: '待评审', name: '待评审', color: '#8F959E' },
        { key: '已排期', name: '已排期', color: '#3370FF' },
        { key: '研发中', name: '研发中', color: '#FF7D00' },
        { key: '已发布', name: '已发布', color: '#22C55E' }
    ],
    // 发版计划状态（rolled 已回滚不在此列）
    relColumns: [
        { key: 'planning', name: '筹备中', color: '#8F959E' },
        { key: 'testing', name: '测试中', color: '#3370FF' },
        { key: 'ready', name: '待发布', color: '#FF7D00' },
        { key: 'released', name: '已发布', color: '#22C55E' }
    ],
    // 迭代状态
    iterColumns: [
        { key: 'planned', name: '已规划', color: '#8F959E' },
        { key: 'active', name: '进行中', color: '#3370FF' },
        { key: 'released', name: '已发布', color: '#22C55E' }
    ]
};

// 实体 → workflow 分区键（前后端同构）
const ENTITY_SECTION = {
    task: 'columns',
    defect: 'defectColumns',
    requirement: 'reqColumns',
    release: 'relColumns',
    iteration: 'iterColumns'
};

// 不进配置的特有状态：只补展示名（配置里永远没有这些 key）
const EXTRA_NAMES = { release: { rolled: '已回滚' } };

// #RRGGBB → 半透明 rgba（状态胶囊按配置色渲染，保留 14% 底色）
function alpha(hex, a) {
    const m = /^#([0-9a-f]{6})$/i.exec(String(hex || ''));
    if (!m) return null;
    const n = parseInt(m[1], 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}

const pick = (list, def) => (Array.isArray(list) && list.length ? list : clone(def));

function findBy(list, key, fallbackName) {
    if (key === null || key === undefined || key === '') return null;
    const hit = list.find((x) => String(x.key) === String(key));
    if (hit) return hit;
    // 历史数据里有、设置里被删掉的选项：仍按 key 展示，避免空白标签
    return { key, name: fallbackName(key), color: null };
}

export const useOptionsStore = defineStore('options', {
    state: () => ({
        options: clone(DEFAULT_OPTIONS),
        workflow: clone(DEFAULT_WORKFLOW),
        projectId: null,
        project: null,
        // 业务模块字典（项目级）：需求 / 任务 / 缺陷共用
        modules: []
    }),
    getters: {
        priorities: (s) => pick(s.options.priorities, DEFAULT_OPTIONS.priorities),
        severities: (s) => pick(s.options.severities, DEFAULT_OPTIONS.severities),
        taskTypes: (s) => pick(s.options.taskTypes, DEFAULT_OPTIONS.taskTypes),
        labels: (s) => pick(s.options.labels, DEFAULT_OPTIONS.labels),
        envs: (s) => pick(s.options.envs, DEFAULT_OPTIONS.envs),
        // 缺陷状态（项目设置 → 工作流 → 缺陷工作流）：顺序即流转顺序
        defectStatuses: (s) => pick(s.workflow?.defectColumns, DEFAULT_WORKFLOW.defectColumns)
    },
    actions: {
        async load() {
            try {
                const pstore = useProjectsStore();
                await pstore.load(); // 复用共享的当前项目（未加载时这里才会真正拉取）
                const p = pstore.current;
                if (!p) {
                    this.projectId = null;
                    this.project = null;
                    this.modules = [];
                    return;
                }
                const ps = await api.getProjectSettings(p.id);
                this.projectId = p.id;
                this.project = p;
                this.options = {
                    priorities: pick(ps?.options?.priorities, DEFAULT_OPTIONS.priorities),
                    severities: pick(ps?.options?.severities, DEFAULT_OPTIONS.severities),
                    taskTypes: pick(ps?.options?.taskTypes, DEFAULT_OPTIONS.taskTypes),
                    labels: pick(ps?.options?.labels, DEFAULT_OPTIONS.labels),
                    envs: pick(ps?.options?.envs, DEFAULT_OPTIONS.envs)
                };
                const wf = {};
                Object.keys(DEFAULT_WORKFLOW).forEach((k) => {
                    wf[k] = pick(ps?.workflow?.[k], DEFAULT_WORKFLOW[k]);
                });
                this.workflow = wf;
                // 模块字典失败不影响其它选项渲染
                try {
                    const list = await api.getModules();
                    this.modules = Array.isArray(list) ? list : [];
                } catch (e) {
                    this.modules = [];
                }
            } catch (e) {
                // 拉取失败保留上一次（或兜底）配置，不影响页面渲染
            }
        },
        async loadModules() {
            const list = await api.getModules();
            this.modules = Array.isArray(list) ? list : [];
            return this.modules;
        },
        moduleName(id) {
            if (id === null || id === undefined || id === '') return '';
            return this.modules.find((m) => String(m.id) === String(id))?.name || '';
        },
        prio(key) {
            return findBy(this.priorities, key, (k) => String(k).toUpperCase());
        },
        prioName(key) {
            return this.prio(key)?.name || '';
        },
        severity(key) {
            return findBy(this.severities, key, (k) => String(k));
        },
        taskType(key) {
            return findBy(this.taskTypes, key, (k) => String(k));
        },

        // ===== 状态流（task / defect / requirement / release / iteration）=====
        /** 某实体的状态列表 [{key,name,color}]，顺序即流转顺序 */
        statuses(entity) {
            const sec = ENTITY_SECTION[entity];
            return pick(this.workflow?.[sec], DEFAULT_WORKFLOW[sec]);
        },
        /** 流转 key 序列（与数据里的 status 对应） */
        keysOf(entity) {
            return this.statuses(entity).map((s) => s.key);
        },
        /** 终态（配置顺序最后一档：done / 已发布 / released / closed） */
        terminalOf(entity) {
            const k = this.keysOf(entity);
            return k[k.length - 1];
        },
        /** 单个状态项；配置里没有的历史 key 返回 null */
        statusOf(entity, key) {
            if (key === null || key === undefined || key === '') return null;
            return this.statuses(entity).find((x) => String(x.key) === String(key)) || null;
        },
        /** 状态展示名：配置名 → 特有状态补名 → key 兜底（被删掉的历史状态仍可读） */
        nameOf(entity, key) {
            if (key === null || key === undefined) return '';
            const hit = this.statusOf(entity, key);
            if (hit && hit.name) return hit.name;
            const extra = EXTRA_NAMES[entity] && EXTRA_NAMES[entity][key];
            return extra || String(key);
        },
        /** 状态胶囊配色：按配置色渲染（未知 key 无内联色时回退 CSS 类） */
        styleOf(entity, key) {
            const s = this.statusOf(entity, key);
            const bg = s ? alpha(s.color, 0.14) : null;
            if (!s || !bg) return {};
            return { background: bg, color: s.color };
        },

        // ===== 缺陷状态别名（既有调用点）=====
        defName(key) {
            return this.nameOf('defect', key);
        },
        defStyle(key) {
            return this.styleOf('defect', key);
        }
    }
});
