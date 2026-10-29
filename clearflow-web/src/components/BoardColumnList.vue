<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue';

// 看板列内的测量式虚拟滚动：只渲染视口 ± overscan 张卡片，滚到底自动续取下一窗
const props = defineProps({
    items: { type: Array, default: () => [] },
    total: { type: Number, default: 0 },
    hasMore: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
    estimate: { type: Number, default: 116 },
    overscan: { type: Number, default: 3 },
    loadAhead: { type: Number, default: 260 },
});
const emit = defineEmits(['load-more']);

const scroller = ref(null);
const scrollTop = ref(0);
const viewH = ref(0);
const heights = new Map();
const measureTick = ref(0);

const keyOf = (it, i) => it?.id ?? i;

const offsets = computed(() => {
    measureTick.value;
    const n = props.items.length;
    const off = new Array(n + 1);
    off[0] = 0;
    for (let i = 0; i < n; i++) {
        off[i + 1] = off[i] + (heights.get(keyOf(props.items[i], i)) ?? props.estimate);
    }
    return off;
});

// 首个与可视区相交的卡片、最后一个可视区下边界之后的卡片索引（半开区间）
const lower = (off, v) => {
    let lo = 0;
    let hi = off.length - 1;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (off[mid + 1] <= v) lo = mid + 1;
        else hi = mid;
    }
    return lo;
};
const upper = (off, v) => {
    let lo = 0;
    let hi = off.length - 1;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (off[mid] < v) lo = mid + 1;
        else hi = mid;
    }
    return lo;
};

const range = computed(() => {
    const off = offsets.value;
    const n = props.items.length;
    if (!n) return [0, 0];
    const top = Math.max(0, scrollTop.value - props.loadAhead);
    const bottom = scrollTop.value + (viewH.value || 0) + props.loadAhead;
    const start = Math.max(0, lower(off, top) - props.overscan);
    const end = Math.min(n, upper(off, bottom) + props.overscan);
    return [start, Math.max(start, end)];
});

const visible = computed(() => props.items.slice(range.value[0], range.value[1]));
const topPad = computed(() => offsets.value[range.value[0]]);
const bottomPad = computed(() => offsets.value[props.items.length] - offsets.value[range.value[1]]);
const remaining = computed(() => Math.max(0, props.total - props.items.length));

let ro = null;
let sizeRo = null;
const observeItems = () => {
    if (!ro || !scroller.value) return;
    ro.disconnect();
    scroller.value.querySelectorAll('.vitem').forEach((el) => ro.observe(el));
};

const measure = (entries) => {
    let changed = false;
    for (const e of entries) {
        const key = e.target.dataset.key;
        if (!key) continue;
        const h = e.borderBoxSize?.[0]?.blockSize ?? e.target.offsetHeight;
        if (h && Math.abs((heights.get(key) ?? 0) - h) > 0.5) {
            heights.set(key, h);
            changed = true;
        }
    }
    if (changed) measureTick.value++;
};

function onScroll() {
    const el = scroller.value;
    if (!el) return;
    scrollTop.value = el.scrollTop;
    if (props.hasMore && !props.loading && el.scrollHeight - el.scrollTop - el.clientHeight < props.loadAhead) {
        emit('load-more');
    }
}

onMounted(() => {
    ro = new ResizeObserver(measure);
    viewH.value = scroller.value?.clientHeight ?? 0;
    new ResizeObserver(() => {
        viewH.value = scroller.value?.clientHeight ?? 0;
        onScroll();
    }).observe(scroller.value);
    nextTick(observeItems);
});
onBeforeUnmount(() => ro?.disconnect());

// 卡片增删/整列重载后重新登记测量目标
watch(() => [range.value[0], range.value[1], props.items.length], () => nextTick(observeItems));
</script>

<template>
    <div ref="scroller" class="vcol" @scroll.passive="onScroll">
        <div v-if="topPad > 0" class="vpad" :style="{ height: topPad + 'px' }"></div>
        <div v-for="(it, i) in visible" :key="keyOf(it, range[0] + i)" class="vitem" :data-key="keyOf(it, range[0] + i)">
            <slot name="item" :item="it" :index="range[0] + i" />
        </div>
        <div v-if="bottomPad > 0" class="vpad" :style="{ height: bottomPad + 'px' }"></div>
        <slot v-if="!items.length" name="empty" />
        <div v-else-if="hasMore || loading" class="vfoot">
            <span v-if="loading" class="tip">加载中…</span>
            <button v-else class="more" type="button" @click="emit('load-more')">
                加载更多 · 已显示 {{ items.length }} / {{ total }}
            </button>
        </div>
    </div>
</template>

<style scoped>
.vcol {
    min-height: 260px;
    max-height: min(72vh, 760px);
    overflow-y: auto;
    overflow-x: hidden;
    overscroll-behavior: contain;
    scrollbar-width: thin;
}
.vitem {
    padding-bottom: 8px;
}
.vfoot {
    padding: 4px 0 8px;
    text-align: center;
}
.vfoot .more {
    width: 100%;
    border: 1px dashed var(--border);
    border-radius: var(--radius-md);
    background: transparent;
    color: var(--t2);
    font-size: 12px;
    font-family: inherit;
    line-height: 26px;
    cursor: pointer;
    transition: background 0.15s var(--ease), color 0.15s var(--ease);
}
.vfoot .more:hover {
    background: var(--card);
    color: var(--primary);
}
.vfoot .tip {
    font-size: 12px;
    color: var(--t3);
}
</style>
