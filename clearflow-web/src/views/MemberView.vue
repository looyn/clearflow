<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue';
import { api } from '../api';
import { useUiStore } from '../stores/ui';
import { useAuthStore } from '../stores/auth';
import { useMembersStore } from '../stores/members';
import { usePermStore } from '../stores/perm';
import { useProjectsStore } from '../stores/projects';
import LoadingMask from '../components/LoadingMask.vue';
import AppModal from '../components/AppModal.vue';
import AppConfirm from '../components/AppConfirm.vue';
import AppPager from '../components/AppPager.vue';
import PageCreateButton from '../components/PageCreateButton.vue';

const ui = useUiStore();
const auth = useAuthStore(); // 当前登录人（「我」标记 / 防自删）
const perm = usePermStore();
const loading = ref(true);
const mstore = useMembersStore(); // 精简成员映射（共享；写操作后强刷）
const projects = useProjectsStore(); // 负载聚合需要项目上下文
// 成员池分页（邮箱/角色/加入时间等仅管理员可读；本页菜单仅系统管理员可见）
const rows = ref([]);
const total = ref(0);
const pool = ref(null); // 成员池概览：总人数 + 角色候选（SQL 聚合，不依赖全量列表）
const load = ref([]);

// 筛选/分页连打时只认最后一次响应
let fetchSeq = 0;
async function fetchData() {
    // 本页仅系统管理员（与菜单 vis:'admin' 同源）；画像未就绪时不发请求
    if (!perm.isAdmin) {
        rows.value = [];
        pool.value = null;
        load.value = [];
        return;
    }
    const seq = ++fetchSeq;
    const [r, s] = await Promise.all([
        api.getMembers(filterParams.value, page.value, pageSize.value),
        api.getMemberStats()
    ]);
    if (seq !== fetchSeq) return;
    rows.value = r.list || [];
    total.value = r.total || 0;
    pool.value = s;
    if (!rows.value.length && page.value > 1) page.value = 1; // 删除后页码越界回收
    mstore.load(true); // 成员可能刚被增删改，强刷（名称/头像映射）
    const pid = projects.currentId;
    if (!pid) {
        load.value = []; // 没有任何可见项目：负载列留空
        return;
    }
    const ov = await api.getOverview();
    if (seq !== fetchSeq) return;
    load.value = Object.fromEntries(ov.teamLoad.map((x) => [x.id, x.pct]));
}
onMounted(async () => {
    await fetchData();
    loading.value = false;
});
// 刷新、切换项目、或权限画像加载完成后（补）拉取
watch(() => ui.refreshTick, fetchData);
watch([() => perm.isAdmin, () => projects.currentId], fetchData);

// ===== 筛选（关键字 + 角色，均下推服务端）=====
const q = ref('');
const fRole = ref('');
const filterParams = computed(() => ({ keyword: q.value.trim(), role: fRole.value }));
const roleOpts = computed(() => pool.value?.roles || []);

// ===== 分页（服务端分页，paged 即当前页）=====
const page = ref(1);
const pageSize = ref(10);
const paged = computed(() => rows.value);
watch([q, fRole], () => {
    // 回第一页由 page 监听取数，已经是第一页时直接取数，避免重复请求
    if (page.value === 1) fetchData().catch(() => {});
    else page.value = 1;
});
watch([page, pageSize], () => fetchData().catch(() => {}));

// ===== 编辑成员权限 =====
const editingId = ref(null);
const editing = ref(false);
const editForm = reactive({ name: '', role: '' });
function startEdit(m) {
    Object.assign(editForm, { name: m.name, role: m.role });
    editingId.value = m.id;
    editing.value = true;
    resetPwdForm();
}
async function saveEdit() {
    await api.updateMember(editingId.value, { role: editForm.role });
    editing.value = false;
    await fetchData();
    ui.notifyRefresh();
}

// ===== 修改成员密码（编辑弹窗内独立提交，与权限保存互不影响）=====
const pwdForm = reactive({ pwd: '', pwd2: '' });
const pwdErr = ref('');
const pwdOk = ref(false);
let pwdOkTimer = null;
function resetPwdForm() {
    pwdForm.pwd = '';
    pwdForm.pwd2 = '';
    pwdErr.value = '';
    pwdOk.value = false;
}
async function changePwd() {
    pwdErr.value = '';
    pwdOk.value = false;
    if (!pwdForm.pwd) {
        pwdErr.value = '请输入新密码';
        return;
    }
    if (pwdForm.pwd.trim().length < 6) {
        pwdErr.value = '密码至少 6 位';
        return;
    }
    if (pwdForm.pwd !== pwdForm.pwd2) {
        pwdErr.value = '两次输入的密码不一致';
        return;
    }
    await api.resetMemberPassword(editingId.value, pwdForm.pwd);
    pwdForm.pwd = '';
    pwdForm.pwd2 = '';
    pwdOk.value = true; // 即时反馈
    clearTimeout(pwdOkTimer);
    pwdOkTimer = setTimeout(() => {
        pwdOk.value = false;
    }, 1600);
}

// ===== 移除成员 =====
const removeTarget = ref(null);
async function doRemove() {
    const id = removeTarget.value.id;
    removeTarget.value = null;
    await api.removeMember(id);
    await fetchData();
    ui.notifyRefresh();
}
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else>
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>成员管理</h1>
                <div class="page-meta">管理系统成员池、角色与密码</div>
            </div>
            <div class="page-right">
                <span class="countdown">{{ pool?.total ?? 0 }} 名成员</span>
                <PageCreateButton type="member" />
            </div>
        </div>

        <!-- 筛选栏 -->
        <div class="filter-bar rise d2">
            <input class="f-search" v-model="q" placeholder="搜索姓名 / 邮箱 / 角色…" />
            <div class="filter-item">
                <span class="filter-label">角色</span>
                <select class="filter-select" v-model="fRole">
                    <option value="">全部</option>
                    <option v-for="r in roleOpts" :key="r" :value="r">{{ r }}</option>
                </select>
            </div>
            <span class="f-count">{{ total }} 名成员</span>
        </div>

            <!-- 成员表格 -->
            <div class="card table-card rise d3">
                <div class="table-head">
                    <div class="col-name">成员</div>
                    <div class="col-email">邮箱</div>
                    <div class="mcol-role">角色</div>
                    <div class="col-join">加入时间</div>
                    <div class="col-load">本迭代负载</div>
                    <div class="col-ops"></div>
                </div>
                <div v-for="m in paged" :key="m.id" class="table-row">
                    <div class="col-name">
                        <span class="mini-ava ava" :class="m.color">{{ m.short }}</span>
                        <span class="m-name">{{ m.name }}</span>
                        <span v-if="m.id === auth.user?.id" class="me-tag">我</span>
                    </div>
                    <div class="col-email">{{ m.email }}</div>
                    <div class="mcol-role">{{ m.role }}</div>
                    <div class="col-join">{{ m.joined }}</div>
                    <div class="col-load">
                        <span class="load-bar"><i :class="{ over: (load[m.id] ?? 0) > 100 }" :style="{ width: Math.min(load[m.id] ?? 0, 100) + '%' }"></i></span>
                        <span class="load-n" :class="{ over: (load[m.id] ?? 0) > 100 }">{{ load[m.id] ?? '—' }}%</span>
                    </div>
                    <div class="col-ops">
                        <button v-if="perm.isAdmin" class="op-btn" @click="startEdit(m)">编辑</button>
                        <button v-if="perm.isAdmin && m.id !== auth.user?.id" class="op-btn danger" @click="removeTarget = m">移除</button>
                    </div>
                </div>
                <div v-if="!rows.length" class="empty">没有符合条件的成员</div>
                <!-- 分页（全站统一组件，total 取后端筛选后的总条数） -->
                <AppPager v-else :total="total" :page="page" :page-size="pageSize" @update:page="page = $event" @update:pageSize="pageSize = $event" />
            </div>

        <!-- 编辑成员弹窗 -->
        <AppModal v-if="editing" :title="'编辑成员 · ' + editForm.name" width="560" @close="editing = false">
            <div class="field">
                <label class="field-label">角色</label>
                <select class="field-select" v-model="editForm.role">
                    <option>前端负责人</option>
                    <option>前端</option>
                    <option>后端</option>
                    <option>测试</option>
                    <option>产品</option>
                    <option>运维</option>
                </select>
            </div>

            <!-- 修改密码（独立提交） -->
            <div class="pwd-section">
                <div class="pwd-title">修改密码</div>
                <div class="field-row">
                    <div class="field">
                        <label class="field-label">新密码</label>
                        <input class="field-input" :class="{ invalid: pwdErr }" v-model="pwdForm.pwd" type="password" placeholder="至少 6 位" @input="pwdErr = ''" />
                    </div>
                    <div class="field">
                        <label class="field-label">确认新密码</label>
                        <input class="field-input" :class="{ invalid: pwdErr }" v-model="pwdForm.pwd2" type="password" placeholder="再次输入新密码" @input="pwdErr = ''" />
                    </div>
                </div>
                <div v-if="pwdErr" class="field-error" style="margin-top: -8px">{{ pwdErr }}</div>
                <div class="pwd-actions">
                    <button class="ghost-btn" @click="changePwd">重置密码</button>
                    <span v-if="pwdOk" class="pwd-ok">密码已重置</span>
                </div>
            </div>

            <template #footer>
                <button class="ghost-btn" @click="editing = false">取消</button>
                <button class="btn" @click="saveEdit">保存</button>
            </template>
        </AppModal>

        <!-- 移除成员确认 -->
        <AppConfirm
            v-if="removeTarget"
            title="移除成员"
            :text="`确定移除成员「${removeTarget.name}」吗？移除后将同时清除其全部模块权限。`"
            confirm-text="移除"
            @confirm="doRemove"
            @close="removeTarget = null"
        />
    </template>
</template>

<style scoped>
/* 筛选栏 */
.f-search {
    height: 32px;
    width: 240px;
    padding: 0 12px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--card);
    font-size: 13px;
    color: var(--t1);
    font-family: inherit;
    outline: none;
    box-shadow: var(--shadow-xs);
    transition: border-color 0.15s var(--ease), box-shadow 0.15s var(--ease);
}
.f-search:hover {
    border-color: var(--border-strong);
}
.f-search:focus {
    border-color: var(--primary);
    box-shadow: var(--ring);
}
.f-count {
    font-size:13px;
    color: var(--t3);
    margin-left: auto;
}

/* 成员表格列宽（总宽约 680，窄窗口下隐藏邮箱列防溢出） */
.col-name {
    flex: 1.3;
    min-width: 100px;
    display: flex;
    align-items: center;
    gap: 9px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.col-email {
    flex: 1.5;
    min-width: 130px;
    color: var(--t2);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.mcol-role {
    width: 80px;
    flex: none;
    font-size: 13px;
    color: var(--t2);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.col-join {
    width: 76px;
    flex: none;
    font-size:13px;
    color: var(--t3);
}
.col-load {
    width: 100px;
    flex: none;
    display: flex;
    align-items: center;
    gap: 6px;
}
.col-ops {
    width: 84px;
    flex: none;
    display: flex;
    justify-content: flex-end;
    gap: 4px;
}
/* 修改密码区（编辑弹窗内） */
.pwd-section {
    margin-top: 6px;
    border-top: 1px solid var(--border);
    padding-top: 14px;
}
.pwd-title {
    font-size: 13px;
    font-weight:700;
    margin-bottom: 12px;
}
.pwd-actions {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-top: 4px;
}
.pwd-ok {
    font-size:13px;
    color: var(--green);
}

@media (max-width: 1000px) {
    .col-email {
        display: none;
    }
} /* 窄窗口隐藏邮箱，保证操作列可见 */
.m-name {
    font-size:14px;
    font-weight:400;
}
.me-tag {
    font-size:12px;
    color: var(--t3);
    background: var(--track);
    padding: 1px 6px;
    border-radius: 5px;
}
/* 负载条 */
.load-bar {
    display: inline-block;
    width: 40px;
    height: 6px;
    border-radius: 3px;
    background: var(--track);
    overflow: hidden;
    flex: none;
}
.load-bar i {
    display: block;
    height: 100%;
    border-radius: 3px;
    background: var(--primary);
}
.load-bar i.over {
    background: var(--red);
}
.load-n {
    font-size:13px;
    color: var(--t2);
    font-weight:400;
}
.load-n.over {
    color: var(--red);
}

/* 行操作 */
.op-btn {
    border: none;
    background: none;
    padding: 3px 5px;
    border-radius: 5px;
    font-size:13px;
    color: var(--primary);
    cursor: pointer;
    font-family: inherit;
    white-space: nowrap;
}
.op-btn.danger {
    color: var(--red);
}
.empty {
    padding: 24px;
    text-align: center;
    font-size: 13px;
    color: var(--t3);
}
</style>
