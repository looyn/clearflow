<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { createEditor, createToolbar, Boot, DomEditor, SlateEditor, SlateTransforms } from '@wangeditor/editor';
import '@wangeditor/editor/dist/css/style.css'; // wangEditor 完整样式（工具栏 flex 布局等）
import { h } from 'snabbdom';
import { api } from '../api';
import { useMembersStore } from '../stores/members';
import { sanitizeHtml } from '../utils/sanitize';

// ===== 富文本编辑器（wangEditor v5 开源内核，MIT）=====
// 对外接口保持不变：modelValue(HTML) / placeholder / minHeight / toolbar / readonly
const props = defineProps({
    modelValue: { type: String, default: '' }, // HTML 内容
    placeholder: { type: String, default: '输入内容…' },
    minHeight: { type: Number, default: 160 },
    toolbar: { type: Boolean, default: true },
    readonly: { type: Boolean, default: false }
});
const emit = defineEmits(['update:modelValue']);
// 只读展示走清洗，避免历史内容里的脚本/内联事件被直接执行
const safeHtml = computed(() => (props.readonly ? sanitizeHtml(props.modelValue) : ''));

// ===== 注册 mention 自定义行内节点（全局仅注册一次）=====
// 让 <span class="mention" data-id>@姓名</span> 在编辑器内保留高亮，可随内容一起保存
let mentionRegistered = false;
function registerMention() {
    if (mentionRegistered) return;
    mentionRegistered = true;
    const esc = (s) =>
        String(s ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');

    // 1) 行内判定 + 空 mention 清理（同 link 的处理方式）
    Boot.registerPlugin((editor) => {
        const prevInline = editor.isInline;
        editor.isInline = (node) => node.type === 'mention' || prevInline(node);
        const prevNormalize = editor.normalizeNode;
        editor.normalizeNode = (entry, options) => {
            const [node, path] = entry;
            if (node.type === 'mention' && !SlateEditor.string(editor, path)) {
                SlateTransforms.removeNodes(editor, { at: path });
                return;
            }
            prevNormalize(entry, options);
        };
        return editor;
    });
    // 2) Slate → DOM 渲染
    Boot.registerRenderElem({
        type: 'mention',
        renderElem: (node) => h('span', { class: { mention: true }, attrs: { 'data-id': node.id || '' } }, node.name || node.children?.[0]?.text || '')
    });
    // 3) Slate → HTML（保存）
    Boot.registerElemToHtml({
        type: 'mention',
        elemToHtml: (node) => `<span class="mention" data-id="${esc(node.id)}">${esc(node.name)}</span>`
    });
    // 4) HTML → Slate（编辑已有内容时解析）
    Boot.registerParseElemHtml({
        selector: 'span.mention',
        parseElemHtml: ($el) => {
            const name = $el.text();
            return { type: 'mention', id: $el.attr('data-id') || '', name, children: [{ text: name }] };
        }
    });
}

const wrap = ref(null); // 根容器
const bar = ref(null); // 工具栏容器
const box = ref(null); // 编辑区容器
let editor = null;
let tb = null;
let lastEmitted = props.modelValue || '';

// 工具栏只保留一行核心按钮：排除视频（无后端）、全屏（弹窗内不合理）、
// 以及字号/字体/行高/颜色/对齐/缩进/表情/任务列表/分割线/上下标等低频按钮
const EXCLUDE_KEYS = [
    'uploadVideo',
    'insertVideo',
    'editVideoSize',
    'group-video',
    'fullScreen',
    'fontSize',
    'fontFamily',
    'lineHeight',
    'color',
    'bgColor',
    'group-justify',
    'group-indent',
    'indent',
    'delIndent',
    'emotion',
    'todo',
    'divider',
    'sub',
    'sup'
];

function currentHtml() {
    if (!editor || editor.isDestroyed) return props.modelValue || '';
    return editor.isEmpty() ? '' : editor.getHtml();
}

function onEditorChange(ed) {
    const html = ed.isEmpty() ? '' : ed.getHtml();
    lastEmitted = html;
    emit('update:modelValue', html);
    setTimeout(checkMention, 0); // 等 DOM / selection 同步后再检测 @
}

onMounted(() => {
    if (props.readonly) return;
    registerMention();
    editor = createEditor({
        selector: box.value,
        html: props.modelValue || '',
        config: {
            placeholder: props.placeholder,
            autoFocus: false,
            onChange: onEditorChange,
            onBlur: () => {
                mentionOpen.value = false;
            },
            MENU_CONF: {
                uploadImage: {
                    // 图片上传 RustFS，正文只存稳定读取 URL（/api/files/raw/<key>）；
                    // 不再 base64 内嵌（几十 KB 一张图会把 TEXT/MEDIUMTEXT 描述列撑爆）
                    customUpload(file, insertFn) {
                        api.uploadEditorImage(file)
                            .then((r) => insertFn(r.url, file.name, ''))
                            .catch((e) => alert('图片上传失败：' + (e?.message || e)));
                    }
                }
            }
        },
        mode: 'default'
    });
    if (props.toolbar) {
        tb = createToolbar({
            editor,
            selector: bar.value,
            mode: 'default',
            config: { excludeKeys: EXCLUDE_KEYS }
        });
    }
    // capture 捕获阶段拦截，先于编辑器内部键盘处理（mention 浮层打开时）
    wrap.value?.addEventListener('keydown', onMentionKey, true);
    document.addEventListener('mousedown', onDocDown, true);
});

onUnmounted(() => {
    wrap.value?.removeEventListener('keydown', onMentionKey, true);
    document.removeEventListener('mousedown', onDocDown, true);
    tb?.destroy();
    editor?.destroy();
    tb = null;
    editor = null;
});

// 外部 v-model 变化（表单重置、切换编辑对象）；自己 emit 的回环跳过
watch(
    () => props.modelValue,
    (v) => {
        const val = v || '';
        if (props.readonly) return;
        if (val === lastEmitted) return;
        if (editor && !editor.isDestroyed && val !== currentHtml()) editor.setHtml(val);
        lastEmitted = val;
    }
);

// ===== @提及 =====
const mentionOpen = ref(false);
const mentionList = ref([]);
const mentionIdx = ref(0);
const members = useMembersStore(); // @补全候选（共享成员 store）
let mentionQuery = ''; // @ 后的过滤词

async function checkMention() {
    if (props.readonly || !editor || editor.isDestroyed) return;
    const sel = window.getSelection();
    if (!sel || !sel.rangeCount) {
        mentionOpen.value = false;
        return;
    }
    const node = sel.anchorNode;
    if (!node || !box.value?.contains(node)) {
        mentionOpen.value = false;
        return;
    }
    // 光标前文本是否以 "@关键词" 结尾
    const before = node.nodeType === 3 ? node.textContent.slice(0, sel.anchorOffset) : '';
    const m = before.match(/@([^\s@]*)$/);
    if (!m) {
        mentionOpen.value = false;
        return;
    }
    mentionQuery = m[1];
    await members.load(); // 共享成员数据（已加载则复用）
    const list = members.list.filter((x) => !mentionQuery || x.name.includes(mentionQuery));
    mentionList.value = list;
    mentionIdx.value = 0;
    mentionOpen.value = list.length > 0;
}

// 键盘：↑↓ 导航 / Enter 选中 / Esc 关闭（capture 拦截，不触发编辑器换行与弹窗关闭）
function onMentionKey(e) {
    if (!mentionOpen.value || props.readonly) return;
    const n = mentionList.value.length;
    if (e.key === 'ArrowDown') {
        e.preventDefault();
        e.stopPropagation();
        mentionIdx.value = (mentionIdx.value + 1) % n;
    } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        e.stopPropagation();
        mentionIdx.value = (mentionIdx.value - 1 + n) % n;
    } else if (e.key === 'Enter') {
        e.preventDefault();
        e.stopPropagation();
        pickMember(mentionList.value[mentionIdx.value]);
    } else if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        mentionOpen.value = false; // 阻止冒泡关闭外层弹窗
    }
}

// 点击编辑区/浮层之外时关闭浮层
function onDocDown(e) {
    if (!mentionOpen.value) return;
    if (wrap.value?.contains(e.target)) return;
    mentionOpen.value = false;
}

// 选中成员：把光标前的 "@关键词" 替换为 mention 节点 + 空格
function pickMember(m) {
    mentionOpen.value = false;
    if (!m || !editor || editor.isDestroyed) return;
    const sel = window.getSelection();
    if (!sel || !sel.rangeCount) return;
    const node = sel.anchorNode;
    if (!node || node.nodeType !== 3 || !box.value?.contains(node)) return;
    const end = sel.anchorOffset;
    const start = end - (mentionQuery.length + 1); // 含 @
    if (start < 0) return;
    let path;
    try {
        path = DomEditor.findPath(editor, DomEditor.toSlateNode(editor, node));
    } catch {
        return;
    }
    SlateTransforms.select(editor, { anchor: { path, offset: start }, focus: { path, offset: end } });
    SlateTransforms.delete(editor);
    SlateTransforms.insertNodes(editor, [{ type: 'mention', id: m.id, name: '@' + m.name, children: [{ text: '@' + m.name }] }, { text: ' ' }]);
}
</script>

<template>
    <div ref="wrap" class="ed">
        <!-- 编辑态：wangEditor 工具栏 + 编辑区 -->
        <template v-if="!readonly">
            <div v-if="toolbar" ref="bar" class="ed-bar"></div>
            <div ref="box" class="ed-box" :class="{ 'no-bar': !toolbar }" :style="{ '--ed-min': minHeight + 'px' }"></div>
        </template>

        <!-- 只读态：直接渲染 HTML（保留 mention 高亮等原样输出） -->
        <div v-else class="ed-ro" :data-ph="placeholder" :style="{ minHeight: minHeight + 'px' }" v-html="safeHtml"></div>

        <!-- @提及 下拉 -->
        <div v-if="mentionOpen && !readonly" class="ed-mention">
            <div v-for="(m, i) in mentionList" :key="m.id" class="ed-mitem" :class="{ on: i === mentionIdx }" @mousedown.prevent="pickMember(m)">
                <span class="ava mini-ava" :class="m.color">{{ m.short }}</span>
                <span class="ed-mname">{{ m.name }}</span>
                <span class="ed-mrole">{{ m.role }}</span>
            </div>
        </div>
    </div>
</template>

<style scoped>
.ed {
    position: relative;
    /* wangEditor 主题全部走这套变量，改在根节点让工具栏/下拉/弹层一并跟随 */
    --w-e-toolbar-bg-color: var(--card);
    --w-e-toolbar-color: var(--t2);
    --w-e-toolbar-active-color: var(--t1);
    --w-e-toolbar-active-bg-color: var(--surface-3);
    --w-e-toolbar-disabled-color: var(--t3);
    --w-e-toolbar-border-color: var(--border);
    --w-e-textarea-bg-color: var(--card);
    --w-e-textarea-color: var(--t1);
    /* 表格边框/分割线/虚线框都取自这两个变量，不能置 transparent，否则边框不可见 */
    --w-e-textarea-border-color: var(--border);
    --w-e-textarea-slight-border-color: var(--border);
    --w-e-textarea-slight-color: var(--t3);
    --w-e-textarea-slight-bg-color: var(--track);
    --w-e-textarea-selected-border-color: var(--primary);
    --w-e-modal-button-bg-color: var(--surface-2);
    --w-e-modal-button-border-color: var(--border);
}

/* 工具栏 + 编辑区外框（贴合项目扁平风格） */
.ed-bar,
.ed-box {
    border: 1px solid var(--border);
    background: var(--card);
}
.ed-bar {
    border-radius: 8px 8px 0 0;
    position: relative;
    z-index: 2;
} /* z-index：下拉菜单盖过编辑区 */
/* 工具栏紧凑化：缩小按钮内边距与分隔线间距，保证一行放下 */
.ed-bar :deep(.w-e-bar-item) {
    padding: 2px 3px;
}
.ed-bar :deep(.w-e-bar-item button) {
    padding: 0 5px;
}
.ed-bar :deep(.w-e-bar-divider) {
    margin: 0 3px;
}
.ed-box {
    border-top: none;
    border-radius: 0 0 8px 8px;
    min-height: var(--ed-min, 96px);
}
.ed-box.no-bar {
    border-top: 1px solid var(--border);
    border-radius: 8px;
}
.ed-box :deep(.w-e-text-container) {
    min-height: var(--ed-min, 96px);
}
.ed-box :deep([data-slate-editor]) {
    min-height: var(--ed-min, 96px);
    font-size: 13px;
    line-height: 1.6;
    padding: 8px 10px;
}
.ed-box :deep([data-slate-editor] p) {
    margin: 0.2em 0;
}
.ed-box :deep([data-slate-editor] h1) {
    font-size: 20px;
}
.ed-box :deep([data-slate-editor] h2) {
    font-size: 17px;
}
.ed-box :deep([data-slate-editor] h3) {
    font-size: 15px;
}
.ed-box :deep([data-slate-editor] ul),
.ed-box :deep([data-slate-editor] ol) {
    padding-left: 1.6em;
    margin: 0.25em 0;
}
.ed-box :deep([data-slate-editor] blockquote) {
    border-left: 3px solid var(--border);
    padding-left: 9px;
    color: var(--t2);
    margin: 0.4em 0;
}
.ed-box :deep([data-slate-editor] pre) {
    background: var(--track);
    border-radius: 6px;
    padding: 8px 10px;
    margin: 0.4em 0;
}
.ed-box :deep([data-slate-editor] code) {
    background: var(--track);
    border-radius: 4px;
    padding: 1px 5px;
    font-size: 12px;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.ed-box :deep([data-slate-editor] a) {
    color: var(--primary);
}
.ed-box :deep([data-slate-editor] img) {
    max-width: 100%;
}
/* 表格（与只读态样式对称）
   wangEditor 自带规则为 .w-e-text-container [data-slate-editor] table td，
   这里用同等深度的选择器覆盖，避免再被其内置样式（透明边框/默认内边距）盖回去 */
.ed-box :deep(.w-e-text-container [data-slate-editor] .table-container) {
    border: none;
    border-radius: 0;
    margin: 0.4em 0;
    padding: 0;
}
.ed-box :deep(.w-e-text-container [data-slate-editor] table) {
    border-collapse: collapse;
    margin: 0.4em 0;
    width: auto;
}
.ed-box :deep(.w-e-text-container [data-slate-editor] table th),
.ed-box :deep(.w-e-text-container [data-slate-editor] table td) {
    border: 1px solid var(--border);
    padding: 5px 9px;
    min-width: 32px;
    font-size:13px;
    line-height: 1.5;
    text-align: left;
}
.ed-box :deep(.w-e-text-container [data-slate-editor] table th) {
    background: var(--track);
    font-weight:700;
    text-align: left;
}

/* mention 高亮（编辑态与只读态都要） */
.ed-box :deep(.mention),
.ed-ro :deep(.mention) {
    background: var(--primary-bg);
    color: var(--primary);
    border-radius: 4px;
    padding: 0 3px;
    white-space: nowrap;
}

/* 只读态容器与内容排版 */
.ed-ro {
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--card);
    padding: 8px 10px;
    font-size: 13px;
    line-height: 1.6;
    color: var(--t1);
    word-break: break-word;
}
.ed-ro:empty::before {
    content: attr(data-ph);
    color: var(--t3);
}
.ed-ro :deep(p) {
    margin: 0.25em 0;
}
.ed-ro :deep(h1) {
    font-size: 20px;
    margin: 0.4em 0;
}
.ed-ro :deep(h2) {
    font-size: 17px;
    margin: 0.4em 0;
}
.ed-ro :deep(h3) {
    font-size: 15px;
    margin: 0.4em 0;
}
.ed-ro :deep(h4),
.ed-ro :deep(h5),
.ed-ro :deep(h6) {
    font-size: 14px;
    margin: 0.4em 0;
}
.ed-ro :deep(ul),
.ed-ro :deep(ol) {
    padding-left: 1.6em;
    margin: 0.25em 0;
}
.ed-ro :deep(li) {
    margin: 0.15em 0;
}
.ed-ro :deep(blockquote) {
    border-left: 3px solid var(--border);
    padding-left: 9px;
    color: var(--t2);
    margin: 0.4em 0;
}
.ed-ro :deep(pre) {
    background: var(--track);
    border-radius: 6px;
    padding: 8px 10px;
    margin: 0.4em 0;
    overflow-x: auto;
}
.ed-ro :deep(code) {
    background: var(--track);
    border-radius: 4px;
    padding: 1px 5px;
    font-size: 12px;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.ed-ro :deep(a) {
    color: var(--primary);
}
.ed-ro :deep(img) {
    max-width: 100%;
    border-radius: 6px;
}
.ed-ro :deep(table) {
    border-collapse: collapse;
    margin: 0.4em 0;
}
.ed-ro :deep(th),
.ed-ro :deep(td) {
    border: 1px solid var(--border);
    padding: 4px 8px;
    font-size:13px;
}
.ed-ro :deep(th) {
    background: var(--track);
    font-weight:700;
}

/* @提及 下拉 */
.ed-mention {
    position: absolute;
    left: 0;
    top: 100%;
    margin-top: 4px;
    min-width: 200px;
    max-width: 320px;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 8px;
    box-shadow: var(--shadow-md);
    padding: 4px;
    z-index: 20;
}
.ed-mitem {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 8px;
    border-radius: 6px;
    cursor: pointer;
}
.ed-mitem.on {
    background: var(--primary-bg);
}
.ed-mname {
    font-size: 13px;
    color: var(--t1);
}
.ed-mrole {
    font-size:12px;
    color: var(--t3);
    margin-left: auto;
}
</style>
