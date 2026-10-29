<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { api } from '../api';
import { useUiStore } from '../stores/ui';
import { useAuthStore } from '../stores/auth';
import { useMembersStore } from '../stores/members';
import AppEditor from './AppEditor.vue';
import { sanitizeHtml } from '../utils/sanitize';

const previewUrl = ref('');

// ===== 评论区（自包含：自己拉数据，挂到详情弹窗主区即可用）=====
const props = defineProps({
    kind: { type: String, required: true }, // 'task' | 'req' | 'defect' | 'release'
    refId: { type: String, required: true } // 实体 id
});

const ui = useUiStore();
const members = useMembersStore(); // 成员映射（共享，评论加载时确保已加载）
const CURRENT_USER = useAuthStore().user?.id ?? '';
const list = ref([]); // 评论列表
const mmap = computed(() => members.map); // 成员映射 id → member
const text = ref(''); // 编辑器 HTML
const busy = ref(false);

// 纯文本长度（判断是否可提交）
const plain = computed(() =>
    text.value
        .replace(/<[^>]+>/g, '')
        .replace(/&nbsp;/g, ' ')
        .trim()
);
const hasImage = computed(() => /<img\s/i.test(text.value));

async function load() {
    if (!props.refId) {
        list.value = [];
        return;
    }
    await members.load(); // 名称映射需要的成员数据
    list.value = await api.getComments(props.kind, props.refId);
}

onMounted(load);
watch(() => props.refId, load);
watch(() => ui.refreshTick, load);

// 提交评论（api 端会按 @姓名 自动识别 mentions 并生成提及通知）
async function submit() {
    if (busy.value || !plain.value) return;
    busy.value = true;
    const html = text.value;
    await api.addComment(props.kind, props.refId, html);
    text.value = '';
    busy.value = false;
    await load();
}

// 删除自己的评论
async function remove(c) {
    await api.deleteComment(c.id);
    await load();
}

// 图片预览
function onTextClick(e) {
    const img = e.target.closest('img');
    if (img && img.src) previewUrl.value = img.src;
}
function closePreview() {
    previewUrl.value = '';
}
function onKeydown(e) {
    if (e.key === 'Escape') closePreview();
}
onMounted(() => document.addEventListener('keydown', onKeydown));
onUnmounted(() => document.removeEventListener('keydown', onKeydown));
</script>

<template>
    <div class="cm">
        <div class="cm-head">
            评论 <span class="cm-count">{{ list.length }}</span>
        </div>

        <!-- 列表 -->
        <div v-if="list.length" class="cm-list">
            <div v-for="(c, i) in list" :key="i" class="cm-item">
                <span class="ava mini-ava" :class="mmap[c.by]?.color">{{ mmap[c.by]?.short || '?' }}</span>
                <div class="cm-body">
                    <div class="cm-meta">
                        <span class="cm-name">{{ mmap[c.by]?.name || '未知' }}</span>
                        <span class="cm-at">{{ c.at }}</span>
                        <button v-if="c.by === CURRENT_USER" class="cm-del" title="删除评论" @click="remove(c)">×</button>
                    </div>
                    <div class="cm-text" v-html="sanitizeHtml(c.text)" @click="onTextClick"></div>
                </div>
            </div>
        </div>
        <div v-else class="cm-empty">还没有评论，来发表第一条</div>

        <!-- 编辑器 -->
        <div class="cm-form">
            <AppEditor v-model="text" :min-height="72" placeholder="写下你的评论…" />
            <div class="cm-actions">
                <button class="btn" :disabled="(!plain && !hasImage) || busy" @click="submit">评论</button>
            </div>
        </div>
    </div>

    <!-- 图片预览 lightbox -->
    <Teleport to="body">
        <div v-if="previewUrl" class="cm-overlay" @click.self="closePreview">
            <img :src="previewUrl" class="cm-preview-img" @click.stop />
            <button class="cm-overlay-close" @click="closePreview">×</button>
        </div>
    </Teleport>
</template>

<style scoped>
.cm-head {
    font-size: 13px;
    font-weight:700;
    margin-bottom: 8px;
}
.cm-count {
    font-size: 12px;
    font-weight:400;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}

/* 列表 */
.cm-item {
    display: flex;
    gap: 10px;
    padding: 10px 0;
}
.cm-item + .cm-item {
    border-top: 1px solid var(--border);
}
.cm-body {
    flex: 1;
    min-width: 0;
}
.cm-meta {
    display: flex;
    align-items: baseline;
    gap: 8px;
}
.cm-name {
    font-size: 13px;
    font-weight:400;
}
.cm-at {
    font-size:12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
}
.cm-del {
    margin-left: auto;
    width: 18px;
    height: 18px;
    border: none;
    background: none;
    padding: 0;
    color: var(--t3);
    font-size: 15px;
    line-height: 1;
    cursor: pointer;
    font-family: inherit;
    display: flex;
    align-items: center;
    justify-content: center;
}
.cm-text {
    font-size: 13px;
    color: var(--t2);
    line-height: 1.6;
    margin-top: 3px;
    word-break: break-word;
}
.cm-text :deep(.mention) {
    background: var(--primary-bg);
    color: var(--primary);
    border-radius: 4px;
    padding: 0 3px;
    white-space: nowrap;
}
/* 评论正文由富文本编辑器产出，可以直接包含表格。
   v-html 渲染的内容没有 scoped 的 data-v 标记，必须用 :deep 才能命中，
   否则 <table> 会落在浏览器默认样式上（border-collapse:separate、单元格无边框），
   表现就是「插入的表格没有边框」。这里与 AppEditor 只读态（.ed-ro）保持一致 */
.cm-text :deep(table) {
    border-collapse: collapse;
    margin: 4px 0;
}
.cm-text :deep(th),
.cm-text :deep(td) {
    border: 1px solid var(--border);
    padding: 4px 8px;
    font-size:13px;
    line-height: 1.5;
    text-align: left;
}
.cm-text :deep(th) {
    background: var(--track);
    font-weight:700;
}
.cm-empty {
    font-size:13px;
    color: var(--t3);
    padding: 8px 0 4px;
}

/* 编辑区 */
.cm-form {
    margin-top: 12px;
}
.cm-actions {
    display: flex;
    justify-content: flex-end;
    margin-top: 8px;
}
.btn:disabled {
    opacity: 0.5;
    cursor: default;
}

/* 评论内图片可点击预览 */
.cm-text img {
    cursor: zoom-in;
    max-width: 100%;
    border-radius: 4px;
}
.cm-text img:hover {
    opacity: 0.9;
}

/* lightbox */
.cm-overlay {
    position: fixed;
    z-index: 9999;
    inset: 0;
    background: rgba(0, 0, 0, 0.78);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: zoom-out;
}
.cm-preview-img {
    max-width: 90vw;
    max-height: 90vh;
    object-fit: contain;
    border-radius: 4px;
    cursor: default;
}
.cm-overlay-close {
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
