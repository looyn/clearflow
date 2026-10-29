<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api';
import { useUiStore } from '../stores/ui';

const router = useRouter();
const ui = useUiStore();
const q = ref('');
const rows = ref([]);
const input = ref(null);
const sel = ref(0);
const searching = ref(false);

const open = computed(() => ui.searchOpen);
watch(open, async (v) => {
    if (v) {
        q.value = '';
        rows.value = [];
        sel.value = 0;
        await nextTick();
        input.value?.focus();
    }
});

// 输入防抖 + 请求序号：只采纳最后一次查询的结果，避免慢请求覆盖新结果
let timer = null;
let seq = 0;
watch(q, (v) => {
    clearTimeout(timer);
    const my = ++seq;
    const kw = v.trim();
    if (!kw) {
        rows.value = [];
        sel.value = 0;
        searching.value = false;
        return;
    }
    searching.value = true;
    timer = setTimeout(async () => {
        try {
            const r = await api.search(kw);
            if (my !== seq) return;
            rows.value = r || [];
            sel.value = 0;
        } catch {
            if (my === seq) rows.value = [];
        } finally {
            if (my === seq) searching.value = false;
        }
    }, 250);
});

const kindCls = { 任务: 'bl', 需求: 'gn', 缺陷: 'rd', MR: 'pu', 迭代: 'am' };

function go(r) {
    if (!r) return;
    ui.searchOpen = false;
    router.push({ path: r.page, query: { focus: r.id } });
}
function onKey(e) {
    if (!open.value) return;
    if (e.key === 'Escape') ui.searchOpen = false;
    else if (e.key === 'ArrowDown') {
        e.preventDefault();
        sel.value = Math.min(sel.value + 1, rows.value.length - 1);
    } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        sel.value = Math.max(sel.value - 1, 0);
    } else if (e.key === 'Enter') go(rows.value[sel.value]);
}
function close() {
    ui.searchOpen = false;
}
onMounted(() => window.addEventListener('keydown', onKey));
onUnmounted(() => {
    window.removeEventListener('keydown', onKey);
    clearTimeout(timer);
});
</script>

<template>
    <Teleport to="body">
        <div v-if="open" class="gs-overlay" style="z-index: 1300">
            <div class="gs-panel">
                <div class="gs-input">
                    <svg viewBox="0 0 24 24">
                        <circle cx="11" cy="11" r="6.5" />
                        <path d="m16 16 4.5 4.5" />
                    </svg>
                    <input ref="input" v-model="q" placeholder="搜索任务 / 需求 / 缺陷 / MR / 迭代…" />
                    <span class="esc">ESC</span>
                    <button class="gs-close" @click="close">
                        <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg>
                    </button>
                </div>
                <div class="gs-body">
                    <div v-if="!q.trim()" class="gs-tip">输入关键词搜索，↑↓ 选择，回车跳转</div>
                    <div v-else-if="searching && !rows.length" class="gs-tip">搜索中…</div>
                    <div v-else-if="!rows.length" class="gs-tip">没有匹配的结果</div>
                    <template v-for="(r, i) in rows" :key="r.kind + r.id">
                        <div v-if="i === 0 || rows[i - 1].kind !== r.kind" class="gs-group">{{ r.kind }}</div>
                        <div class="gs-row" :class="{ sel: i === sel }" @mouseenter="sel = i" @click="go(r)">
                            <span class="kind" :class="kindCls[r.kind]">{{ r.kind }}</span>
                            <span class="rid">{{ r.id }}</span>
                            <span class="title">{{ r.title }}</span>
                            <span class="st">{{ r.status }}</span>
                        </div>
                    </template>
                </div>
                <div class="gs-foot">清流全局搜索 · 结果按类型分组排序</div>
            </div>
        </div>
    </Teleport>
</template>

<style scoped>
.gs-overlay {
    position: fixed;
    inset: 0;
    background: var(--overlay);
    backdrop-filter: blur(3px);
    display: flex;
    align-items: flex-start;
    justify-content: center;
    padding: 96px 24px 24px;
}
.gs-panel {
    width: 600px;
    max-width: 100%;
    max-height: calc(100vh - 140px);
    display: flex;
    flex-direction: column;
    background: var(--card);
    border-radius: var(--radius-xl);
    box-shadow: var(--shadow-lg);
    animation: pop 0.18s var(--ease) both;
    overflow: hidden;
}
@keyframes pop {
    from {
        opacity: 0;
        transform: translateY(-8px) scale(0.99);
    }
    to {
        opacity: 1;
        transform: none;
    }
}
.gs-input {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 14px 16px;
    border-bottom: 1px solid var(--border);
}
.gs-input svg {
    width: 16px;
    height: 16px;
    stroke: var(--t3);
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
    flex: none;
}
.gs-input input {
    flex: 1;
    border: none;
    outline: none;
    font-size:15px;
    color: var(--t1);
    background: none;
    font-family: inherit;
}
.gs-input input::placeholder {
    color: var(--t3);
}
.esc {
    font-size:12px;
    color: var(--t3);
    border: 1px solid var(--border);
    border-radius: 5px;
    padding: 2px 6px;
}
.gs-close {
    width: 24px;
    height: 24px;
    flex: none;
    border: none;
    background: none;
    border-radius: 6px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--t3);
}
.gs-close svg {
    width: 13px;
    height: 13px;
    stroke: currentColor;
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
}
.gs-body {
    overflow: auto;
    flex: 1;
}
.gs-tip {
    padding: 28px 16px;
    text-align: center;
    font-size:13px;
    color: var(--t3);
}
.gs-group {
    padding: 7px 16px 5px;
    background: var(--bg);
    border-bottom: 1px solid var(--border);
    font-size:12px;
    font-weight:400;
    color: var(--t3);
}
.gs-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 11px 16px;
    cursor: pointer;
    border-bottom: 1px solid var(--border);
}
.gs-row.sel,
.gs-row.sel .st {
    background: var(--primary-bg);
}
.gs-row.sel .title {
    color: var(--primary);
}
.kind {
    flex: none;
    font-size:12px;
    padding: 2px 7px;
    border-radius: 5px;
    font-weight:400;
    background: var(--track);
    color: var(--t2);
}
.kind.bl {
    background: var(--primary-bg);
    color: var(--primary);
}
.kind.gn {
    background: var(--green-bg);
    color: var(--green);
}
.kind.rd {
    background: var(--red-bg);
    color: var(--red);
}
.kind.pu {
    background: var(--purple-bg);
    color: var(--purple);
}
.kind.am {
    background: var(--amber-bg);
    color: var(--amber);
}
.rid {
    flex: none;
    font-size: 12px;
    color: var(--t3);
    font-variant-numeric: tabular-nums;
    width: 64px;
}
.title {
    flex: 1;
    min-width: 0;
    font-size: 13px;
    color: var(--t1);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.st {
    flex: none;
    font-size:12px;
    color: var(--t3);
}
.gs-foot {
    padding: 10px 16px;
    border-top: 1px solid var(--border);
    font-size:12px;
    color: var(--t3);
}
</style>
