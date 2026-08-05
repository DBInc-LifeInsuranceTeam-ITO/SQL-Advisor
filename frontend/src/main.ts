import './assets/main.css'
import './assets/sql-tuning-polish.css'
import './assets/sql-diagnosis-neutral.css'
import './assets/sql-connection-card.css'
import './assets/sql-top-sql-table.css'
import './assets/sql-result-polish.css'
import './assets/sql-manual-analysis.css'
import './assets/realtime-dashboard-compact.css'
import './assets/realtime-dashboard-fit.css'
import './sqlTuningUiEnhancer'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
