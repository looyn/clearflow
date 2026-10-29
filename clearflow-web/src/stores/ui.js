import { defineStore } from 'pinia'
import { setTheme } from '../utils/theme'

// 批量写操作（如多选删除）会连续触发刷新，合并为一次，避免每个请求都让全页重查
let refreshTimer = null
const REFRESH_DEBOUNCE = 150

export const useUiStore = defineStore('ui', {
  state: () => ({
    activeCreate: null, // 'task' | 'defect' | 'release' | 'requirement' | 'iteration' | 'member' | 'document'
    createPreset: {},   // 新建表单预填值（如看板列头快速新建带 status）
    searchOpen: false,  // ⌘K 全局搜索
    refreshTick: 0,     // 写操作成功后广播刷新，触发各视图重查
    settingsTick: 0,    // 项目设置（选项配置/工作流/切换项目）变更，用于刷新选项 store
    theme: document.documentElement.dataset.theme || 'light' // index.html 内联脚本已按存储/系统偏好写入
  }),
  actions: {
    openCreate(type, preset = {}) { this.activeCreate = type; this.createPreset = preset },
    closeCreate() { this.activeCreate = null; this.createPreset = {} },
    toggleTheme() {
      this.theme = this.theme === 'dark' ? 'light' : 'dark'
      setTheme(this.theme)
    },
    notifyRefresh() {
      clearTimeout(refreshTimer)
      refreshTimer = setTimeout(() => {
        this.refreshTick++
      }, REFRESH_DEBOUNCE)
    },
    notifySettings() { this.settingsTick++ }
  }
})
