<script setup>
import { onMounted, onUnmounted } from 'vue'

defineProps({
  title: { type: String, default: '确认操作' },
  text: { type: String, default: '' },
  confirmText: { type: String, default: '删除' }
})
const emit = defineEmits(['confirm', 'close'])
function onKey(e) { if (e.key === 'Escape') emit('close') }
onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <div class="cf-overlay" style="z-index:1100">
      <div class="cf-panel">
        <div class="cf-head">
          <div class="cf-icon">
            <svg viewBox="0 0 24 24">
              <path d="M12 9v4M12 17h.01" />
              <path d="M10.3 3.9 2.6 17.2A2 2 0 0 0 4.3 20h15.4a2 2 0 0 0 1.7-2.8L13.7 3.9a2 2 0 0 0-3.4 0z" />
            </svg>
          </div>
          <div class="cf-title">{{ title }}</div>
        </div>
        <div class="cf-text">{{ text }}</div>
        <div class="cf-foot">
          <button class="ghost-btn" @click="emit('close')">取消</button>
          <button class="btn danger" @click="emit('confirm')">{{ confirmText }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.cf-overlay{position:fixed;inset:0;background:var(--overlay);backdrop-filter:blur(2px);display:flex;align-items:center;justify-content:center;padding:24px}
.cf-panel{width:440px;max-width:calc(100vw - 48px);background:var(--card);border-radius:var(--radius-xl);
  box-shadow:var(--shadow-lg);padding:22px 24px 20px;animation:pop .2s var(--ease) both}
@keyframes pop{from{opacity:0;transform:translateY(12px) scale(.98)}to{opacity:1;transform:none}}
.cf-head{display:flex;align-items:center;gap:12px}
.cf-icon{flex:none;width:36px;height:36px;border-radius:10px;background:var(--red-bg);display:flex;align-items:center;justify-content:center}
.cf-icon svg{width:20px;height:20px;stroke:var(--red);stroke-width:1.8;fill:none;stroke-linecap:round;stroke-linejoin:round}
.cf-title{font-size:16px;font-weight:700}
.cf-text{font-size:13px;color:var(--t2);margin-top:12px;line-height:1.6}
.cf-foot{display:flex;justify-content:flex-end;gap:10px;margin-top:20px}
</style>
