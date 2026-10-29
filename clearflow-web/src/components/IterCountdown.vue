<script setup>
// 页头迭代倒计时：进行中迭代的 code + 距发布剩余天数（无迭代或无法推算日期时退化为状态文案）
import { useIterationsStore } from '../stores/iterations';
const iters = useIterationsStore();
</script>

<template>
    <div v-if="iters.active" class="countdown">
        {{ iters.active.code }} ·
        <template v-if="iters.daysLeft !== null">距发布还有 <b>{{ iters.daysLeft }} 天</b></template>
        <template v-else>{{ iters.active.stateLabel }}</template>
    </div>
</template>
