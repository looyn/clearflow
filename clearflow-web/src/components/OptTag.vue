<!-- 选项标签：文案与配色取自「项目设置 → 选项配置」（优先级 / 严重度 / 任务类型），
     设置里已被删除的 key 仍按 key 兜底展示，历史数据不会变成空白 -->
<script setup>
import { computed } from 'vue';
import { useOptionsStore } from '../stores/options';

const props = defineProps({
    kind: { type: String, default: 'priorities' }, // priorities | severities | taskTypes
    base: { type: String, default: 'prio' }, // 沿用的布局 class：prio / sev / tag
    value: { type: [String, Number], default: '' }
});
const opts = useOptionsStore();

// 历史 class 兜底（设置里没配 color 时保持原有观感）
const SEV_CLS = { 致命: 'fatal', 严重: 'major', 一般: 'minor', 轻微: 'trivial' };
const FALLBACK_KEY = { p0: '#F54A45', p1: '#FF7D00' };

const item = computed(() => {
    if (props.value === '' || props.value == null) return null;
    if (props.kind === 'severities') return opts.severity(props.value);
    if (props.kind === 'taskTypes') return opts.taskType(props.value);
    return opts.prio(props.value);
});
const text = computed(() => item.value?.name || '');
const legacy = computed(() => {
    if (props.kind === 'severities') return SEV_CLS[props.value] || '';
    return String(props.value || ''); // p0/p1/p2、fe/be/qa/de
});
const style = computed(() => {
    const color = item.value?.color || FALLBACK_KEY[String(props.value)] || null;
    if (!color) return { background: 'var(--track)', color: 'var(--t3)' };
    return { background: tint(color, 0.14), color };
});

function tint(hex, a) {
    const m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return `rgba(51, 112, 255, ${a})`;
    const n = parseInt(m[1], 16);
    return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}
</script>

<template>
    <span :class="[base, legacy]" :style="style">{{ text }}</span>
</template>

<style scoped>
span {
    white-space: nowrap;
}
</style>
