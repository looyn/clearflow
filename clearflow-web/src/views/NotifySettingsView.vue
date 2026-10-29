<script setup>
import { ref, reactive, onMounted } from 'vue';
import { api } from '../api';
import LoadingMask from '../components/LoadingMask.vue';

const loading = ref(true);
const notifyList = ref([]);

async function load() {
    const personal = await api.getSettings();
    notifyList.value = JSON.parse(JSON.stringify(personal.notify || []));
}
onMounted(async () => {
    await load();
    loading.value = false;
});

// ===== 保存反馈：行尾「已保存 / 保存失败」，失败回滚开关 =====
const saved = ref(''); // 刚保存成功的开关 key
const saving = ref(''); // 正在保存的开关 key
const saveErr = reactive({}); // 开关 key -> 失败原因
let savedTimer = null;
function flashSaved(key) {
    saved.value = key;
    clearTimeout(savedTimer);
    savedTimer = setTimeout(() => {
        saved.value = '';
    }, 2000);
}

// 切换即保存；失败回滚，避免界面与后端不一致
async function toggleNotify(n) {
    const key = n.key;
    const prev = n.on;
    n.on = !n.on;
    saving.value = key;
    delete saveErr[key];
    try {
        await api.saveSettings('notify', { [n.key]: n.on });
        flashSaved(key);
    } catch (e) {
        n.on = prev;
        saveErr[key] = e?.message || '保存失败，请稍后重试';
    } finally {
        saving.value = '';
    }
}
</script>

<template>
    <LoadingMask v-if="loading" />
    <template v-else>
        <!-- 页头 -->
        <div class="page-head rise d1">
            <div>
                <h1>通知设置</h1>
                <div class="page-meta">你的个人通知偏好，仅对你自己生效</div>
            </div>
        </div>

        <div class="card rise d2">
            <div class="card-head">
                <span class="card-title">通知开关</span>
                <span class="card-extra">切换后自动保存</span>
            </div>
            <div v-for="n in notifyList" :key="n.key" class="notify-row">
                <div>
                    <div class="nr-label">{{ n.label }}</div>
                    <div class="nr-desc">{{ n.desc }}</div>
                </div>
                <b v-if="saved === n.key" class="saved-tip">已保存</b>
                <span v-else-if="saveErr[n.key]" class="notify-err">保存失败：{{ saveErr[n.key] }}</span>
                <button class="switch" :class="{ on: n.on }" :disabled="saving === n.key" @click="toggleNotify(n)"><span class="knob"></span></button>
            </div>
        </div>
    </template>
</template>

<style scoped>
.card {
    margin-top: 16px;
}
.page-meta {
    font-size:13px;
    color: var(--t3);
    margin-top: 4px;
}
.notify-row {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 13px 0;
    border-top: 1px solid var(--border);
}
.notify-row:first-of-type {
    border-top: none;
    padding-top: 2px;
}
.nr-label {
    font-size:14px;
    font-weight:400;
}
.nr-desc {
    font-size: 12px;
    color: var(--t3);
    margin-top: 2px;
}
.saved-tip {
    margin-left: auto;
    font-size: 12px;
    color: var(--green);
    font-weight:400;
}
.notify-err {
    margin-left: auto;
    font-size: 12px;
    color: var(--red);
}
.card-extra {
    font-size: 12px;
    color: var(--t3);
}
.switch {
    width: 36px;
    height: 20px;
    flex: none;
    border: none;
    border-radius: 10px;
    background: var(--track);
    cursor: pointer;
    position: relative;
    padding: 0;
    transition: background 0.15s;
}
.switch.on {
    background: var(--primary);
}
.switch:disabled {
    opacity: 0.6;
    cursor: default;
}
.knob {
    position: absolute;
    top: 2px;
    left: 2px;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    background: #fff;
    transition: left 0.15s;
    box-shadow: var(--shadow-xs);
}
.switch.on .knob {
    left: 18px;
}
</style>
