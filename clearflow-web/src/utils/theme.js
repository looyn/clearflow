const KEY = 'cf-theme'

export const storedTheme = () => {
    const v = localStorage.getItem(KEY)
    return v === 'dark' || v === 'light' ? v : ''
}

export const systemTheme = () =>
    window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'

export function applyTheme(theme) {
    document.documentElement.dataset.theme = theme
    const meta = document.querySelector('meta[name="theme-color"]')
    if (meta) meta.setAttribute('content', theme === 'dark' ? '#0E1116' : '#3370FF')
}

export function initTheme() {
    applyTheme(storedTheme() || document.documentElement.dataset.theme || systemTheme())
    // 未手动选择过主题时跟随系统明暗切换
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
        if (!storedTheme()) applyTheme(e.matches ? 'dark' : 'light')
    })
}

export function setTheme(theme) {
    localStorage.setItem(KEY, theme)
    applyTheme(theme)
}
