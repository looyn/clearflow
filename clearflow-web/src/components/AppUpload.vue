<script setup>
import { ref, reactive, markRaw, onUnmounted, onMounted } from 'vue';
import { useAuthStore } from '../stores/auth';

// ===== 附件上传（纯受控组件，本地处理，不调用 api）=====
const props = defineProps({
    modelValue: { type: Array, default: () => [] }, // 附件数组 { id, name, size, kind, by, time, url }
    disabled: Boolean
});
const emit = defineEmits(['update:modelValue', 'change']);
const auth = useAuthStore(); // 当前登录人（真实后端取 auth.user）;

const inputEl = ref(null); // 隐藏的 file input
const err = ref(''); // 内联错误文案（超限时显示）
let errTimer = null;
const previewUrl = ref(''); // 图片预览 lightbox

// 打开文件选择
function openPicker() {
    if (props.disabled) return;
    inputEl.value?.click();
}

// 大小格式化：KB / MB
function fmtSize(n) {
    if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
    return Math.max(1, Math.round(n / 1024)) + ' KB';
}

// 时间格式化 MM.DD HH:mm
function fmtTime(d) {
    const p = (n) => String(n).padStart(2, '0');
    return `${p(d.getMonth() + 1)}.${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// 显示错误 3 秒后自动消失
function showErr(msg) {
    err.value = msg;
    clearTimeout(errTimer);
    errTimer = setTimeout(() => {
        err.value = '';
    }, 3000);
}

// 选中文件：校验大小 → 生成待上传条目（真实上传由宿主页面保存时调用 api.saveAttachment）
function onPick(e) {
    const files = Array.from(e.target.files || []);
    e.target.value = ''; // 清空，允许重复选择同一文件
    if (!files.length) return;
    const next = [...props.modelValue];
    files.forEach((f) => {
        if (f.size > 20 * 1024 * 1024) {
            showErr(`${f.name} 超过 20MB 限制`);
            return;
        }
        const isImg = f.type.startsWith('image/');
        // reactive：缩略图由组件内部直接更新，驱动界面刷新
        const item = reactive({
            id: 'a' + Date.now() + Math.random().toString(36).slice(2, 6),
            name: f.name,
            size: f.size,
            kind: isImg ? 'image' : 'file',
            by: auth.user?.id ?? '',
            file: markRaw(f), // 原始 File：宿主保存时经 api.saveAttachment 真实上传
            time: fmtTime(new Date()),
            url: ''
        });
        // 图片读取 dataURL 作缩略图
        if (isImg) {
            const reader = new FileReader();
            reader.onload = (ev) => {
                item.url = ev.target.result;
            };
            reader.readAsDataURL(f);
        }
        next.push(item);
    });
    emit('update:modelValue', next);
    emit('change', next);
}

// 删除条目
function remove(item) {
    if (props.disabled) return;
    const next = props.modelValue.filter((x) => x.id !== item.id);
    emit('update:modelValue', next);
    emit('change', next);
}

// 粘贴图片：截图工具 / 剪贴板图片直接上传
function onPaste(e) {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (const item of items) {
        if (item.kind === 'file' && item.type.startsWith('image/')) {
            const file = item.getAsFile();
            if (file) {
                e.preventDefault();
                onPick({ target: { files: [file] } });
                break;
            }
        }
    }
}

// 图片预览 lightbox
function openPreview(url) {
    previewUrl.value = url;
}
function closePreview() {
    previewUrl.value = '';
}
function onKeydown(e) {
    if (e.key === 'Escape' && previewUrl.value) closePreview();
}
onMounted(() => document.addEventListener('keydown', onKeydown));
onUnmounted(() => {
    clearTimeout(errTimer);
});
</script>

<template>
    <div class="up" @paste="onPaste">
        <input ref="inputEl" type="file" multiple hidden accept="image/*,.pdf,.zip,.doc,.docx,.xls,.xlsx,.log,.txt" @change="onPick" />

        <div class="up-grid">
            <div v-for="it in modelValue" :key="it.id" class="up-item" :class="it.kind">
                <!-- 图片：120×84 缩略图 -->
                <img v-if="it.kind === 'image' && it.url" :src="it.url" class="up-img" alt="" @click="openPreview(it.url)" />
                <div v-else-if="it.kind === 'image'" class="up-img up-img-ph" @click="openPreview(it.url)"></div>
                <!-- 非图片：文件图标 + 文件名 + 大小 -->
                <div v-else class="up-file">
                    <svg viewBox="0 0 24 24">
                        <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
                        <path d="M14 3v5h5" />
                    </svg>
                    <div class="up-name" :title="it.name">{{ it.name }}</div>
                    <div class="up-size">{{ fmtSize(it.size) }}</div>
                </div>
                <!-- 删除 -->
                <button v-if="!disabled" class="up-del" title="删除" @click="remove(it)">×</button>
            </div>

            <!-- 添加框（空态时仅显示此框） -->
            <button v-if="!disabled" class="up-add" type="button" @click="openPicker">+ 添加附件<br /><span class="up-hint">或 Ctrl+V 粘贴截图</span></button>
        </div>

        <!-- 超限错误内联提示 -->
        <div v-if="err" class="up-err">{{ err }}</div>
    </div>

    <!-- 图片预览 lightbox -->
    <Teleport to="body">
        <div v-if="previewUrl" class="up-overlay" @click.self="closePreview">
            <img :src="previewUrl" class="up-preview-img" @click.stop />
            <button class="up-overlay-close" @click="closePreview">×</button>
        </div>
    </Teleport>
</template>

<style scoped>
.up-grid {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
}

.up-item {
    position: relative;
    height: 84px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--card);
    overflow: hidden;
}
.up-item.image {
    width: 120px;
}
.up-item.file {
    width: 150px;
}

.up-img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
}
.up-img-ph {
    background: var(--track);
}

.up-file {
    height: 100%;
    padding: 10px 10px 8px;
    display: flex;
    flex-direction: column;
    gap: 3px;
}
.up-file svg {
    width: 18px;
    height: 18px;
    stroke: var(--t3);
    stroke-width: 1.6;
    fill: none;
    stroke-linejoin: round;
}
.up-name {
    font-size: 12px;
    color: var(--t1);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.up-size {
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}

.up-del {
    position: absolute;
    top: 4px;
    right: 4px;
    width: 18px;
    height: 18px;
    border: 1px solid var(--border);
    border-radius: 50%;
    background: var(--card);
    color: var(--t2);
    font-size: 13px;
    line-height: 1;
    cursor: pointer;
    padding: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    font-family: inherit;
}

.up-add {
    width: 140px;
    height: 84px;
    border: 1px dashed var(--border);
    border-radius: 8px;
    background: none;
    color: var(--t3);
    font-size:13px;
    cursor: pointer;
    font-family: inherit;
    line-height: 1.4;
}
.up-hint {
    font-size:12px;
    color: var(--t3);
    font-weight: 400;
}

.up-err {
    font-size:12px;
    color: var(--red);
    margin-top: 8px;
}

/* 缩略图可点击预览 */
.up-img {
    cursor: pointer;
}
.up-img-ph {
    cursor: pointer;
}

/* lightbox 全屏遮罩 */
.up-overlay {
    position: fixed;
    z-index: 9999;
    inset: 0;
    background: rgba(0, 0, 0, 0.78);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: zoom-out;
}
.up-preview-img {
    max-width: 90vw;
    max-height: 90vh;
    object-fit: contain;
    border-radius: 4px;
    cursor: default;
}
.up-overlay-close {
    position: fixed;
    top: 20px;
    right: 24px;
    width: 36px;
    height: 36px;
    border: none;
    border-radius: 50%;
    background: rgba(0, 0, 0, 0.5);
    color: #fff;
    font-size: 22px;
    line-height: 1;
    cursor: pointer;
    font-family: inherit;
    display: flex;
    align-items: center;
    justify-content: center;
}
</style>
