// SVG 图表按 viewBox 等比拉伸时，线宽、点半径会跟着容器一起放大（2.6 倍宽 = 2.6 倍粗线）。
// 这里把容器实际像素宽回写给视图，视图用它生成同宽的 viewBox，做到 1 用户单位 = 1 CSS px，
// 线宽/点径/字号即写成设计值，不需要任何缩放补偿。
export const chartSize = {
    mounted(el, binding) {
        const apply = () => {
            const w = Math.round(el.clientWidth);
            // 隐藏态（v-if、未展开的 tab）宽度为 0；宽度没变则不回调，避免与重渲染互相触发
            if (w > 0 && w !== el._chartW) {
                el._chartW = w;
                binding.value?.(w);
            }
        };
        apply();
        el._chartRo = new ResizeObserver(apply);
        el._chartRo.observe(el);
    },
    unmounted(el) {
        el._chartRo?.disconnect();
        el._chartRo = null;
        el._chartW = 0;
    }
};
