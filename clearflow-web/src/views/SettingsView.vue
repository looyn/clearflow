<script setup>
import { ref, computed, reactive, onMounted, watch } from 'vue';
import { api } from '../api';
import { useUiStore } from '../stores/ui';
import { usePermStore } from '../stores/perm';
import { useMembersStore } from '../stores/members';
import { DEFAULT_WORKFLOW, useOptionsStore } from '../stores/options';
import LoadingMask from '../components/LoadingMask.vue';
import AppConfirm from '../components/AppConfirm.vue';

const ui = useUiStore();
const perm = usePermStore();
const members = useMembersStore();
const opts = useOptionsStore(); // 当前项目对象（project）由这里提供，避免重复拉 getCurrentProject
const loading = ref(true);

// ===== 左侧 in-page tab（本地 state，不做子路由）=====
// 「项目设置」已移除：项目基本信息在项目管理页编辑，危险操作也在项目管理页
const TABS = [
    { key: 'workflow', label: '工作流' },
    { key: 'options', label: '选项配置' },
    { key: 'modules', label: '业务模块' }
];
const tab = ref('workflow');

// 8 色色板（工作流列 / 选项 / 项目颜色共用）
const COLORS = ['#3370FF', '#22C55E', '#7A5AF8', '#FF7D00', '#F54A45', '#12B7C4', '#E0218A', '#8F959E'];

// ===== 数据（深拷贝为编辑副本，未保存不会改到 db）=====
const project = ref(null);

// 工作流按实体分块：任务 / 缺陷 / 需求 / 发版计划 / 迭代 各一套状态配置、各自保存。
// key = workflow 分区里的键；顺序即单向流转顺序，最后一档为终态。
const FLOW_BLOCKS = [
    { key: 'columns', saveKey: 'workflow', title: '任务工作流', short: '任务', desc: '看板列即任务状态，自上而下单向流转（最后一档为终态）；仅当前项目生效。', ph: '列名称', add: '+ 添加列', prefix: 'col' },
    {
        key: 'defectColumns',
        saveKey: 'defectWorkflow',
        title: '缺陷工作流',
        short: '缺陷',
        desc: '缺陷状态即看板列与筛选项，自上而下单向流转（最后一档为终态）；仅当前项目生效。',
        ph: '状态名称',
        add: '+ 添加状态',
        prefix: 'st'
    },
    { key: 'reqColumns', saveKey: 'reqWorkflow', title: '需求工作流', short: '需求', desc: '需求状态用于需求池筛选与流转，最后一档为终态（默认「已发布」）；仅当前项目生效。', ph: '状态名称', add: '+ 添加状态', prefix: 'st' },
    {
        key: 'relColumns',
        saveKey: 'relWorkflow',
        title: '发版计划工作流',
        short: '发版计划',
        desc: '发版状态自上而下单向流转（最后一档为终态）；「已回滚」为发版特有状态，不参与配置。仅当前项目生效。',
        ph: '状态名称',
        add: '+ 添加状态',
        prefix: 'st'
    },
    { key: 'iterColumns', saveKey: 'iterWorkflow', title: '迭代工作流', short: '迭代', desc: '迭代状态自上而下单向流转，最后一档为终态；仅当前项目生效。', ph: '状态名称', add: '+ 添加状态', prefix: 'st' }
];
// 保存成功后需要通知选项 store 重拉的分区 key（都落在 workflow/options 分区里）
const REFRESH_KEYS = new Set(['workflow', 'options', 'defectWorkflow', 'reqWorkflow', 'relWorkflow', 'iterWorkflow']);

const flows = reactive({}); // 分区键 -> 状态列表（编辑副本）
const flowColor = reactive({}); // 分区键 -> 正在选色的状态索引
const flowErr = reactive({}); // 分区键 -> 校验/保存前提示（空串表示无错）

const options = ref({ priorities: [], severities: [], taskTypes: [], labels: [], envs: [] });

// 项目级配置（工作流/选项配置）仅管理员或项目负责人可编辑
const canEditProject = computed(() => project.value && perm.owns(project.value.id));

async function load() {
    const p = opts.project;
    project.value = p;
    if (p) {
        await members.load();
        const ps = await api.getProjectSettings(p.id);
        // 各实体状态：配置为空时按默认值展示，便于首次保存即写入配置
        FLOW_BLOCKS.forEach((b) => {
            const v = ps.workflow?.[b.key];
            flows[b.key] = JSON.parse(JSON.stringify(Array.isArray(v) && v.length ? v : DEFAULT_WORKFLOW[b.key]));
            flowColor[b.key] = -1;
            flowErr[b.key] = '';
        });
        const o = JSON.parse(JSON.stringify(ps.options || {}));
        // 老配置里可能缺某个分组（如新增的环境），补齐空数组避免模板迭代 undefined
        ['priorities', 'severities', 'taskTypes', 'labels', 'envs'].forEach((k) => {
            if (!Array.isArray(o[k])) o[k] = [];
        });
        options.value = o;
        await loadModules();
    }
}
onMounted(async () => {
    await perm.load();
    await opts.load(); // 先确保当前项目与设置就绪（切换项目/保存后也走这里）
    await load();
    loading.value = false;
});
// 切换项目（notifySettings）或本页保存成功（runSave → notifySettings）后重载
watch(() => ui.settingsTick, load);

// ===== 保存反馈：按钮「保存中… → 已保存 ✓」+ 行内报错，2 秒后自动恢复 =====
const saved = ref(''); // 刚保存成功的分区 key（workflow / options / notify-xxx）
const saving = ref(''); // 正在保存的分区 key
const saveErr = reactive({}); // 分区 key -> 失败原因（保存成功后清除）
let savedTimer = null;
function flashSaved(key) {
    saved.value = key;
    clearTimeout(savedTimer);
    savedTimer = setTimeout(() => {
        saved.value = '';
    }, 2000);
}
// 统一保存流程：保存中 → 成功闪「已保存」→ 失败行内提示，不让异常悄悄吞掉
async function runSave(key, fn) {
    saving.value = key;
    delete saveErr[key];
    try {
        await fn();
        flashSaved(key);
        // 选项配置/工作流保存成功后通知选项 store 重新拉取（通知为个人偏好，无需刷新）
        if (REFRESH_KEYS.has(key)) ui.notifySettings();
    } catch (e) {
        saveErr[key] = e?.message || '保存失败，请稍后重试';
    } finally {
        saving.value = '';
    }
}
// 保存按钮文案：保存中… / 已保存 ✓ / 默认
function btnLabel(key, label) {
    if (saving.value === key) return '保存中…';
    if (saved.value === key) return '已保存 ✓';
    return label;
}

// ===== Tab 1 工作流（项目级）：五类状态共用一套增删/换色/排序/保存逻辑 =====
// 五套流程一次只编辑一套，顶部子标签切换，避免长页面里五个保存按钮互相干扰
const flowTab = ref('columns');
const activeFlow = computed(() => FLOW_BLOCKS.find((b) => b.key === flowTab.value) || FLOW_BLOCKS[0]);
function pickFlowTab(key) {
    const prev = activeFlow.value.key;
    if (prev !== key) flowColor[prev] = -1;
    flowTab.value = key;
}
function moveFlow(key, i, d) {
    const arr = flows[key];
    const j = i + d;
    if (!arr || j < 0 || j >= arr.length) return;
    [arr[i], arr[j]] = [arr[j], arr[i]];
}
function delFlow(key, i) {
    flows[key].splice(i, 1);
}
function addFlow(b) {
    const arr = flows[b.key];
    let n = 1;
    while (arr.some((c) => c.key === b.prefix + n)) n++;
    arr.push({ key: b.prefix + n, name: '', color: '#3370FF' });
}
async function saveFlow(b) {
    const list = flows[b.key];
    flowErr[b.key] = !list.length ? '至少保留一个状态' : list.some((c) => !c.name.trim()) ? '状态名称不能为空' : '';
    if (flowErr[b.key]) return;
    flowColor[b.key] = -1;
    await runSave(b.saveKey, () => api.saveProjectSettings(project.value.id, 'workflow', { [b.key]: list }));
}
// 点击内容区空白收起所有色板
function closePalettes() {
    Object.keys(flowColor).forEach((k) => {
        flowColor[k] = -1;
    });
    optColorKey.value = '';
}

// ===== Tab 2 选项配置（项目级）=====
const OPT_GROUPS = [
    { key: 'priorities', label: '优先级' },
    { key: 'severities', label: '严重度' },
    { key: 'taskTypes', label: '任务类型' }
];
const optColorKey = ref(''); // 正在选色的选项，如 'priorities-0'
const optErr = ref(false);
function toggleOptColor(g, i) {
    const k = g + '-' + i;
    optColorKey.value = optColorKey.value === k ? '' : k;
}
function addOpt(g) {
    const list = options.value[g];
    let n = list.length + 1;
    while (list.some((x) => x.key === 'k' + n)) n++;
    list.push({ key: 'k' + n, name: '', color: '#3370FF' });
}
function delOpt(g, i) {
    options.value[g].splice(i, 1);
}
async function saveOptions() {
    const bad = OPT_GROUPS.some((g) => options.value[g.key].some((x) => !x.name.trim()));
    optErr.value = bad;
    if (bad) return;
    optColorKey.value = '';
    await runSave('options', () => api.saveProjectSettings(project.value.id, 'options', { ...options.value }));
}

// 标签管理
const labelInput = ref('');
function addLabel() {
    const v = labelInput.value.trim();
    if (v && !options.value.labels.includes(v)) options.value.labels.push(v);
    labelInput.value = '';
}
function delLabel(i) {
    options.value.labels.splice(i, 1);
}

// 环境管理（缺陷的「环境」下拉取这里，纯字符串列表）
const envInput = ref('');
function addEnv() {
    const v = envInput.value.trim();
    if (v && !options.value.envs.includes(v)) options.value.envs.push(v);
    envInput.value = '';
}
function delEnv(i) {
    options.value.envs.splice(i, 1);
}

// ===== Tab 3 业务模块（项目级字典）：需求 / 任务 / 缺陷统一归类，统计按此聚合 =====
const modules = ref([]);
const moduleDraft = reactive({ name: '', owner: '', description: '' });
const moduleEditingId = ref(null);
const moduleEdit = reactive({ name: '', owner: '', description: '' });
const moduleErr = ref('');
const moduleSaving = ref(false);
const confirmModuleDel = ref(null);

async function loadModules() {
    modules.value = await api.getModules();
}
function startModuleEdit(m) {
    moduleEditingId.value = m.id;
    moduleEdit.name = m.name;
    moduleEdit.owner = m.owner ?? '';
    moduleEdit.description = m.description || '';
    moduleErr.value = '';
}
function cancelModuleEdit() {
    moduleEditingId.value = null;
    moduleErr.value = '';
}
async function addModule() {
    moduleErr.value = '';
    if (!moduleDraft.name.trim()) {
        moduleErr.value = '模块名称不能为空';
        return;
    }
    moduleSaving.value = true;
    try {
        await api.createModule({
            name: moduleDraft.name.trim(),
            owner: moduleDraft.owner || null,
            description: moduleDraft.description.trim()
        });
        moduleDraft.name = '';
        moduleDraft.owner = '';
        moduleDraft.description = '';
        await loadModules();
        await opts.loadModules();
    } catch (e) {
        moduleErr.value = e?.message || '新建失败，请稍后重试';
    } finally {
        moduleSaving.value = false;
    }
}
async function saveModule(id) {
    moduleErr.value = '';
    if (!moduleEdit.name.trim()) {
        moduleErr.value = '模块名称不能为空';
        return;
    }
    moduleSaving.value = true;
    try {
        await api.updateModule(id, {
            name: moduleEdit.name.trim(),
            owner: moduleEdit.owner || null,
            description: moduleEdit.description.trim()
        });
        moduleEditingId.value = null;
        await loadModules();
        await opts.loadModules();
        ui.notifyRefresh();
    } catch (e) {
        moduleErr.value = e?.message || '保存失败，请稍后重试';
    } finally {
        moduleSaving.value = false;
    }
}
async function removeModule() {
    const m = confirmModuleDel.value;
    confirmModuleDel.value = null;
    if (!m) return;
    moduleErr.value = '';
    try {
        await api.deleteModule(m.id);
        await loadModules();
        await opts.loadModules();
    } catch (e) {
        moduleErr.value = e?.message || '删除失败：该模块下仍有需求 / 任务 / 缺陷';
    }
}
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else>
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>设置</h1>
                <div class="page-meta">项目、工作流与个人偏好配置</div>
            </div>
        </div>

        <div class="set-wrap rise d2">
            <!-- 左侧竖排 tab -->
            <nav class="set-tabs">
                <button v-for="t in TABS" :key="t.key" class="set-tab" :class="{ active: tab === t.key }" @click="tab = t.key">{{ t.label }}</button>
            </nav>

            <!-- 右侧内容区（页面整体滚动，无内部滚动条）；点击空白处收起色板 -->
            <div class="set-main" @click="closePalettes">
                <!-- ===== Tab 1 工作流（项目级）：顶部子标签切换五套流程，共用下方一个编辑卡片 ===== -->
                <template v-if="tab === 'workflow'">
                    <div class="set-toolbar">
                        <div>
                            <div class="set-toolbar-t">工作流<span v-if="project" class="toolbar-proj"> · {{ project.name }}</span></div>
                            <div class="set-toolbar-s">各类状态即看板列、筛选项与流转按钮，顺序即流转顺序（最后一档为终态），仅当前项目生效。</div>
                        </div>
                    </div>
                    <div v-if="!canEditProject" class="field-error opt-err">当前账号无该项目配置权限（仅系统管理员或项目负责人可修改）</div>

                    <div class="flow-tabs" role="tablist">
                        <button
                            v-for="b in FLOW_BLOCKS"
                            :key="b.key"
                            class="flow-tab"
                            :class="{ active: flowTab === b.key }"
                            role="tab"
                            :aria-selected="flowTab === b.key"
                            @click="pickFlowTab(b.key)"
                        >
                            {{ b.short }}<span class="flow-count">{{ flows[b.key]?.length ?? 0 }}</span>
                        </button>
                    </div>

                    <div class="card wf-card">
                        <div class="wf-head">
                            <div class="wf-head-txt">
                                <div class="wf-title">{{ activeFlow.title }}</div>
                                <div class="wf-desc">{{ activeFlow.desc }}</div>
                            </div>
                            <div class="toolbar-acts">
                                <b v-if="saved === activeFlow.saveKey" class="saved-tip">已保存</b>
                                <button
                                    class="btn"
                                    :class="{ ok: saved === activeFlow.saveKey }"
                                    :disabled="!canEditProject || saving === activeFlow.saveKey"
                                    :title="canEditProject ? '' : '仅系统管理员或项目负责人可修改'"
                                    @click="saveFlow(activeFlow)"
                                >
                                    {{ btnLabel(activeFlow.saveKey, '保存') }}
                                </button>
                            </div>
                        </div>
                        <div v-if="flowErr[activeFlow.key]" class="field-error wf-err">{{ flowErr[activeFlow.key] }}</div>
                        <div v-if="saveErr[activeFlow.saveKey]" class="field-error wf-err">保存失败：{{ saveErr[activeFlow.saveKey] }}</div>
                        <div class="wf-list">
                            <div v-for="(c, i) in flows[activeFlow.key]" :key="c.key" class="wf-row">
                                <span class="wf-order">{{ i + 1 }}</span>
                                <span class="swatch" :style="{ background: c.color }" @click.stop="flowColor[activeFlow.key] = flowColor[activeFlow.key] === i ? -1 : i"></span>
                                <div v-if="flowColor[activeFlow.key] === i" class="palette" @click.stop>
                                    <span
                                        v-for="cc in COLORS"
                                        :key="cc"
                                        class="swatch"
                                        :class="{ on: c.color === cc }"
                                        :style="{ background: cc }"
                                        @click="
                                            c.color = cc;
                                            flowColor[activeFlow.key] = -1;
                                        "
                                    ></span>
                                </div>
                                <input class="field-input wf-name" :class="{ invalid: flowErr[activeFlow.key] && !c.name.trim() }" v-model="c.name" :placeholder="activeFlow.ph" />
                                <span v-if="c.key !== c.name" class="wf-key">{{ c.key }}</span>
                                <span v-if="i === flows[activeFlow.key].length - 1" class="wf-terminal">终态</span>
                                <button class="mini-btn" :disabled="i === 0" title="上移" @click="moveFlow(activeFlow.key, i, -1)">
                                    <svg viewBox="0 0 24 24"><path d="m6 14 6-6 6 6" /></svg>
                                </button>
                                <button class="mini-btn" :disabled="i === flows[activeFlow.key].length - 1" title="下移" @click="moveFlow(activeFlow.key, i, 1)">
                                    <svg viewBox="0 0 24 24"><path d="m6 10 6 6 6-6" /></svg>
                                </button>
                                <button class="mini-btn del" title="删除" @click="delFlow(activeFlow.key, i)">
                                    <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                                </button>
                            </div>
                            <div v-if="!flows[activeFlow.key].length" class="row-empty">暂无状态，点击下方「{{ activeFlow.add }}」添加</div>
                        </div>
                        <div class="wf-foot">
                            <a class="link-btn" @click="addFlow(activeFlow)">{{ activeFlow.add }}</a>
                        </div>
                    </div>
                </template>

                <!-- ===== Tab 2 选项配置（项目级）===== -->
                <template v-else-if="tab === 'options'">
                    <div class="set-toolbar">
                        <div>
                            <div class="set-toolbar-t">选项配置<span v-if="project" class="toolbar-proj"> · {{ project.name }}</span></div>
                            <div class="set-toolbar-s">优先级、严重度、任务类型、标签与环境，仅当前项目生效，供任务/缺陷表单选择</div>
                        </div>
                        <div class="toolbar-acts">
                            <b v-if="saved === 'options'" class="saved-tip">已保存</b>
                            <button
                                class="btn"
                                :class="{ ok: saved === 'options' }"
                                :disabled="!canEditProject || saving === 'options'"
                                :title="canEditProject ? '' : '仅系统管理员或项目负责人可修改'"
                                @click="saveOptions"
                            >
                                {{ btnLabel('options', '保存选项') }}
                            </button>
                        </div>
                    </div>
                    <div v-if="!canEditProject" class="field-error opt-err">当前账号无该项目配置权限（仅系统管理员或项目负责人可修改）</div>
                    <div v-if="optErr" class="field-error opt-err">选项名称不能为空</div>
                    <div v-if="saveErr.options" class="field-error opt-err">保存失败：{{ saveErr.options }}</div>
                    <div class="opt-grid">
                        <div v-for="g in OPT_GROUPS" :key="g.key" class="card opt-card">
                            <div class="card-head">
                                <span class="card-title">{{ g.label }}</span>
                                <span class="card-extra"><a class="link-btn" @click="addOpt(g.key)">+ 添加</a></span>
                            </div>
                            <div v-for="(it, i) in options[g.key]" :key="it.key" class="opt-row">
                                <span class="swatch" :style="{ background: it.color }" @click.stop="toggleOptColor(g.key, i)"></span>
                                <div v-if="optColorKey === g.key + '-' + i" class="palette" @click.stop>
                                    <span
                                        v-for="cc in COLORS"
                                        :key="cc"
                                        class="swatch"
                                        :class="{ on: it.color === cc }"
                                        :style="{ background: cc }"
                                        @click="
                                            it.color = cc;
                                            optColorKey = '';
                                        "
                                    ></span>
                                </div>
                                <input class="field-input opt-name" :class="{ invalid: optErr && !it.name.trim() }" v-model="it.name" placeholder="名称" />
                                <span class="opt-key">{{ it.key }}</span>
                                <button class="mini-btn del" title="删除" @click="delOpt(g.key, i)">
                                    <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                                </button>
                            </div>
                            <div v-if="!options[g.key].length" class="row-empty">暂无选项</div>
                        </div>
                    </div>

                    <div class="card">
                        <div class="card-head">
                            <span class="card-title">标签管理</span>
                        </div>
                        <div class="labels">
                            <span v-for="(l, i) in options.labels" :key="l" class="label-chip"> {{ l }}<i class="chip-x" @click="delLabel(i)">×</i> </span>
                        </div>
                        <input class="field-input label-input" v-model="labelInput" placeholder="输入标签后回车添加" @keydown.enter.prevent="addLabel" />
                    </div>

                    <div class="card">
                        <div class="card-head">
                            <span class="card-title">环境管理</span>
                            <span class="card-extra">缺陷表单的「环境」下拉取这里</span>
                        </div>
                        <div class="labels">
                            <span v-for="(e, i) in options.envs" :key="e" class="label-chip"> {{ e }}<i class="chip-x" @click="delEnv(i)">×</i> </span>
                        </div>
                        <div v-if="!options.envs.length" class="row-empty">暂无环境，缺陷表单将无法选择环境</div>
                        <input class="field-input label-input" v-model="envInput" placeholder="输入环境名后回车添加，如：预发" @keydown.enter.prevent="addEnv" />
                    </div>
                </template>

                <!-- ===== Tab 3 业务模块（项目级）===== -->
                <template v-else-if="tab === 'modules'">
                    <div class="set-toolbar">
                        <div>
                            <div class="set-toolbar-t">业务模块<span v-if="project" class="toolbar-proj"> · {{ project.name }}</span></div>
                            <div class="set-toolbar-s">需求 / 任务 / 缺陷共用的归类字典，用于模块维度的需求点数、缺陷密度、逃逸率与 MTTR 统计；与权限模块无关。</div>
                        </div>
                    </div>
                    <div v-if="!canEditProject" class="field-error opt-err">当前账号无该项目配置权限（仅系统管理员或项目负责人可修改）</div>
                    <div v-if="moduleErr" class="field-error opt-err">{{ moduleErr }}</div>

                    <div class="card">
                        <div class="card-head">
                            <span class="card-title">新增模块</span>
                            <span class="card-extra">名称在当前项目内唯一</span>
                        </div>
                        <div class="module-add">
                            <input class="field-input" v-model="moduleDraft.name" placeholder="模块名称，如 支付网关" />
                            <select class="field-select" v-model="moduleDraft.owner">
                                <option value="">模块负责人（可选）</option>
                                <option v-for="m in members.list" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                            </select>
                            <input class="field-input" v-model="moduleDraft.description" placeholder="一句话说明模块边界（可选）" />
                            <button class="btn" :disabled="!canEditProject || moduleSaving" @click="addModule">添加</button>
                        </div>
                    </div>

                    <div class="card">
                        <div class="card-head">
                            <span class="card-title">模块列表</span>
                            <span class="card-extra">删除前需先清空该模块下的需求 / 任务 / 缺陷</span>
                        </div>
                        <div v-for="m in modules" :key="m.id" class="module-row">
                            <template v-if="moduleEditingId === m.id">
                                <input class="field-input module-name" v-model="moduleEdit.name" placeholder="模块名称" />
                                <select class="field-select module-owner" v-model="moduleEdit.owner">
                                    <option value="">未指定负责人</option>
                                    <option v-for="mm in members.list" :key="mm.id" :value="mm.id">{{ mm.name }}</option>
                                </select>
                                <input class="field-input module-desc" v-model="moduleEdit.description" placeholder="模块说明" />
                                <button class="btn small" :disabled="moduleSaving" @click="saveModule(m.id)">保存</button>
                                <button class="ghost-btn small" @click="cancelModuleEdit">取消</button>
                            </template>
                            <template v-else>
                                <span class="module-name">{{ m.name }}</span>
                                <span class="module-owner">{{ m.ownerName || '未指定负责人' }}</span>
                                <span class="module-desc">{{ m.description || '—' }}</span>
                                <span class="module-count">需求 {{ m.requirementCount }} · 任务 {{ m.taskCount }} · 缺陷 {{ m.defectCount }}</span>
                                <button class="ghost-btn small" :disabled="!canEditProject" @click="startModuleEdit(m)">编辑</button>
                                <button class="ghost-btn small danger" :disabled="!canEditProject" @click="confirmModuleDel = m">删除</button>
                            </template>
                        </div>
                        <div v-if="!modules.length" class="row-empty">暂无业务模块，先在上方新增</div>
                    </div>
                </template>
            </div>
        </div>
    </template>

    <AppConfirm
        v-if="confirmModuleDel"
        title="删除业务模块"
        :text="`确定删除模块「${confirmModuleDel.name}」？仅当模块下没有需求 / 任务 / 缺陷时可删除。`"
        @confirm="removeModule"
        @close="confirmModuleDel = null"
    />
</template>

<style scoped>
/* ===== 布局：左 160px 竖排 tab + 右内容区，整页滚动 ===== */
.set-wrap {
    display: flex;
    gap: 24px;
    align-items: flex-start;
}
.set-tabs {
    width: 160px;
    flex: none;
    display: flex;
    flex-direction: column;
    gap: 2px;
}
.set-tab {
    text-align: left;
    padding: 8px 14px;
    border: none;
    background: none;
    border-radius: 8px;
    font-size:14px;
    color: var(--t2);
    cursor: pointer;
    font-family: inherit;
    transition: background 0.15s var(--ease), color 0.15s var(--ease);
}
.set-tab:hover {
    background: var(--track);
    color: var(--t1);
}
.set-tab.active {
    background: var(--primary-bg);
    color: var(--primary);
    font-weight:400;
    box-shadow: inset 0 0 0 1px rgba(51, 112, 255, 0.12);
}
.set-main {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 16px;
}

.row-empty {
    font-size:13px;
    color: var(--t3);
    padding: 8px 0;
}
.saved-tip {
    font-size: 12px;
    color: var(--green);
    font-weight:400;
}
.link-btn {
    font-size:13px;
    color: var(--primary);
    cursor: pointer;
}

/* 色块选择 */
.swatch {
    width: 18px;
    height: 18px;
    border-radius: 6px;
    cursor: pointer;
    flex: none;
    display: block;
}
.swatch.on {
    box-shadow:
        0 0 0 2px var(--card),
        0 0 0 3.5px var(--primary);
}
.palette {
    position: absolute;
    top: calc(100% + 6px);
    left: 0;
    z-index: 60;
    display: flex;
    gap: 7px;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 8px;
    box-shadow: var(--shadow-md);
}

/* ===== 工作流：顶部子标签 ===== */
.flow-tabs {
    display: flex;
    gap: 2px;
    padding: 4px;
    width: fit-content;
    max-width: 100%;
    overflow-x: auto;
    background: var(--track);
    border-radius: 10px;
}
.flow-tab {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    flex: none;
    height: 32px;
    padding: 0 14px;
    border: none;
    border-radius: 7px;
    background: transparent;
    color: var(--t2);
    font-family: inherit;
    font-size: 13px;
    cursor: pointer;
    white-space: nowrap;
    transition: background 0.15s var(--ease), color 0.15s var(--ease);
}
.flow-tab:hover {
    color: var(--t1);
}
.flow-tab.active {
    background: var(--card);
    color: var(--primary);
    font-weight: 700;
    box-shadow: var(--shadow-xs);
}
.flow-count {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 18px;
    height: 18px;
    padding: 0 5px;
    border-radius: 999px;
    background: var(--surface-3);
    color: var(--t3);
    font-size: 12px;
    font-weight: 400;
    font-variant-numeric: tabular-nums;
}
.flow-tab.active .flow-count {
    background: var(--primary-bg);
    color: var(--primary);
}

/* ===== 工作流列 ===== */
.wf-head {
    display: flex;
    align-items: flex-start;
    gap: 16px;
    margin-bottom: 14px;
}
.wf-head-txt {
    flex: 1;
    min-width: 0;
}
.wf-title {
    font-size: 15px;
    font-weight: 700;
}
.wf-desc {
    font-size: 12px;
    color: var(--t3);
    line-height: 1.6;
    margin-top: 4px;
}
.wf-head .toolbar-acts {
    flex: none;
    display: flex;
    align-items: center;
    gap: 10px;
}
.wf-err {
    margin-bottom: 10px;
}
.wf-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
}
.wf-row {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-radius: 10px;
}
.wf-order {
    width: 16px;
    flex: none;
    text-align: right;
    font-size: 12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.wf-terminal {
    flex: none;
    padding: 2px 7px;
    border-radius: 5px;
    background: var(--track);
    color: var(--t3);
    font-size: 12px;
}
.wf-name {
    flex: 1;
    min-width: 0;
}
.wf-key {
    width: 72px;
    flex: none;
    font-size: 12px;
    color: var(--t3);
    font-family: ui-monospace, Consolas, monospace;
}
.mini-btn {
    width: 26px;
    height: 26px;
    flex: none;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--card);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;
}
.mini-btn svg {
    width: 14px;
    height: 14px;
    stroke: var(--t2);
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.mini-btn.del svg {
    stroke: var(--red);
}
.mini-btn:disabled {
    opacity: 0.4;
    cursor: default;
}
.wf-foot {
    margin-top: 12px;
    padding-top: 12px;
    border-top: 1px dashed var(--border);
}

/* ===== 选项配置 ===== */
.set-toolbar {
    display: flex;
    align-items: flex-end;
    gap: 16px;
}
.set-toolbar-t {
    font-size: 16px;
    font-weight:700;
}
.toolbar-proj {
    font-size: 13px;
    font-weight:400;
    color: var(--t3);
}
.set-toolbar-s {
    font-size:13px;
    color: var(--t3);
    margin-top: 4px;
}
.set-toolbar .toolbar-acts {
    margin-left: auto;
    flex: none;
    display: flex;
    align-items: center;
    gap: 10px;
}
.set-toolbar button {
    flex: none;
}
/* 保存成功：按钮短暂变绿，配合「已保存」标签双重确认 */
.btn.ok {
    background: var(--green);
}
.opt-err {
    margin-bottom: 10px;
}
.opt-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 16px;
}
.opt-card {
    min-width: 0;
}
.opt-row {
    position: relative;
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
}
.opt-name {
    flex: 1;
    min-width: 0;
}
.opt-key {
    font-size:12px;
    color: var(--t3);
    font-family: ui-monospace, Consolas, monospace;
}

/* 标签 */
.labels {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 12px;
}
.label-chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size:13px;
    padding: 4px 10px;
    border-radius: 7px;
    background: var(--primary-bg);
    color: var(--primary);
}
.chip-x {
    font-style: normal;
    cursor: pointer;
    font-size: 14px;
    line-height: 1;
}
.label-input {
    width: 240px;
}

.ava-dot {
    border-radius: 50%;
}

/* ===== 业务模块 ===== */
.module-add {
    display: flex;
    gap: 10px;
    align-items: center;
    flex-wrap: wrap;
}
.module-add .field-input,
.module-add .field-select {
    flex: 1;
    min-width: 160px;
}
.module-row {
    display: flex;
    gap: 10px;
    align-items: center;
    padding: 10px 0;
    border-bottom: 1px solid var(--border);
    flex-wrap: wrap;
}
.module-row:last-of-type {
    border-bottom: none;
}
.module-row .module-name {
    width: 150px;
    flex: none;
    font-size:14px;
    color: var(--t1);
    font-weight:400;
}
.module-row .module-owner {
    width: 130px;
    flex: none;
    font-size:13px;
    color: var(--t2);
}
.module-row .module-desc {
    flex: 1;
    min-width: 140px;
    font-size:13px;
    color: var(--t3);
}
.module-row .module-count {
    flex: none;
    font-size: 12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.module-row .module-owner.field-select {
    width: 140px;
}
.module-row .module-desc.field-input {
    flex: 1;
    min-width: 140px;
}
.btn.small,
.ghost-btn.small {
    height: 28px;
    padding: 0 12px;
    font-size:13px;
}

@media (max-width: 880px) {
    .set-wrap {
        flex-direction: column;
    }
    .set-tabs {
        width: 100%;
        flex-direction: row;
        flex-wrap: wrap;
    }
    .opt-grid {
        grid-template-columns: 1fr;
    }
}
</style>
