import { ref } from 'vue';

/**
 * 看板列窗口状态：每列只持有已加载的卡片，滚动到底按 offset 续取。
 * load(limit) 拉全部列首页，loadColumn(key, offset, limit) 续取单列。
 */
export function useBoardColumns(load, loadColumn, { size = 40 } = {}) {
    const columns = ref([]);
    const loadingAll = ref(false);

    const colOf = (key) => columns.value.find((c) => c.key === key);

    const wrap = (c) => ({
        key: c.key,
        name: c.name || c.key,
        total: c.total || 0,
        items: c.items || [],
        hasMore: !!c.hasMore,
        loading: false,
    });

    /** 已加载最多的一列决定刷新窗口：刷新后列不会突然变短 */
    const refreshLimit = () => Math.min(200, Math.max(size, ...columns.value.map((c) => c.items.length)));

    async function reload(limit = size) {
        loadingAll.value = true;
        try {
            const payload = await load(limit);
            columns.value = (payload?.columns || []).map(wrap);
        } finally {
            loadingAll.value = false;
        }
    }

    async function refresh() {
        await reload(refreshLimit());
    }

    async function loadMore(key) {
        const col = colOf(key);
        if (!col || !col.hasMore || col.loading) return;
        col.loading = true;
        try {
            const r = await loadColumn(key, col.items.length, Math.max(size, 40));
            const seen = new Set(col.items.map((x) => x.id));
            col.items = col.items.concat((r.items || []).filter((x) => !seen.has(x.id)));
            col.total = r.total ?? col.total;
            col.hasMore = !!r.hasMore;
        } finally {
            col.loading = false;
        }
    }

    /** 拖拽的即时反馈：先在本地把卡片挪到目标列的落点，服务端返回后由 refresh 校正 */
    function moveLocal(id, toKey, anchorId, after) {
        let moved = null;
        columns.value.forEach((c) => {
            const i = c.items.findIndex((x) => x.id === id);
            if (i >= 0) moved = c.items.splice(i, 1)[0];
        });
        if (!moved) return null;
        const to = colOf(toKey);
        if (!to) return moved;
        moved.status = toKey;
        const at = anchorId ? to.items.findIndex((x) => x.id === anchorId) : -1;
        if (at < 0) to.items.push(moved);
        else to.items.splice(after ? at + 1 : at, 0, moved);
        return moved;
    }

    /** 卡片本体查找（含所有已加载列） */
    function find(id) {
        for (const c of columns.value) {
            const t = c.items.find((x) => x.id === id);
            if (t) return t;
        }
        return null;
    }

    const totalCount = () => columns.value.reduce((n, c) => n + c.total, 0);

    return { columns, loadingAll, reload, refresh, loadMore, moveLocal, find, colOf, totalCount };
}
