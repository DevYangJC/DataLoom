import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('@/views/ExcelDashboard.vue')
  },
  {
    path: '/edit/:id',
    name: 'SheetEditor',
    component: () => import('@/views/SheetEditor.vue'),
    props: true
  }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
