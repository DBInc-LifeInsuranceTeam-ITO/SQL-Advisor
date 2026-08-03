import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { installSqlTuningKoreanUi } from './utils/sqlTuningKorean'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
installSqlTuningKoreanUi()
