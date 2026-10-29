// ===== 请求层：真实后端（Vite 代理 /api → http://localhost:8080）=====
import { useUiStore } from '../stores/ui';

const BASE = '/api';
const TOKEN_KEY = 'clearflow_token';

const authHeaders = (extra) => {
    const token = localStorage.getItem(TOKEN_KEY);
    return token ? { Authorization: `Bearer ${token}`, ...extra } : { ...extra };
};

// 401：清登录态并携带原路径跳登录页（整页跳转，store 随页面重载重置）
function redirectLogin() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem('clearflow_user');
    const redirect = encodeURIComponent(location.pathname + location.search);
    location.href = `/login?redirect=${redirect}`;
}

// 解析响应体：200 空响应体 → null（如「当前项目为空」场景，res.json() 会抛错）
async function parseBody(res) {
    const text = await res.text();
    if (!text) return null;
    try {
        return JSON.parse(text);
    } catch {
        return text;
    }
}

async function throwByStatus(res, data) {
    if (res.status === 401) {
        redirectLogin();
        throw new Error(data?.message || '未登录或登录已过期');
    }
    if (res.status === 403) throw new Error(data?.message || '无权限执行该操作');
    throw new Error(data?.message || `HTTP ${res.status}`);
}

export async function request(path, { method = 'GET', body } = {}) {
    const res = await fetch(BASE + path, {
        method,
        headers: authHeaders(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    const data = await parseBody(res);
    if (!res.ok) await throwByStatus(res, data);
    // 写操作成功后广播刷新，触发各视图重查
    if (method !== 'GET') useUiStore().notifyRefresh();
    return data;
}

// multipart 上传：不手动设 Content-Type（由浏览器生成 boundary），仍带 Bearer 头
export async function upload(path, formData) {
    const res = await fetch(BASE + path, { method: 'POST', headers: authHeaders(), body: formData });
    const data = await parseBody(res);
    if (!res.ok) await throwByStatus(res, data);
    return data;
}

// 二进制下载（如 PDF）：失败时尽量取出后端 JSON 里的 message
export async function requestBlob(path) {
    const res = await fetch(BASE + path, { headers: authHeaders() });
    if (!res.ok) {
        const text = await res.text().catch(() => '');
        let data = null;
        try {
            data = text ? JSON.parse(text) : null;
        } catch {
            data = text ? { message: text.slice(0, 200) } : null;
        }
        await throwByStatus(res, data);
    }
    return res.blob();
}
