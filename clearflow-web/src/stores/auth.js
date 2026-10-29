import { defineStore } from 'pinia';

// 登录态：token + user 双持久化（request.js 请求层直接读 'clearflow_token'）
const TOKEN_KEY = 'clearflow_token';
const USER_KEY = 'clearflow_user';

export const useAuthStore = defineStore('auth', {
    state: () => ({
        token: localStorage.getItem(TOKEN_KEY) || '',
        user: JSON.parse(localStorage.getItem(USER_KEY) || 'null') // MemberView 形状
    }),
    getters: {
        logged: (s) => !!s.token
    },
    actions: {
        // 登录成功时调用：写入 token 与用户信息
        setSession(token, user) {
            this.token = token;
            localStorage.setItem(TOKEN_KEY, token);
            this.user = user || null;
            localStorage.setItem(USER_KEY, JSON.stringify(user || null));
        },
        logout() {
            this.token = '';
            this.user = null;
            localStorage.removeItem(TOKEN_KEY);
            localStorage.removeItem(USER_KEY);
            localStorage.removeItem('clearflow_logged'); // 清理旧版登录标记
        }
    }
});
