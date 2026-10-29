import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { chartSize } from './utils/chartSize'
import { initTheme } from './utils/theme'
import './styles/global.css'

initTheme()

const app = createApp(App)
app.directive('chart-size', chartSize)
app.use(createPinia()).use(router).mount('#app')
