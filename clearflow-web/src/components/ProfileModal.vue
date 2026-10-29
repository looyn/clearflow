<script setup>
import { ref, reactive, watch, computed } from 'vue';
import { api } from '../api';
import { useAuthStore } from '../stores/auth';
import AppModal from './AppModal.vue';

// 个人信息弹窗：tab = 'info'（查看个人信息）| 'password'（修改密码）
const props = defineProps({
    tab: { type: String, default: 'info' }
});
const emit = defineEmits(['close']);

const auth = useAuthStore();
const activeTab = ref(props.tab);
watch(
    () => props.tab,
    (v) => (activeTab.value = v)
);

// ===== 个人信息（取后端最新，含 login 登录账号）=====
const me = ref(null);
const loading = ref(true);
const loadErr = ref('');
async function loadMe() {
    loading.value = true;
    loadErr.value = '';
    try {
        me.value = await api.getMe();
    } catch (e) {
        loadErr.value = e?.message || '加载失败';
    } finally {
        loading.value = false;
    }
}
loadMe();

const rows = computed(() => {
    const u = me.value || {};
    return [
        { label: '姓名', value: u.name },
        { label: '登录账号', value: u.login || u.email },
        { label: '邮箱', value: u.email },
        { label: '职位', value: u.role },
        { label: '权限角色', value: u.perm },
        { label: '加入时间', value: u.joined },
        { label: '容量（小时/迭代）', value: u.capacity ?? '—' }
    ];
});

// ===== 修改密码 =====
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' });
const errors = reactive({});
const saving = ref(false);
const saved = ref(false);
const saveErr = ref('');
let savedTimer = null;

function clearErr(key) {
    delete errors[key];
    saveErr.value = '';
}

function validate() {
    Object.keys(errors).forEach((k) => delete errors[k]);
    let ok = true;
    if (!form.oldPassword) {
        errors.oldPassword = '请输入原密码';
        ok = false;
    }
    if (!form.newPassword || form.newPassword.length < 6) {
        errors.newPassword = '新密码至少 6 位';
        ok = false;
    }
    if (form.confirm !== form.newPassword) {
        errors.confirm = '两次输入的新密码不一致';
        ok = false;
    }
    return ok;
}

async function submit() {
    saveErr.value = '';
    saved.value = false;
    if (!validate()) return;
    saving.value = true;
    try {
        await api.changePassword(form.oldPassword, form.newPassword);
        form.oldPassword = form.newPassword = form.confirm = '';
        saved.value = true;
        clearTimeout(savedTimer);
        savedTimer = setTimeout(() => (saved.value = false), 2500);
    } catch (e) {
        // 后端错误（如原密码不正确）行内展示，不吞掉
        saveErr.value = e?.message || '修改失败，请稍后重试';
    } finally {
        saving.value = false;
    }
}
</script>

<template>
    <AppModal :title="activeTab === 'password' ? '修改密码' : '个人信息'" :width="520" @close="emit('close')">
        <!-- tab 切换 -->
        <div class="pm-tabs">
            <button class="pm-tab" :class="{ on: activeTab === 'info' }" @click="activeTab = 'info'">个人信息</button>
            <button class="pm-tab" :class="{ on: activeTab === 'password' }" @click="activeTab = 'password'">修改密码</button>
        </div>

        <!-- ===== 个人信息 ===== -->
        <template v-if="activeTab === 'info'">
            <div v-if="loading" class="pm-loading">加载中…</div>
            <div v-else-if="loadErr" class="field-error">{{ loadErr }}</div>
            <template v-else>
                <div class="pm-head">
                    <div class="pm-ava" :class="me?.color || 'blue'">{{ me?.short || (me?.name || '未')[0] }}</div>
                    <div>
                        <div class="pm-name">{{ me?.name || '—' }}</div>
                        <div class="pm-sub">{{ [me?.role, me?.perm].filter(Boolean).join(' · ') || '—' }}</div>
                    </div>
                </div>
                <div class="pm-rows">
                    <div v-for="r in rows" :key="r.label" class="pm-row">
                        <span class="pm-label">{{ r.label }}</span>
                        <span class="pm-value">{{ r.value || '—' }}</span>
                    </div>
                </div>
            </template>
        </template>

        <!-- ===== 修改密码 ===== -->
        <template v-else>
            <div class="pm-form">
                <label class="field-label">原密码<span class="req">*</span></label>
                <input
                    class="field-input"
                    :class="{ invalid: errors.oldPassword }"
                    type="password"
                    v-model="form.oldPassword"
                    placeholder="输入当前密码"
                    @input="clearErr('oldPassword')"
                />
                <div v-if="errors.oldPassword" class="field-error">{{ errors.oldPassword }}</div>

                <label class="field-label">新密码<span class="req">*</span></label>
                <input
                    class="field-input"
                    :class="{ invalid: errors.newPassword }"
                    type="password"
                    v-model="form.newPassword"
                    placeholder="至少 6 位"
                    @input="clearErr('newPassword')"
                />
                <div v-if="errors.newPassword" class="field-error">{{ errors.newPassword }}</div>

                <label class="field-label">确认新密码<span class="req">*</span></label>
                <input
                    class="field-input"
                    :class="{ invalid: errors.confirm }"
                    type="password"
                    v-model="form.confirm"
                    placeholder="再次输入新密码"
                    @input="clearErr('confirm')"
                />
                <div v-if="errors.confirm" class="field-error">{{ errors.confirm }}</div>

                <div v-if="saveErr" class="field-error pm-save-err">{{ saveErr }}</div>
            </div>
        </template>

        <template #footer>
            <button class="ghost-btn" @click="emit('close')">关闭</button>
            <button v-if="activeTab === 'password'" class="btn" :class="{ ok: saved }" :disabled="saving" @click="submit">
                {{ saving ? '保存中…' : saved ? '已修改 ✓' : '确认修改' }}
            </button>
        </template>
    </AppModal>
</template>

<style scoped>
.pm-tabs {
    display: flex;
    gap: 4px;
    border-bottom: 1px solid var(--border);
    margin-bottom: 16px;
}
.pm-tab {
    border: none;
    background: none;
    padding: 6px 10px 9px;
    font-size: 13px;
    color: var(--t2);
    cursor: pointer;
    border-bottom: 2px solid transparent;
    margin-bottom: -1px;
    font-family: inherit;
}
.pm-tab.on {
    color: var(--primary);
    border-bottom-color: var(--primary);
    font-weight:400;
}

.pm-loading {
    font-size: 13px;
    color: var(--t3);
    padding: 8px 0;
}

.pm-head {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 16px;
}
.pm-ava {
    width: 44px;
    height: 44px;
    flex: none;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    font-size: 17px;
    font-weight:700;
    background: var(--primary-fill);
}
.pm-ava.teal { background: #00b2a9; }
.pm-ava.orange { background: #ff7d00; }
.pm-ava.green { background: var(--green); }
.pm-ava.sky { background: #0fa6e8; }
.pm-ava.ink { background: #646a73; }
.pm-name {
    font-size: 15px;
    font-weight:700;
}
.pm-sub {
    font-size: 12px;
    color: var(--t3);
    margin-top: 2px;
}

.pm-rows {
    display: flex;
    flex-direction: column;
}
.pm-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 9px 0;
    border-top: 1px solid var(--border);
}
.pm-row:first-child {
    border-top: none;
}
.pm-label {
    width: 130px;
    flex: none;
    font-size:13px;
    color: var(--t3);
}
.pm-value {
    font-size: 13px;
    color: var(--t1);
    word-break: break-all;
}

.pm-form .field-label {
    margin-top: 12px;
}
.pm-form .field-label:first-child {
    margin-top: 0;
}
.pm-save-err {
    margin-top: 14px;
}
.btn.ok {
    background: var(--green);
}
.req {
    color: var(--red);
    margin-left: 2px;
}
</style>
