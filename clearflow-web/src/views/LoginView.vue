<script setup>
import { ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import { api } from '../api';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();

// 演示账号预填（内置系统管理员账号，登录走真实后端 /auth/login）
const account = ref('admin');
const password = ref('123456');
const err = ref('');
const busy = ref(false);

async function submit() {
    if (!account.value.trim()) {
        err.value = '请输入账号';
        return;
    }
    if (!password.value) {
        err.value = '请输入密码';
        return;
    }
    err.value = '';
    busy.value = true;
    try {
        const r = await api.login({ account: account.value.trim(), password: password.value });
        auth.setSession(r.token, r.user);
        router.push(route.query.redirect || '/overview');
    } catch (e) {
        err.value = e.message || '登录失败，请稍后重试';
    } finally {
        busy.value = false;
    }
}
</script>

<template>
    <div class="login">
        <!-- 左侧品牌区 -->
        <aside class="brand-pane">
            <div class="bp-glow g1"></div>
            <div class="bp-glow g2"></div>
            <div class="bp-grid"></div>
            <div class="bp-top">
                <img class="bp-logo" src="/logo-mark.png" alt="清流 ClearFlow" />
                <div class="bp-name">清流 ClearFlow</div>
            </div>
            <div class="bp-mid">
                <div class="bp-eyebrow">研发项目管理平台</div>
                <h2>让需求、任务、缺陷与发版<br />在一条流上运转</h2>
                <div class="bp-feats">
                    <div class="bp-feat">
                        <span class="feat-ico"><svg viewBox="0 0 24 24"><path d="M4 6h16M4 12h10M4 18h13" /></svg></span>
                        <div><b>全流程覆盖</b><span>需求池 → 迭代规划 → 任务看板 → 缺陷追踪</span></div>
                    </div>
                    <div class="bp-feat">
                        <span class="feat-ico"><svg viewBox="0 0 24 24"><path d="M12 3v18M5 10l7-7 7 7" /></svg></span>
                        <div><b>研发度量</b><span>迭代燃尽、模块质量与成员负载，交付与质量一目了然</span></div>
                    </div>
                    <div class="bp-feat">
                        <span class="feat-ico"><svg viewBox="0 0 24 24">
                            <path d="M6 21V4" />
                            <path d="M6 5h11l-2.2 3.5L17 12H6" />
                        </svg></span>
                        <div><b>发版计划</b><span>关联发布内容，一键生成发版通知</span></div>
                    </div>
                </div>
            </div>
            <div class="bp-foot">© 2026 清流 ClearFlow · 研发项目管理</div>
        </aside>

        <!-- 右侧表单区 -->
        <main class="form-pane">
            <form class="form" @submit.prevent="submit">
                <div class="form-mark">
                    <img class="fm-logo" src="/logo-mark.png" alt="清流 ClearFlow" />
                </div>
                <h1>登录</h1>
                <p class="sub">欢迎回来，请输入账号信息</p>

                <label class="lbl">账号</label>
                <input v-model="account" class="inp" type="text" placeholder="账号 / 邮箱" autocomplete="username" />

                <label class="lbl">密码</label>
                <input v-model="password" class="inp" :class="{ invalid: err }" type="password" placeholder="密码" autocomplete="current-password" />

                <div v-if="err" class="err">{{ err }}</div>

                <button class="btn-main" type="submit" :disabled="busy">{{ busy ? '登录中…' : '登 录' }}</button>

                <div class="hint">
                    <span class="hint-dot"></span>演示环境 · admin / 123456
                </div>
            </form>
        </main>
    </div>
</template>

<style scoped>
.login {
    display: flex;
    min-height: 100vh;
    background: var(--card);
}

/* ===== 左侧品牌区 ===== */
.brand-pane {
    position: relative;
    overflow: hidden;
    flex: 0 0 44%;
    max-width: 560px;
    background: var(--grad-brand);
    color: #fff;
    padding: 48px 56px;
    display: flex;
    flex-direction: column;
}
.brand-pane::after {
    content: '';
    position: absolute;
    inset: 0;
    background: linear-gradient(160deg, rgba(255, 255, 255, 0.1), rgba(0, 0, 0, 0.06));
    pointer-events: none;
}
.bp-glow {
    position: absolute;
    border-radius: 50%;
    filter: blur(60px);
    pointer-events: none;
}
.bp-glow.g1 {
    width: 320px;
    height: 320px;
    right: -110px;
    top: -90px;
    background: rgba(127, 166, 255, 0.65);
}
.bp-glow.g2 {
    width: 280px;
    height: 280px;
    left: -120px;
    bottom: -110px;
    background: rgba(93, 141, 255, 0.5);
}
.bp-grid {
    position: absolute;
    inset: 0;
    background-image: linear-gradient(rgba(255, 255, 255, 0.06) 1px, transparent 1px),
        linear-gradient(90deg, rgba(255, 255, 255, 0.06) 1px, transparent 1px);
    background-size: 32px 32px;
    mask-image: radial-gradient(circle at 30% 30%, #000 0%, transparent 75%);
    -webkit-mask-image: radial-gradient(circle at 30% 30%, #000 0%, transparent 75%);
    pointer-events: none;
}
.bp-top,
.bp-mid,
.bp-foot {
    position: relative;
    z-index: 1;
}
.bp-top {
    display: flex;
    align-items: center;
    gap: 12px;
}
.bp-logo {
    height: 34px;
    width: auto;
    flex: none;
}
.bp-name {
    font-size: 17px;
    font-weight:700;
    letter-spacing: 0.5px;
}
.bp-mid {
    margin: auto 0;
}
.bp-eyebrow {
    display: inline-block;
    font-size: 12px;
    font-weight:400;
    letter-spacing: 0.08em;
    padding: 4px 12px;
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.16);
    box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.2);
    margin-bottom: 20px;
}
.bp-mid h2 {
    font-size: 30px;
    line-height: 1.45;
    font-weight:700;
}
.bp-feats {
    margin-top: 40px;
    display: flex;
    flex-direction: column;
    gap: 22px;
}
.bp-feat {
    display: flex;
    align-items: flex-start;
    gap: 14px;
}
.feat-ico {
    flex: none;
    width: 34px;
    height: 34px;
    border-radius: 9px;
    background: rgba(255, 255, 255, 0.14);
    box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.16);
    display: flex;
    align-items: center;
    justify-content: center;
}
.feat-ico svg {
    width: 18px;
    height: 18px;
    stroke: #fff;
    stroke-width: 1.8;
    fill: none;
    stroke-linecap: round;
    stroke-linejoin: round;
}
.bp-feat b {
    display: block;
    font-size:15px;
    font-weight:700;
}
.bp-feat span {
    display: block;
    font-size: 13px;
    opacity: 0.78;
    margin-top: 3px;
}
.bp-foot {
    font-size: 12px;
    opacity: 0.62;
}

/* ===== 右侧表单区 ===== */
.form-pane {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 32px;
}
.form {
    width: 100%;
    max-width: 360px;
    animation: rise 0.5s var(--ease) both;
}
.form-mark {
    margin-bottom: 22px;
}
.fm-logo {
    height: 40px;
    width: auto;
    display: block;
}
h1 {
    font-size: 26px;
    font-weight:700;
}
.sub {
    font-size:14px;
    color: var(--t3);
    margin-top: 8px;
    margin-bottom: 32px;
}
.lbl {
    display: block;
    font-size: 13px;
    color: var(--t2);
    font-weight:400;
    margin-bottom: 8px;
}
.inp {
    width: 100%;
    height: 44px;
    padding: 0 14px;
    font-size: 14px;
    color: var(--t1);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--card);
    outline: none;
    font-family: inherit;
    margin-bottom: 18px;
    transition: border-color 0.15s var(--ease), box-shadow 0.15s var(--ease);
}
.inp::placeholder {
    color: var(--placeholder);
}
.inp:hover {
    border-color: var(--border-strong);
}
.inp:focus {
    border-color: var(--primary);
    box-shadow: var(--ring);
}
.inp.invalid {
    border-color: var(--red);
    box-shadow: 0 0 0 3px rgba(245, 74, 69, 0.12);
}
.err {
    font-size:13px;
    color: var(--red);
    margin: -10px 0 14px;
}
.btn-main {
    width: 100%;
    height: 46px;
    border: none;
    border-radius: var(--radius-md);
    background: var(--grad-primary);
    color: #fff;
    font-size: 15px;
    font-weight:400;
    cursor: pointer;
    font-family: inherit;
    letter-spacing: 4px;
    box-shadow: var(--shadow-primary);
    transition: filter 0.15s var(--ease), box-shadow 0.2s var(--ease), transform 0.12s var(--ease);
}
.btn-main:hover {
    filter: brightness(1.05);
    box-shadow: 0 2px 8px rgba(51, 112, 255, 0.32), 0 10px 24px -6px rgba(51, 112, 255, 0.55);
}
.btn-main:active {
    transform: translateY(1px);
}
.btn-main:disabled {
    opacity: 0.6;
    cursor: not-allowed;
    box-shadow: none;
    filter: saturate(0.6);
}
.hint {
    font-size: 12px;
    color: var(--t3);
    text-align: center;
    margin-top: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
}
.hint-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--green);
    box-shadow: 0 0 0 3px var(--green-bg);
}

@media (max-width: 900px) {
    .brand-pane {
        display: none;
    }
}
</style>
