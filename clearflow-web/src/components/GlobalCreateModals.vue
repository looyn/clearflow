<script setup>
import { reactive, ref, computed, watch } from 'vue';
import { useUiStore } from '../stores/ui';
import { useOptionsStore } from '../stores/options';
import { useIterationsStore } from '../stores/iterations';
import { useMembersStore } from '../stores/members';
import { useAuthStore } from '../stores/auth';
import { api } from '../api';
import AppModal from './AppModal.vue';
import AppEditor from './AppEditor.vue';
import AppUpload from './AppUpload.vue';
import AppChecklist from './AppChecklist.vue';
import AppPager from './AppPager.vue';

const ui = useUiStore();
const opts = useOptionsStore(); // 项目设置里的选项配置（优先级/严重度/任务类型/标签）
const iters = useIterationsStore(); // 迭代下拉候选（当前项目真实迭代 + 待排期）
const mstore = useMembersStore(); // 成员下拉共享数据
const submitting = ref(false);
const submitErr = ref(''); // 提交失败原因（403/400/网络错误），显示在弹窗底部；不让失败静默
const pendingCreate = ref(null); // 实体已创建但附件上传失败：重试时只补附件，避免重复建单
const errors = reactive({});
// 关联需求候选：服务端搜索 + 分页；已选项单独记 label（候选分页后不能只从当前页找）
const reqRows = ref([]);
const reqTotal = ref(0);
const reqSearch = ref('');
const reqPickerOpen = ref(false);
const reqPage = ref(1);
const reqPageSize = ref(10);
const pickedReq = ref(null);
let reqSeq = 0;
async function fetchReqOptions() {
    const seq = ++reqSeq;
    const r = await api.getRequirementOptions(reqSearch.value.trim(), reqPage.value, reqPageSize.value);
    if (seq !== reqSeq || !reqPickerOpen.value) return;
    reqRows.value = r.list || [];
    reqTotal.value = r.total || 0;
}
const reqEcho = computed(() => (pickedReq.value ? `${pickedReq.value.id} ${pickedReq.value.title}` : ''));
function openReqPicker() {
    reqSearch.value = '';
    reqPage.value = 1;
    reqRows.value = [];
    reqTotal.value = 0;
    reqPickerOpen.value = true; // 先开弹窗，候选异步回填
    fetchReqOptions().catch(() => {});
}
watch(reqSearch, () => {
    if (reqPage.value === 1) fetchReqOptions().catch(() => {});
    else reqPage.value = 1;
});
watch([reqPage, reqPageSize], () => fetchReqOptions().catch(() => {}));
function pickReq(r) {
    form.reqId = r ? r.id : null;
    pickedReq.value = r ? { id: r.id, title: r.title } : null;
    reqPickerOpen.value = false;
}
const members = computed(() => mstore.list); // 成员下拉数据源：共享成员 store
const auth = useAuthStore();
// 默认负责人/处理人：优先当前登录人，其次项目第一位成员（避免写死 id 在别的项目里不存在）
function defaultOwner() {
    const me = auth.user?.id;
    if (me !== undefined && me !== null && members.value.some((m) => String(m.id) === String(me))) return me;
    return members.value[0]?.id ?? '';
}

// ===== 前置任务（多选，仅创建任务）：候选走服务端搜索 + 分页 =====
const predPickerOpen = ref(false);
const predSel = ref([]); // 弹窗内待提交的选中集合（打开时同步表单值，确定后写回）
const predSearch = ref('');
const predPage = ref(1);
const predPageSize = ref(10);
const predRows = ref([]);
const predTotal = ref(0);
const predLabel = computed(() => ((form.predIds || []).length ? `已选 ${form.predIds.length} 项：${form.predIds.join('、')}` : '')); // 输入框回显以表单已提交值为准
let predSeq = 0;
async function fetchPredOptions() {
    const seq = ++predSeq;
    const r = await api.getTasks({ q: predSearch.value.trim() }, predPage.value, predPageSize.value);
    if (seq !== predSeq || !predPickerOpen.value) return;
    predRows.value = r.list || [];
    predTotal.value = r.total || 0;
}
async function openPredPicker() {
    predSearch.value = '';
    predPage.value = 1;
    predRows.value = [];
    predTotal.value = 0;
    predSel.value = [...(form.predIds || [])];
    predPickerOpen.value = true; // 先开弹窗，候选异步回填
    fetchPredOptions().catch(() => {});
}
watch(predSearch, () => {
    if (predPage.value === 1) fetchPredOptions().catch(() => {});
    else predPage.value = 1;
});
watch([predPage, predPageSize], () => fetchPredOptions().catch(() => {}));
function togglePredSel(id) {
    const i = predSel.value.indexOf(id);
    if (i >= 0) predSel.value.splice(i, 1);
    else predSel.value.push(id);
}
function confirmPredSel() {
    form.predIds = [...predSel.value];
    predPickerOpen.value = false;
}

// 新建缺陷的描述默认模板：给出固定结构，避免从空白开始，也统一同类缺陷的写法
const DEFECT_DESC_TEMPLATE =
    '<p><strong>现象：</strong></p>' +
    '<p><strong>期望结果：</strong></p>' +
    '<p><strong>实际结果：</strong></p>' +
    '<p><strong>复现步骤：</strong></p>' +
    '<p>1. </p>';

const blank = {
    task: { title: '', type: 'fe', prio: 'p1', assignee: 1, iter: '', due: '', estimate: 3, desc: '', reqId: null, moduleId: '', predIds: [], labels: [], attachments: [] },
    defect: {
        title: '',
        env: '测试',
        prio: 'p1',
        severity: '一般',
        owner: 1,
        iter: '',
        desc: DEFECT_DESC_TEMPLATE,
        reqId: null,
        moduleId: '',
        reporterId: '',
        reporter: '',
        introducedBy: '',
        defectType: '功能',
        rootCause: '',
        fixedIn: '',
        foundIn: '',
        deadline: '',
        attachments: []
    },
    release: { version: '', name: '', date: '', owner: 1, channel: '生产', risk: '低' },
    requirement: { title: '', type: '功能', prio: 'p1', source: '业务规划', owner: 5, iter: '待排期', estimate: 5, moduleId: '', desc: '', acceptance: [], attachments: [] },
    iteration: { name: '', startDate: '', endDate: '', owner: 1, total: 40, goal: '' },
    member: { name: '', role: '前端', email: '', password: '123456' }
};
const form = reactive(JSON.parse(JSON.stringify(blank)));
const key = computed(() => ui.activeCreate);

// 弹窗打开：先同步重置表单（模板随 key 立即渲染，不能等异步加载），再拉取迭代/成员下拉数据并校正默认值
watch(key, async () => {
    if (!key.value) return;
    resetForm();
    await Promise.all([iters.load(), mstore.load()]);
    alignDefaults();
}, { immediate: true });
function resetForm() {
    if (!key.value) return;
    Object.keys(form).forEach((k) => delete form[k]); // 清掉其他类型 blank 的残留字段，避免随请求体发给后端
    Object.assign(form, JSON.parse(JSON.stringify(blank[key.value])), ui.createPreset || {});
    pickedReq.value = null; // 关联需求回显随表单一起重置
    reqPickerOpen.value = false;
    predPickerOpen.value = false;
    Object.keys(errors).forEach((k) => delete errors[k]);
    submitErr.value = '';
    pendingCreate.value = null;
}
// 默认值对齐当前项目已有数据：设置里删掉的选项回退到第一个可用项，避免下拉空白
function alignDefaults() {
    if (!key.value) return;
    if ('prio' in form && !opts.priorities.some((p) => p.key === form.prio)) form.prio = opts.priorities[0]?.key || '';
    if ('severity' in form && !opts.severities.some((s) => s.key === form.severity)) form.severity = opts.severities[0]?.key || '';
    if ('env' in form && !opts.envs.includes(form.env)) form.env = opts.envs[0] || '';
    if (key.value === 'task' && !opts.taskTypes.some((t) => t.key === form.type)) form.type = opts.taskTypes[0]?.key || '';
    // 迭代默认取进行中的迭代；预设/默认值不在候选里时同样回退
    if ('iter' in form && !iters.options.some((o) => o.code === form.iter)) form.iter = iters.active?.code || '待排期';
    if ('assignee' in form && !members.value.some((m) => String(m.id) === String(form.assignee))) form.assignee = defaultOwner();
    if ('owner' in form && !members.value.some((m) => String(m.id) === String(form.owner))) form.owner = defaultOwner();
    // 业务模块：项目里已删除的模块回退为「未归类 / 随需求继承」
    if ('moduleId' in form && form.moduleId !== '' && !opts.modules.some((m) => String(m.id) === String(form.moduleId))) form.moduleId = '';
}

function validate() {
    Object.keys(errors).forEach((k) => delete errors[k]);
    if (key.value === 'task' || key.value === 'defect' || key.value === 'requirement') {
        if (!form.title.trim()) errors.title = '请输入标题';
    } else if (key.value === 'release') {
        if (!form.version.trim()) errors.version = '请输入版本号';
        if (!form.name.trim()) errors.name = '请输入计划名称';
        if (!form.date) errors.date = '请选择计划发版日期';
    } else if (key.value === 'iteration') {
        if (!form.name.trim()) errors.name = '请输入迭代名称';
        if (!form.startDate) errors.startDate = '请选择开始日期';
        if (!form.endDate) errors.endDate = '请选择结束日期';
    } else if (key.value === 'member') {
        if (!form.name.trim()) errors.name = '请输入姓名';
        if (!form.email.trim() || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(form.email)) errors.email = '请输入有效邮箱';
        if (!form.password.trim()) errors.password = '请输入初始密码';
        else if (form.password.trim().length < 6) errors.password = '密码至少 6 位';
    }
    return Object.keys(errors).length === 0;
}

// 新建流程附件：创建接口不接收附件，先本地暂存（AppUpload 带 file），实体创建成功后逐个真实上传并登记
// 已成功的条目打标记，失败重试时不会重复上传
async function saveAtts(kind, r) {
    if (!r || !r.id) return;
    for (const it of form.attachments || []) {
        if (it.saved) continue;
        await api.saveAttachment(kind, r.id, it);
        it.saved = true;
    }
}

async function submit() {
    if (!validate()) return;
    submitting.value = true;
    submitErr.value = '';
    try {
        const core = { ...form, attachments: [] }; // create 请求体不带暂存附件（后端不解析）
        const remember = (kind, r) => {
            if (r?.id) pendingCreate.value = { key: key.value, kind, id: r.id };
        };
        const map = {
            task: async () => {
                const r = await api.createTask({ ...core, due: form.due ? form.due.replace('T', ' ') + ':00' : form.due });
                remember('task', r);
                await saveAtts('task', r);
            },
            defect: async () => {
                // 原样提交模板等于没填，按空描述处理，避免详情里只剩几个空标题
                const plain = (h) => (h || '').replace(/<[^>]*>/g, '').replace(/&nbsp;|\s/g, '');
                const desc = plain(form.desc) === plain(DEFECT_DESC_TEMPLATE) ? '' : form.desc;
                const r = await api.createDefect({ ...core, desc, deadline: form.deadline ? form.deadline.replace('T', ' ') + ':00' : form.deadline });
                remember('defect', r);
                await saveAtts('defect', r);
            },
            release: () => api.createRelease(form),
            requirement: async () => {
                const r = await api.createRequirement(core);
                remember('req', r);
                await saveAtts('req', r);
            },
            iteration: () =>
                api.createIteration({
                    ...form,
                    range: `${form.startDate} ~ ${form.endDate}`
                }),
            member: async () => {
                await api.inviteMember({ name: form.name, role: form.role, email: form.email, password: form.password });
                await mstore.load(true); // 成员池变了，强刷共享成员数据
            }
        };
        if (pendingCreate.value && pendingCreate.value.key === key.value) {
            await saveAtts(pendingCreate.value.kind, { id: pendingCreate.value.id });
        } else {
            await map[key.value]();
        }
        pendingCreate.value = null;
        ui.closeCreate();
        ui.notifyRefresh();
    } catch (e) {
        // 失败不关弹窗：后端 403/400 的 message 直接展示（如「无权限执行该操作：「任务看板」需要「编辑」权限」）
        submitErr.value = e?.message || '提交失败，请稍后重试';
    } finally {
        submitting.value = false;
    }
}

const titles = {
    task: '新建任务',
    defect: '提缺陷',
    release: '新建发版计划',
    requirement: '新建需求',
    iteration: '新建迭代',
    member: '邀请成员'
};
const DEFECT_TYPES = ['功能', '性能', '兼容性', '安全', '数据', '体验'];
const ROOT_CAUSES = ['', '需求遗漏', '设计缺陷', '编码错误', '测试遗漏', '环境问题'];
const clearErr = (k) => {
    delete errors[k];
};

// ===== 标签：候选取自「项目设置 → 选项配置 → 标签管理」，点击快捷加入 =====
const labelSuggest = computed(() => opts.labels.filter((l) => !(form.labels || []).includes(l)).slice(0, 8));
function addLabel(l) {
    form.labels = [...(form.labels || []), l];
}
</script>

<template>
    <!-- 新建任务 -->
    <AppModal v-if="key === 'task'" :title="titles.task" @close="ui.closeCreate()">
        <div class="field">
            <label class="field-label">任务标题<span class="req">*</span></label>
            <input class="field-input" :class="{ invalid: errors.title }" v-model="form.title" placeholder="一句话描述任务目标" @input="clearErr('title')" />
            <div v-if="errors.title" class="field-error">{{ errors.title }}</div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">类型</label>
                <select class="field-select" v-model="form.type">
                    <option v-for="t in opts.taskTypes" :key="t.key" :value="t.key">{{ t.name }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">优先级</label>
                <select class="field-select" v-model="form.prio">
                    <option v-for="p in opts.priorities" :key="p.key" :value="p.key">{{ p.name }}</option>
                </select>
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">负责人</label>
                <select class="field-select" v-model="form.assignee">
                    <option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">所属迭代</label>
                <select class="field-select" v-model="form.iter">
                    <option v-for="o in iters.options" :key="o.code" :value="o.code">{{ o.label }}</option>
                </select>
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">业务模块</label>
                <select class="field-select" v-model="form.moduleId">
                    <option value="">未归类（随关联需求）</option>
                    <option v-for="m in opts.modules" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">截止日期</label>
                <input type="datetime-local" class="field-input" v-model="form.due" />
            </div>
        </div>
        <div class="field">
            <label class="field-label">预估工时（h）</label>
            <input class="field-input" type="number" min="0" v-model.number="form.estimate" />
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">关联需求</label>
                <div class="picker-input-wrap">
                    <input
                        class="field-input picker-input"
                        readonly
                        :value="reqEcho"
                        placeholder="搜索选择关联需求…"
                        @click="openReqPicker()"
                    />
                    <button class="picker-search-btn" @click="openReqPicker()" title="搜索关联需求">
                        <svg viewBox="0 0 24 24">
                            <circle cx="11" cy="11" r="8" />
                            <path d="m21 21-4.35-4.35" />
                        </svg>
                    </button>
                </div>
            </div>
            <div class="field">
                <label class="field-label">标签</label>
                <input class="field-input" :value="form.labels.join(' ')" @input="form.labels = $event.target.value.split(/[\s,，]+/).filter(Boolean)" placeholder="空格分隔，或点下方标签快捷添加" />
                <div class="label-suggest">
                    <a v-for="l in labelSuggest" :key="l" class="suggest-chip" @click="addLabel(l)">+ {{ l }}</a>
                    <span v-if="!labelSuggest.length" class="suggest-none">项目设置里的标签已全部添加</span>
                </div>
            </div>
        </div>
        <div class="field">
            <label class="field-label">前置任务</label>
            <div class="picker-input-wrap">
                <input class="field-input picker-input" readonly :value="predLabel" placeholder="搜索选择前置任务（可多选）…" @click="openPredPicker()" />
                <button class="picker-search-btn" @click="openPredPicker()" title="搜索前置任务">
                    <svg viewBox="0 0 24 24">
                        <circle cx="11" cy="11" r="8" />
                        <path d="m21 21-4.35-4.35" />
                    </svg>
                </button>
            </div>
        </div>
        <div class="field">
            <label class="field-label">描述</label>
            <AppEditor v-model="form.desc" :min-height="200" placeholder="补充上下文、验收标准等"></AppEditor>
        </div>
        <div class="field">
            <label class="field-label">附件</label>
            <AppUpload v-model="form.attachments"></AppUpload>
        </div>
        <template #footer>
            <span v-if="submitErr" class="submit-err">{{ submitErr }}</span>
            <button class="ghost-btn" @click="ui.closeCreate()">取消</button>
            <button class="btn" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '创建任务' }}</button>
        </template>
    </AppModal>

    <!-- 提缺陷 -->
    <AppModal v-else-if="key === 'defect'" :title="titles.defect" @close="ui.closeCreate()">
        <div class="field">
            <label class="field-label">缺陷标题<span class="req">*</span></label>
            <input class="field-input" :class="{ invalid: errors.title }" v-model="form.title" placeholder="描述缺陷现象" @input="clearErr('title')" />
            <div v-if="errors.title" class="field-error">{{ errors.title }}</div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">环境</label>
                <select class="field-select" v-model="form.env">
                    <option v-for="e in opts.envs" :key="e" :value="e">{{ e }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">优先级</label>
                <select class="field-select" v-model="form.prio">
                    <option v-for="p in opts.priorities" :key="p.key" :value="p.key">{{ p.name }}</option>
                </select>
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">严重度</label>
                <select class="field-select" v-model="form.severity">
                    <option v-for="s in opts.severities" :key="s.key" :value="s.key">{{ s.name }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">缺陷类型</label>
                <select class="field-select" v-model="form.defectType">
                    <option v-for="t in DEFECT_TYPES" :key="t" :value="t">{{ t }}</option>
                </select>
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">业务模块</label>
                <select class="field-select" v-model="form.moduleId">
                    <option value="">未归类（随关联需求）</option>
                    <option v-for="m in opts.modules" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">发现版本</label>
                <input class="field-input" v-model="form.foundIn" placeholder="如 v2.24.0-rc.1" />
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">处理人</label>
                <select class="field-select" v-model="form.owner">
                    <option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">修复期限</label>
                <input type="datetime-local" class="field-input" v-model="form.deadline" />
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">报告人</label>
                <select class="field-select" v-model="form.reporterId">
                    <option value="">默认当前登录人 / 外部反馈人</option>
                    <option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">外部报告人</label>
                <input class="field-input" v-model="form.reporter" :disabled="!!form.reporterId" placeholder="如 客服-王芳（无成员账号时填写）" />
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">根因（修复后复盘）</label>
                <select class="field-select" v-model="form.rootCause">
                    <option v-for="c in ROOT_CAUSES" :key="c" :value="c">{{ c || '暂未分析' }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">引入人（仅复盘，可选）</label>
                <select class="field-select" v-model="form.introducedBy">
                    <option value="">未定位</option>
                    <option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                </select>
            </div>
        </div>
        <div class="field">
            <label class="field-label">修复版本</label>
            <input class="field-input" v-model="form.fixedIn" placeholder="如 v2.24.0" />
        </div>
        <div class="field">
            <label class="field-label">关联需求</label>
            <div class="picker-input-wrap">
                <input
                    class="field-input picker-input"
                    readonly
                    :value="reqEcho"
                    placeholder="搜索选择关联需求…"
                    @click="openReqPicker()"
                />
                <button class="picker-search-btn" @click="openReqPicker()" title="搜索关联需求">
                    <svg viewBox="0 0 24 24">
                        <circle cx="11" cy="11" r="8" />
                        <path d="m21 21-4.35-4.35" />
                    </svg>
                </button>
            </div>
        </div>
        <div class="field">
            <label class="field-label">描述</label>
            <AppEditor v-model="form.desc" :min-height="200" placeholder="现象 / 期望结果 / 实际结果；复现步骤请分条编号（含运行环境）"></AppEditor>
        </div>
        <div class="field">
            <label class="field-label">附件</label>
            <AppUpload v-model="form.attachments"></AppUpload>
        </div>
        <template #footer>
            <span v-if="submitErr" class="submit-err">{{ submitErr }}</span>
            <button class="ghost-btn" @click="ui.closeCreate()">取消</button>
            <button class="btn" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '提交缺陷' }}</button>
        </template>
    </AppModal>

    <!-- 新建发版计划 -->
    <AppModal v-else-if="key === 'release'" :title="titles.release" @close="ui.closeCreate()">
        <div class="field-row">
            <div class="field">
                <label class="field-label">版本号<span class="req">*</span></label>
                <input class="field-input" :class="{ invalid: errors.version }" v-model="form.version" placeholder="如 v2.25.0" @input="clearErr('version')" />
                <div v-if="errors.version" class="field-error">{{ errors.version }}</div>
            </div>
            <div class="field">
                <label class="field-label">计划名称<span class="req">*</span></label>
                <input class="field-input" :class="{ invalid: errors.name }" v-model="form.name" placeholder="如 对账与商户进件体验优化" @input="clearErr('name')" />
                <div v-if="errors.name" class="field-error">{{ errors.name }}</div>
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">计划发版日期<span class="req">*</span></label>
                <input type="date" class="field-input" :class="{ invalid: errors.date }" v-model="form.date" @input="clearErr('date')" />
                <div v-if="errors.date" class="field-error">{{ errors.date }}</div>
            </div>
            <div class="field">
                <label class="field-label">发版负责人</label>
                <select class="field-select" v-model="form.owner">
                    <option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                </select>
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">发布渠道</label>
                <select class="field-select" v-model="form.channel">
                    <option>生产</option>
                    <option>灰度</option>
                    <option>内测</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">风险等级</label>
                <select class="field-select" v-model="form.risk">
                    <option>低</option>
                    <option>中</option>
                    <option>高</option>
                </select>
            </div>
        </div>
        <template #footer>
            <span v-if="submitErr" class="submit-err">{{ submitErr }}</span>
            <button class="ghost-btn" @click="ui.closeCreate()">取消</button>
            <button class="btn" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '创建计划' }}</button>
        </template>
    </AppModal>

    <!-- 新建需求 -->
    <AppModal v-else-if="key === 'requirement'" :title="titles.requirement" @close="ui.closeCreate()">
        <div class="field">
            <label class="field-label">需求标题<span class="req">*</span></label>
            <input class="field-input" :class="{ invalid: errors.title }" v-model="form.title" placeholder="一句话描述需求价值" @input="clearErr('title')" />
            <div v-if="errors.title" class="field-error">{{ errors.title }}</div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">类型</label>
                <select class="field-select" v-model="form.type">
                    <option>功能</option>
                    <option>优化</option>
                    <option>技术</option>
                    <option>体验</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">优先级</label>
                <select class="field-select" v-model="form.prio">
                    <option v-for="p in opts.priorities" :key="p.key" :value="p.key">{{ p.name }}</option>
                </select>
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">负责人</label>
                <select class="field-select" v-model="form.owner">
                    <option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">业务模块</label>
                <select class="field-select" v-model="form.moduleId">
                    <option value="">未归类</option>
                    <option v-for="m in opts.modules" :key="m.id" :value="m.id">{{ m.name }}</option>
                </select>
            </div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">目标迭代</label>
                <select class="field-select" v-model="form.iter">
                    <option v-for="o in iters.options" :key="o.code" :value="o.code">{{ o.label }}</option>
                </select>
            </div>
            <div class="field">
                <label class="field-label">来源</label>
                <select class="field-select" v-model="form.source">
                    <option>业务规划</option>
                    <option>客户反馈</option>
                    <option>运营反馈</option>
                    <option>合规要求</option>
                    <option>技术规划</option>
                    <option>风控组</option>
                </select>
            </div>
        </div>
        <div class="field">
            <label class="field-label">预估规模（点）</label>
            <input class="field-input" type="number" min="0" v-model.number="form.estimate" />
        </div>
        <div class="field">
            <label class="field-label">描述</label>
            <AppEditor v-model="form.desc" :min-height="200" placeholder="需求背景、业务价值与目标"></AppEditor>
        </div>
        <div class="field">
            <AppChecklist v-model="form.acceptance" label="验收标准" placeholder="输入一条验收标准，回车添加" />
        </div>
        <div class="field">
            <label class="field-label">附件</label>
            <AppUpload v-model="form.attachments"></AppUpload>
        </div>
        <template #footer>
            <span v-if="submitErr" class="submit-err">{{ submitErr }}</span>
            <button class="ghost-btn" @click="ui.closeCreate()">取消</button>
            <button class="btn" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '创建需求' }}</button>
        </template>
    </AppModal>

    <!-- 新建迭代 -->
    <AppModal v-else-if="key === 'iteration'" :title="titles.iteration" @close="ui.closeCreate()">
        <div class="field">
            <label class="field-label">迭代名称<span class="req">*</span></label>
            <input class="field-input" :class="{ invalid: errors.name }" v-model="form.name" placeholder="如 对账中心升级" @input="clearErr('name')" />
            <div v-if="errors.name" class="field-error">{{ errors.name }}</div>
        </div>
        <div class="field-row">
            <div class="field">
                <label class="field-label">开始日期<span class="req">*</span></label>
                <input type="date" class="field-input" :class="{ invalid: errors.startDate }" v-model="form.startDate" @input="clearErr('startDate')" />
                <div v-if="errors.startDate" class="field-error">{{ errors.startDate }}</div>
            </div>
            <div class="field">
                <label class="field-label">结束日期<span class="req">*</span></label>
                <input type="date" class="field-input" :class="{ invalid: errors.endDate }" v-model="form.endDate" @input="clearErr('endDate')" />
                <div v-if="errors.endDate" class="field-error">{{ errors.endDate }}</div>
            </div>
            <div class="field">
                <label class="field-label">负责人</label>
                <select class="field-select" v-model="form.owner">
                    <option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }} · {{ m.role }}</option>
                </select>
            </div>
        </div>
        <div class="field">
            <label class="field-label">迭代目标</label>
            <textarea class="field-textarea" v-model="form.goal" placeholder="本次迭代要达成的业务目标"></textarea>
        </div>
        <div class="field">
            <label class="field-label">计划任务数</label>
            <input class="field-input" type="number" min="0" v-model.number="form.total" />
        </div>
        <template #footer>
            <span v-if="submitErr" class="submit-err">{{ submitErr }}</span>
            <button class="ghost-btn" @click="ui.closeCreate()">取消</button>
            <button class="btn" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '创建迭代' }}</button>
        </template>
    </AppModal>

    <!-- 邀请成员 -->
    <AppModal v-else-if="key === 'member'" :title="titles.member" @close="ui.closeCreate()">
        <div class="field">
            <label class="field-label">姓名<span class="req">*</span></label>
            <input class="field-input" :class="{ invalid: errors.name }" v-model="form.name" placeholder="成员姓名" @input="clearErr('name')" />
            <div v-if="errors.name" class="field-error">{{ errors.name }}</div>
        </div>
        <div class="field">
            <label class="field-label">角色</label>
            <select class="field-select" v-model="form.role">
                <option>前端</option>
                <option>后端</option>
                <option>测试</option>
                <option>产品</option>
                <option>运维</option>
            </select>
        </div>
        <div class="field">
            <label class="field-label">邮箱<span class="req">*</span></label>
            <input class="field-input" :class="{ invalid: errors.email }" v-model="form.email" placeholder="name@clearflow.dev" @input="clearErr('email')" />
            <div v-if="errors.email" class="field-error">{{ errors.email }}</div>
        </div>
        <div class="field">
            <label class="field-label">初始密码<span class="req">*</span></label>
            <input class="field-input" :class="{ invalid: errors.password }" v-model="form.password" type="password" placeholder="至少 6 位，成员登录后可自行修改" @input="clearErr('password')" />
            <div v-if="errors.password" class="field-error">{{ errors.password }}</div>
        </div>
        <template #footer>
            <span v-if="submitErr" class="submit-err">{{ submitErr }}</span>
            <button class="ghost-btn" @click="ui.closeCreate()">取消</button>
            <button class="btn" :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '发送邀请' }}</button>
        </template>
    </AppModal>

    <!-- 关联需求搜索弹窗 -->
    <Teleport to="body">
        <div v-if="reqPickerOpen" class="picker-overlay">
            <div class="picker-dialog">
                <div class="picker-head">
                    <span>选择关联需求</span>
                    <button class="picker-close" title="关闭" @click="reqPickerOpen = false">
                        <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                    </button>
                </div>
                <input class="field-input picker-search" v-model="reqSearch" placeholder="搜索需求编号或标题…" autofocus />
                <div class="picker-list">
                    <div class="picker-item" @click="pickReq(null)"><em>不关联</em></div>
                    <div v-for="r in reqRows" :key="r.id" class="picker-item" @click="pickReq(r)">
                        <b>{{ r.id }}</b> {{ r.title }}
                    </div>
                    <div v-if="!reqRows.length" class="picker-empty">无匹配需求</div>
                </div>
                <AppPager :total="reqTotal" :page="reqPage" :page-size="reqPageSize" @update:page="reqPage = $event" @update:pageSize="reqPageSize = $event" />
            </div>
        </div>
    </Teleport>

    <!-- 前置任务搜索弹窗（多选，确定后写回表单） -->
    <Teleport to="body">
        <div v-if="predPickerOpen" class="picker-overlay">
            <div class="picker-dialog">
                <div class="picker-head">
                    <span>选择前置任务<span class="head-sub">可多选</span></span>
                    <button class="picker-close" title="关闭" @click="predPickerOpen = false">
                        <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                    </button>
                </div>
                <input class="field-input picker-search" v-model="predSearch" placeholder="搜索任务编号或标题…" autofocus />
                <div class="picker-list">
                    <div v-for="t in predRows" :key="t.id" class="picker-item multi" :class="{ on: predSel.includes(t.id) }" @click="togglePredSel(t.id)">
                        <b>{{ t.id }}</b> {{ t.title }}
                        <svg class="tick" viewBox="0 0 24 24"><path d="m5 13 4 4L19 7" /></svg>
                    </div>
                    <div v-if="!predRows.length" class="picker-empty">无匹配任务</div>
                </div>
                <AppPager :total="predTotal" :page="predPage" :page-size="predPageSize" @update:page="predPage = $event" @update:pageSize="predPageSize = $event" />
                <div class="picker-act">
                    <span class="act-n">已选 {{ predSel.length }} 项</span>
                    <button class="ghost-btn" @click="predPickerOpen = false">取消</button>
                    <button class="btn" @click="confirmPredSel">确定</button>
                </div>
            </div>
        </div>
    </Teleport>
</template>

<style scoped>
/* 提交失败提示（footer 内左对齐，按钮靠右） */
.submit-err {
    margin-right: auto;
    align-self: center;
    font-size:13px;
    color: var(--red);
}

/* 输入框+搜索按钮组合 */
.picker-input-wrap {
    display: flex;
    align-items: stretch;
    gap: 0;
    width: 100%;
}
.picker-input {
    flex: 1;
    min-width: 0;
    border-top-right-radius: 0 !important;
    border-bottom-right-radius: 0 !important;
    cursor: pointer;
    height: 30px;
}
.picker-search-btn {
    flex: none;
    width: 32px;
    border: 1px solid var(--border);
    border-left: none;
    border-radius: 0 7px 7px 0;
    background: var(--card);
    color: var(--t3);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;
}
.picker-search-btn svg {
    width: 14px;
    height: 14px;
    stroke: currentColor;
    stroke-width: 2;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.picker-search-btn:hover {
    color: var(--primary);
}

/* 标签快捷添加（候选来自项目设置的标签库） */
.label-suggest {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 6px;
}
.suggest-chip {
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 6px;
    background: var(--primary-bg);
    color: var(--primary);
    cursor: pointer;
}
.suggest-none {
    font-size: 12px;
    color: var(--t3);
}

/* 搜索弹窗 */
.picker-overlay {
    position: fixed;
    z-index: 11000;
    inset: 0;
    background: var(--overlay);
    display: flex;
    align-items: center;
    justify-content: center;
}
.picker-dialog {
    width: 540px;
    max-height: 75vh;
    overflow: hidden;
    background: var(--card);
    border-radius: 12px;
    box-shadow: var(--shadow-lg);
    display: flex;
    flex-direction: column;
}
.picker-head {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight:700;
    padding: 16px 20px 10px;
}
.picker-close {
    margin-left: auto;
    width: 26px;
    height: 26px;
    flex: none;
    padding: 0;
    border: none;
    background: none;
    border-radius: 6px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--t3);
}
.picker-close svg {
    width: 14px;
    height: 14px;
    stroke: currentColor;
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
}
.picker-close:hover {
    background: var(--track);
    color: var(--t1);
}
.picker-search {
    width: calc(100% - 40px);
    margin: 0 20px 10px;
}
.picker-list {
    overflow-y: auto;
    padding: 0 0 6px;
    flex: 1;
    min-height: 220px;
}
.picker-item {
    padding: 10px 20px;
    font-size: 13px;
    color: var(--t2);
    cursor: pointer;
}
.picker-item b {
    color: var(--t1);
    margin-right: 6px;
    font-weight:700;
}
.picker-item em {
    color: var(--t3);
    font-style: normal;
}
.picker-item:hover {
    background: var(--track);
}
.picker-empty {
    padding: 18px;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}
/* 统一分页条在弹窗内补足左右留白（与标题/列表 20px 内边距对齐） */
.picker-dialog .pager {
    padding: 12px 20px 12px;
}
/* 多选选中态与底部操作条（前置任务弹窗） */
.head-sub {
    margin-left: 8px;
    font-size: 12px;
    font-weight: 400;
    color: var(--t3);
}
.picker-item.multi {
    display: flex;
    align-items: center;
} /* 多选项用 flex 让勾选标记靠右，不影响关联需求列表的文本换行 */
.picker-item.on {
    background: var(--primary-bg);
}
.picker-item.on b {
    color: var(--primary);
}
.tick {
    display: none;
    width: 16px;
    height: 16px;
    margin-left: auto;
    flex: none;
    stroke: var(--primary);
    stroke-width: 2.5;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.picker-item.on .tick {
    display: block;
}
.picker-act {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 20px 14px;
    border-top: 1px solid var(--border);
}
.picker-act .ghost-btn,
.picker-act .btn {
    height: 30px;
}
.act-n {
    margin-right: auto;
    font-size:13px;
    color: var(--t2);
}
</style>
