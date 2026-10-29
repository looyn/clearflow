// ===== API 模块：按域拆分，全部走 request（真实后端，Vite 代理 /api → http://localhost:8080）=====
import { request, upload, requestBlob } from './request';

// 看板/列表通用筛选参数（空值不发送，避免后端把 '' 当条件）
const qs = (f = {}, extra = {}) => {
    const p = new URLSearchParams();
    for (const k of ['q', 'keyword', 'status', 'state', 'prio', 'assignee', 'owner', 'iter', 'module',
        'category', 'role', 'scope', 'sort', 'limit']) {
        const v = f[k];
        if (v !== undefined && v !== null && v !== '') p.set(k, v);
    }
    for (const [k, v] of Object.entries(extra)) p.set(k, v);
    return p.toString();
};

// 分页请求：page/pageSize 统一拼接（自带 ?，筛选为空时也不会出现 ?&），返回后端 PageResult{list,total,page,pageSize}
const pageQs = (f, page, pageSize) => {
    const p = new URLSearchParams(qs(f));
    p.set('page', page);
    p.set('pageSize', pageSize);
    return `?${p}`;
};

export const api = {
    // ---- 登录 ----
    login: (data) => request('/auth/login', { method: 'POST', body: data }),
    // 个人信息：当前登录人详情（含 login 登录账号）
    getMe: () => request('/auth/me'),
    // 修改本人密码（校验原密码；管理员重置他人密码走 resetMemberPassword）
    changePassword: (oldPassword, newPassword) =>
        request('/auth/me/password', {
            method: 'PUT',
            body: { oldPassword, newPassword }
        }),

    // ---- 权限矩阵（当前项目的成员 × 模块；GET /perm/matrix?projectId=）----
    getPermMatrix: (projectId) => request(`/perm/matrix?projectId=${projectId}`),
    // 当前登录人权限画像（菜单过滤/按钮显隐）：{ perm, isAdmin, levels, ownedProjectIds }
    getPermMe: () => request('/perm/me'),

    // ---- 概览 ----
    getOverview: () => request('/overview'),
    // 全量看板（供我的工作/需求池/发版计划等聚合页取候选，看板视图不再使用）
    getBoard: () => request('/board'),
    // 看板列窗口：每列只返回首页 limit 张 + 真实列计数（列内滚动到底再续取）
    getBoardColumns: (f, limit = 40) => request(`/board/columns?${qs(f, { limit })}`),
    getBoardColumn: (status, offset, f, limit = 40) =>
        request(`/board/column?${qs(f, { limit })}&status=${encodeURIComponent(status)}&offset=${offset}`),

    // ---- 业务模块字典（项目级，与权限模块 key 无关）----
    getModules: () => request('/modules'),
    createModule: (data) => request('/modules', { method: 'POST', body: data }),
    updateModule: (id, data) => request(`/modules/${id}`, { method: 'PUT', body: data }),
    deleteModule: (id) => request(`/modules/${id}`, { method: 'DELETE' }),

    // ---- 项目 ----
    // 顶部切换器 / 权限矩阵用：可见项目全量（精简字段，一次取全）
    getProjects: () => request('/projects/options'),
    // 项目表格：服务端状态/负责人/关键词筛选 + 分页
    getProjectsPage: (f, page = 1, pageSize = 10) => request(`/projects${pageQs(f, page, pageSize)}`),
    getProjectStats: () => request('/projects/stats'),
    // 项目成员明细（管理员可见邮箱）与可添加候选（服务端搜索 + 分页）
    getProjectMembers: (id) => request(`/projects/${id}/members`),
    getProjectMemberCandidates: (id, q = '', page = 1, pageSize = 10) =>
        request(`/projects/${id}/member-candidates${pageQs({ q }, page, pageSize)}`),
    createProject: (form) => request('/projects', { method: 'POST', body: form }),
    updateProject: (id, patch) => request(`/projects/${id}`, { method: 'PUT', body: patch }),
    archiveProject: (id) => request(`/projects/${id}/archive`, { method: 'PUT' }),
    deleteProject: (id) => request(`/projects/${id}`, { method: 'DELETE' }),
    setCurrentProject: (id) => request('/projects/current', { method: 'PUT', body: { id } }),
    getCurrentProject: () => request('/projects/current'),

    // ---- 设置 ----
    // 成员级个人通知开关（member_setting，每人一份；后端按登录用户隔离）
    getSettings: () => request('/settings'),
    saveSettings: (section, patch) => request(`/settings/${section}`, { method: 'PUT', body: patch }),
    // 项目级设置（workflow / options，每项目独立）
    getProjectSettings: (projectId) => request(`/projects/${projectId}/settings`),
    saveProjectSettings: (projectId, section, patch) =>
        request(`/projects/${projectId}/settings/${section}`, { method: 'PUT', body: patch }),

    // ---- 任务 ----
    // 服务端筛选 + 排序 + 分页（看板列窗口见 getBoardColumns）
    getTasks: (f, page = 1, pageSize = 20) => request(`/tasks${pageQs(f, page, pageSize)}`),
    getTask: (id) => request(`/tasks/${id}`),
    createTask: (data) => request('/tasks', { method: 'POST', body: data }),
    updateTask: (id, patch) => request(`/tasks/${id}`, { method: 'PUT', body: patch }),
    deleteTask: (id) => request(`/tasks/${id}`, { method: 'DELETE' }),
    addSubtask: (tid, title) => request(`/tasks/${tid}/subtasks`, { method: 'POST', body: { title } }),
    toggleSubtask: (tid, sid) => request(`/tasks/${tid}/subtasks/${sid}`, { method: 'PUT' }),
    removeSubtask: (tid, sid) => request(`/tasks/${tid}/subtasks/${sid}`, { method: 'DELETE' }),

    // ---- 迭代 ----
    // 迭代精简列表（下拉 / 名称映射 / 活跃迭代，全站共享）
    getIterations: () => request('/iterations/timeline'),
    // 迭代容量 / 统计 / 燃尽卡片（服务端聚合，列表数据走 getIterationsPage）
    getIterationCards: () => request('/iterations'),
    // 迭代表分页：scope = current（未完成）/ released（终态）/ all
    getIterationsPage: (scope = 'current', page = 1, pageSize = 10) =>
        request(`/iterations/page?scope=${encodeURIComponent(scope)}&page=${page}&pageSize=${pageSize}`),
    // 甘特时间轴：全部迭代一次取全（轻量字段，不含复盘正文）
    getIterationTimeline: () => request('/iterations/timeline'),
    // 迭代详情（含复盘，结构化复盘为对象、历史纯文本为字符串）
    getIterationDetail: (code) => request(`/iterations/${encodeURIComponent(code)}`),
    createIteration: (data) => request('/iterations', { method: 'POST', body: data }),
    saveRetro: (code, content) =>
        request(`/iterations/${code}/retro`, {
            method: 'PUT',
            // 复盘为结构化对象时序列化（后端按文本接收，读取端兼容字符串/对象两种形态）
            body: { content: typeof content === 'string' ? content : JSON.stringify(content) }
        }),
    updateIteration: (code, patch) => request(`/iterations/${code}`, { method: 'PUT', body: patch }),
    planRequirements: (ids, iter) => request('/iterations/plan', { method: 'POST', body: { ids, iter } }),

    // ---- 需求 ----
    // 列表：服务端筛选（状态/优先级/负责人/迭代/模块/关键词）+ 排序 + 分页；行内不含描述正文
    getRequirements: (f, page = 1, pageSize = 10) => request(`/requirements${pageQs(f, page, pageSize)}`),
    getRequirement: (id) => request(`/requirements/${id}`),
    // 统计条与状态下拉计数（一次 SQL 聚合；iter 传入活跃迭代编码时附带该迭代内的分布与点数）
    getRequirementStats: (iter) => request(`/requirements/stats${iter ? `?iter=${encodeURIComponent(iter)}` : ''}`),
    // 追踪链：本需求关联的任务 / 缺陷
    getRequirementLinks: (id) => request(`/requirements/${id}/links`),
    // 迭代规划弹窗候选：未排入该迭代且未到终态的需求（服务端搜索 + 分页）
    getRequirementPlanPool: (iter, q = '', page = 1, pageSize = 10) =>
        request(`/requirements/plan-pool${pageQs({ q, iter }, page, pageSize)}`),
    // 需求池 / 任务看板等下拉用的候选（服务端搜索 + 分页）
    getRequirementOptions: (q = '', page = 1, pageSize = 10) =>
        request(`/requirements${pageQs({ q }, page, pageSize)}`),
    createRequirement: (data) => request('/requirements', { method: 'POST', body: data }),
    updateRequirement: (id, patch) => request(`/requirements/${id}`, { method: 'PUT', body: patch }),
    deleteRequirement: (id) => request(`/requirements/${id}`, { method: 'DELETE' }),
    setRequirementStatus: (id, status) => request(`/requirements/${id}/status`, { method: 'PUT', body: { status } }),
    toggleAcceptance: (reqId, acId) => request(`/requirements/${reqId}/acceptance/${acId}`, { method: 'PUT' }),
    // AI 拆分任务：只返回草稿 { tasks, mock, model }，创建仍逐条走 createTask
    splitRequirementTasks: (id) => request(`/ai/requirements/${id}/split-tasks`, { method: 'POST' }),
    // 导出全量需求（按当前筛选，后端生成 CSV，含 UTF-8 BOM）
    exportRequirements: (f) => requestBlob(`/requirements/export?${qs(f)}`),

    // ---- 缺陷 ----
    // 我的工作等聚合页仍按前 200 条取全量（缺陷列表分页本轮未改造）
    getDefects: () => request('/defects?pageSize=200').then((r) => r.list || []),
    // 看板列窗口 / 列内续取
    getDefectBoardColumns: (f, limit = 40) => request(`/defects/board?${qs(f, { limit })}`),
    getDefectBoardColumn: (status, offset, f, limit = 40) =>
        request(`/defects/board/column?${qs(f, { limit })}&status=${encodeURIComponent(status)}&offset=${offset}`),
    getDefectDetail: (id) => request(`/defects/${id}`),
    // 列表分页（服务端筛选/排序）与全量导出
    getDefectsPage: (f, page = 1, pageSize = 20) =>
        request(`/defects${pageQs(f, page, pageSize)}`),
    exportDefects: (f) => requestBlob(`/defects/export?${qs(f)}`),
    getDefectStats: () => request('/defects/stats'),
    getDefectTrend: () => request('/defects/trend'),
    createDefect: (data) => request('/defects', { method: 'POST', body: data }),
    updateDefect: (id, patch) => request(`/defects/${id}`, { method: 'PUT', body: patch }),
    deleteDefect: (id) => request(`/defects/${id}`, { method: 'DELETE' }),
    addVerifyLog: (bid, { result, note }) =>
        request(`/defects/${bid}/verify`, { method: 'POST', body: { result, note } }),
    addRelate: (bid, { type, target }) =>
        request(`/defects/${bid}/relates`, { method: 'POST', body: { type, target } }),
    removeRelate: (bid, relateId) => request(`/defects/${bid}/relates/${relateId}`, { method: 'DELETE' }),

    // ---- 发版计划 ----
    // 列表：服务端状态/负责人/版本号筛选 + 分页；行内不含发版说明正文
    getReleases: (f, page = 1, pageSize = 10) => request(`/releases${pageQs(f, page, pageSize)}`),
    getRelease: (id) => request(`/releases/${id}`),
    // 已关联的发布内容（需求/缺陷标题与处理人，按关联 id 批量取）
    getReleaseLinked: (id) => request(`/releases/${id}/linked`),
    // 关联候选（服务端搜索 + 分页，linked 标记已关联项）
    getReleaseReqOptions: (id, q = '', page = 1, pageSize = 10) =>
        request(`/releases/${id}/req-options${pageQs({ q }, page, pageSize)}`),
    getReleaseDefectOptions: (id, q = '', page = 1, pageSize = 10) =>
        request(`/releases/${id}/defect-options${pageQs({ q }, page, pageSize)}`),
    // 通知邮件内容（后端解析收件人邮箱，仅生成不发送）
    getReleaseMail: (id) => request(`/releases/${id}/mail`),
    getReleaseStats: () => request('/releases/stats'),
    createRelease: (data) => request('/releases', { method: 'POST', body: data }),
    updateRelease: (id, patch) => request(`/releases/${id}`, { method: 'PUT', body: patch }),
    deleteRelease: (id) => request(`/releases/${id}`, { method: 'DELETE' }),

    // ---- 文档 ----
    // 列表：服务端分类/更新人/标题正文关键词筛选 + 分页；行内不含正文
    getDocs: (f, page = 1, pageSize = 10) => request(`/docs${pageQs(f, page, pageSize)}`),
    // 单篇正文（阅读、编辑与下载 .md 都按 id 取）
    getDoc: (id) => request(`/docs/${id}`),
    // 分类文档数（一次 GROUP BY）
    getDocStats: () => request('/docs/stats'),
    getDocCategories: () => request('/docs/categories'),
    addDocCategory: (name) => request('/docs/categories', { method: 'POST', body: { name } }),
    // 重命名分类：字典与该分类下所有文档同步更新；新名与已有分类重复时做合并
    updateDocCategory: (oldName, newName) =>
        request('/docs/categories', { method: 'PUT', body: { oldName, newName } }),
    // 删除分类：分类下仍有文档时后端拒绝
    deleteDocCategory: (name) => request('/docs/categories', { method: 'DELETE', body: { name } }),
    createDoc: (data) => request('/docs', { method: 'POST', body: data }),
    updateDoc: (id, patch) => request(`/docs/${id}`, { method: 'PUT', body: patch }),
    deleteDoc: (id) => request(`/docs/${id}`, { method: 'DELETE' }),
    // 导出 PDF：后端生成（首页目录带页码 + 书签），返回二进制文件
    getDocPdf: (id) => requestBlob(`/docs/${id}/pdf`),

    // ---- 我的工作 ----
    getMyWork: () => request('/mywork'),

    // ---- 度量 ----
    getMetrics: () => request('/metrics'),
    // 模块维度质量/产出：仅项目负责人；iter 为空表示不筛选迭代
    getModuleStats: (iter) => request(`/stats/modules${iter ? `?iter=${encodeURIComponent(iter)}` : ''}`),
    // 成员维度负载/质量：仅项目负责人；引入缺陷仅供复盘，不做排名
    getPeopleStats: (iter) => request(`/stats/people${iter ? `?iter=${encodeURIComponent(iter)}` : ''}`),

    // ---- 工时 ----
    // 汇总 + 明细分页：records 为 PageResult{list,total,page,pageSize}，heat 为服务端聚合的近 14 天矩阵
    getWorklog: ({ page = 1, pageSize = 10 } = {}) =>
        request(`/worklog?page=${page}&pageSize=${pageSize}`),
    logWork: (data) => request('/worklog', { method: 'POST', body: data }),
    // 周填报：同一关联对象按天批量登记
    logWorkBatch: (data) => request('/worklog/batch', { method: 'POST', body: data }),
    updateWorklog: (id, data) => request(`/worklog/${id}`, { method: 'PUT', body: data }),
    deleteWorklog: (id) => request(`/worklog/${id}`, { method: 'DELETE' }),
    // 导出全量工时明细（后端生成 CSV，含 UTF-8 BOM）
    exportWorklog: () => requestBlob('/worklog/export'),

    // ---- 成员 ----
    // 成员精简视图（全员可读：下拉 / 名称映射 / 项目成员候选；不含邮箱等管理字段）
    getMemberBriefs: () => request('/members/brief'),
    // 成员池分页（仅系统管理员：成员管理页；keyword 命中姓名/邮箱/角色）
    getMembers: (f, page = 1, pageSize = 10) => request(`/members${pageQs(f, page, pageSize)}`),
    // 成员池概览：总人数 + 角色候选（SQL 聚合，不依赖全量列表）
    getMemberStats: () => request('/members/stats'),
    inviteMember: (data) => request('/members', { method: 'POST', body: data }),
    updateMember: (id, patch) => request(`/members/${id}`, { method: 'PUT', body: patch }),
    // 重置成员密码（管理员操作，动态里不记录密码内容）
    resetMemberPassword: (id, password) =>
        request(`/members/${id}/password`, { method: 'PUT', body: { password } }),
    removeMember: (id) => request(`/members/${id}`, { method: 'DELETE' }),
    updatePerm: (projectId, memberId, moduleKey, level) =>
        request(`/perm/${memberId}?projectId=${projectId}`, { method: 'PUT', body: { moduleKey, level } }),

    // ---- 评论 ----
    getComments: (kind, eid) => request(`/comments?kind=${kind}&id=${eid}`),
    addComment: (kind, eid, text) => request('/comments', { method: 'POST', body: { kind, eid, text } }),
    deleteComment: (id) => request(`/comments/${id}`, { method: 'DELETE' }),

    // ---- 通知 ----
    // 分页拉取：{ list, total, page, pageSize }；list 项含 createdAt（ISO）供相对时间与按天分组
    getNotifications: (page = 1, pageSize = 20) => request(`/notifications?page=${page}&pageSize=${pageSize}`),
    // 未读数（铃铛角标）
    getNotificationUnread: () => request('/notifications/unread-count'),
    // 单条已读 / 标为未读
    markNotificationRead: (id, read = true) => request(`/notifications/${id}/read?read=${read}`, { method: 'PUT' }),
    markAllNotifications: () => request('/notifications/read-all', { method: 'PUT' }),
    // 删除单条
    deleteNotification: (id) => request(`/notifications/${id}`, { method: 'DELETE' }),
    // 清空已读
    clearReadNotifications: () => request('/notifications/read', { method: 'DELETE' }),

    // ---- 实体扩展：附件 / 关注 ----
    addAttachment: (kind, id, meta) =>
        request(`/attachments?kind=${kind}&id=${id}`, { method: 'POST', body: meta }),
    // 附件保存：条目带原始 File 时先真实上传（RustFS）再登记元数据；否则仅登记（新建流程本地暂存项）
    saveAttachment: async (kind, id, it) => {
        if (!it || !it.file) {
            return api.addAttachment(kind, id, { name: it?.name || '', size: it?.size || 0, kind: it?.kind || 'file', url: it?.url || '' });
        }
        const fd = new FormData();
        fd.append('file', it.file);
        const r = await upload(`/files/upload?kind=${kind}&id=${id}`, fd);
        return api.addAttachment(kind, id, { name: r?.name || it.name, size: r?.size ?? it.size, kind: r?.kind || it.kind, url: r?.url || '', key: r?.key || '' });
    },
    // 富文本编辑器图片：上传 RustFS，返回正文可长期引用的稳定读取 URL
    uploadEditorImage: (file) => {
        const fd = new FormData();
        fd.append('file', file);
        return upload('/files/editor-image', fd);
    },
    removeAttachment: (kind, id, aid) =>
        request(`/attachments?kind=${kind}&id=${id}&aid=${aid}`, { method: 'DELETE' }),
    toggleWatch: (kind, id) => request(`/watch?kind=${kind}&id=${id}`, { method: 'PUT' }),

    // ---- 动态 ----
    getActivity: (kind, id) => request(`/activity?kind=${kind}&id=${id}`), // 升序（先发生的在前）
    getActivityFeed: (limit = 20) => request(`/activity/feed?limit=${limit}`),

    // ---- 批量操作 ----
    bulkUpdate: (kind, ids, patch) =>
        request('/bulk/update', { method: 'PUT', body: { kind, ids, patch } }),
    // 看板拖拽落位：只报被拖卡片、锚点卡片与方向，列内顺序由服务端按 sort 邻接计算
    // （列窗口模式下前端不持有整列 id，旧的整列回写接口已移除）
    moveCard: (kind, status, id, anchorId, after) =>
        request('/bulk/move', { method: 'PUT', body: { kind, status, id, anchorId, after } }),

    // ---- 全局搜索（⌘K）----
    search: (q) => request(`/search?q=${encodeURIComponent(q)}`)
};
