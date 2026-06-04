const { defineConfig } = require('@vue/cli-service')

module.exports = defineConfig({
  // 开发时把 /api 代理到后端 9191
  devServer: {
    port: 8081,
    // 关闭运行时错误遮罩层（CDN 跨域脚本会触发无意义的 "Script error."）
    client: {
      overlay: false
    },
    proxy: {
      '/api': {
        target: 'http://localhost:9191',
        changeOrigin: true
      }
    }
  },

  // Luckysheet 相关配置
  transpileDependencies: ['luckysheet'],

  // 关闭生产 source map
  productionSourceMap: false
})
