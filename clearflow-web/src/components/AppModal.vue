<script setup>
import { computed, ref, nextTick, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  title: { type: String, default: '' },
  sub: { type: String, default: '' },
  width: { type: [Number, String], default: 560 },
  zIndex: { type: Number, default: 1000 }
})
// 弹窗宽度档位（统一加宽）：详情 760→1080、新建/编辑 560→880；其他传值原样
const SIZE = { 760: 1080, 560: 880 }
const panelW = computed(() => {
  const n = Number(props.width)
  if (props.width !== '' && Number.isFinite(n)) return (SIZE[n] ?? n) + 'px'
  return String(props.width)
})
const emit = defineEmits(['close'])

// 多层弹窗：只有最上层响应 Esc，且打开期间锁定背景滚动
const uid = Symbol('modal')
const stack = [];
let lockCount = 0;
let prevOverflow = '';
function lockScroll() {
  if (lockCount === 0) {
    prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
  }
  lockCount++;
}
function unlockScroll() {
  lockCount = Math.max(0, lockCount - 1);
  if (lockCount === 0) document.body.style.overflow = prevOverflow;
}

const panel = ref(null);
let prevFocus = null;

function focusables() {
  if (!panel.value) return [];
  return [...panel.value.querySelectorAll('a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])')].filter(
    (el) => el.offsetParent !== null
  );
}

function onKey(e) {
  if (stack[stack.length - 1] !== uid) return;
  if (e.key === 'Escape') {
    e.stopPropagation();
    emit('close');
    return;
  }
  if (e.key !== 'Tab') return;
  const list = focusables();
  if (!list.length) {
    e.preventDefault();
    panel.value?.focus();
    return;
  }
  const first = list[0];
  const last = list[list.length - 1];
  if (e.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) {
    e.preventDefault();
    last.focus();
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault();
    first.focus();
  }
}

onMounted(async () => {
  stack.push(uid);
  lockScroll();
  prevFocus = document.activeElement;
  window.addEventListener('keydown', onKey, true);
  await nextTick();
  panel.value?.focus();
});
onUnmounted(() => {
  window.removeEventListener('keydown', onKey, true);
  const i = stack.indexOf(uid);
  if (i >= 0) stack.splice(i, 1);
  unlockScroll();
  if (prevFocus && prevFocus.isConnected) prevFocus.focus();
});
</script>

<template>
  <Teleport to="body">
    <div class="modal-overlay" :style="{ zIndex }">
      <div ref="panel" class="modal-panel" :style="{ width: panelW }" role="dialog" aria-modal="true" :aria-label="title || '对话框'" tabindex="-1">
        <div class="modal-head">
          <div>
            <div class="modal-title">{{ title }}</div>
            <div v-if="sub" class="modal-sub">{{ sub }}</div>
          </div>
          <button class="modal-close" @click="emit('close')">
            <svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg>
          </button>
        </div>
        <div class="modal-body" :class="{ 'has-rail': $slots.rail }">
          <div class="modal-main"><slot /></div>
          <div v-if="$slots.rail" class="modal-rail"><slot name="rail" /></div>
        </div>
        <div v-if="$slots.footer" class="modal-foot"><slot name="footer" /></div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-overlay{position:fixed;inset:0;background:var(--overlay);backdrop-filter:blur(3px);display:flex;align-items:center;justify-content:center;padding:24px}
.modal-panel{max-width:calc(100vw - 48px);max-height:calc(100vh - 48px);display:flex;flex-direction:column;
  background:var(--card);border-radius:var(--radius-xl);box-shadow:var(--shadow-lg);animation:pop .2s var(--ease) both}
@keyframes pop{from{opacity:0;transform:translateY(12px) scale(.98)}to{opacity:1;transform:none}}
.modal-head{display:flex;align-items:flex-start;gap:10px;padding:20px 24px 0}
.modal-title{font-size:16px;font-weight:700;line-height:1.4}
.modal-sub{font-size:12px;color:var(--t3);margin-top:3px}
.modal-close{margin-left:auto;width:28px;height:28px;flex:none;border:none;background:none;border-radius:6px;cursor:pointer;
  display:flex;align-items:center;justify-content:center;color:var(--t3);transition:background .15s var(--ease),color .15s var(--ease)}
.modal-close:hover{background:var(--track);color:var(--t1)}
.modal-close svg{width:15px;height:15px;stroke:currentColor;stroke-width:1.8;fill:none;stroke-linecap:round}
.modal-body{padding:16px 24px 20px;overflow:auto}
.modal-body.has-rail{display:grid;grid-template-columns:1fr 216px;gap:24px}
.modal-rail{border-left:1px solid var(--border);padding-left:20px;min-width:0}
.modal-foot{display:flex;justify-content:flex-end;gap:10px;padding:0 24px 20px}
.modal-foot :deep(.btn){height:34px}
.modal-foot :deep(.ghost-btn){height:34px}

@media (max-width:880px){
  .modal-body.has-rail{grid-template-columns:1fr}
  .modal-rail{border-left:none;padding-left:0;border-top:1px solid var(--border);padding-top:14px}
}
</style>
