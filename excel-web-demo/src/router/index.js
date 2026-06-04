import Vue from 'vue'
import Router from 'vue-router'

Vue.use(Router)

// 懒加载页面组件
const ExcelDashboard = () => import('@/views/ExcelDashboard.vue')
const SheetEditor = () => import('@/views/SheetEditor.vue')

export default new Router({
  mode: 'hash',
  routes: [
    {
      path: '/',
      name: 'Dashboard',
      component: ExcelDashboard
    },
    {
      path: '/edit/:id',
      name: 'SheetEditor',
      component: SheetEditor
    }
  ]
})
