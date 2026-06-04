<template>
  <div class="sheet-editor">
    <!-- ========== 顶部工具栏 ========== -->
    <div class="editor-toolbar">
      <div class="toolbar-left">
        <el-button size="small" icon="el-icon-back"
                   @click="$router.push('/')">返回列表</el-button>
        <span class="doc-name">{{ documentName }}</span>
      </div>
      <div class="toolbar-right">
        <!-- 加载进度（大文件分块加载时展示） -->
        <span v-if="loadingSheet" class="load-progress">
          <i class="el-icon-loading"></i>
          加载 Sheet 数据中... {{ loadedChunks }}/{{ totalChunks }} 块
        </span>
        
        <el-tag v-if="hasUnsavedChanges" type="warning" size="small" style="margin-right: 8px">有未保存的修改</el-tag>
        
        <el-button size="small" type="primary" icon="el-icon-upload"
                   @click="handleSave" :loading="saving">手动保存</el-button>
        <el-button size="small" type="success" icon="el-icon-download"
                   @click="handleExport" :loading="exporting">导出 Excel</el-button>
      </div>
    </div>

    <!-- ========== Luckysheet 容器 ========== -->
    <div id="luckysheet-container"></div>
  </div>
</template>

<script>
import { getDocument, loadAllCelldata, batchUpdateCells } from '@/api/excel'
import { exportExcel } from '@/utils/export'

export default {
  name: 'SheetEditor',
  data() {
    return {
      documentId: null,
      documentName: '',
      // 分块加载进度
      loadingSheet: false,
      loadedChunks: 0,
      totalChunks: 0,
      // 导出状态
      exporting: false,
      saving: false,
      // luckysheet
      luckysheetLoaded: false,
      // 保存正在编辑的 sheetId 映射
      sheetIdMap: {},
      // 记录修改过的单元格
      dirtyCells: {}
    }
  },

  computed: {
    hasUnsavedChanges() {
      return Object.keys(this.dirtyCells).length > 0
    }
  },

  /* ========== 生命周期 ========== */
  async mounted() {
    this.documentId = this.$route.params.id
    await this.initDocument()
  },

  beforeDestroy() {
    try {
      if (this._keydownHandler) {
        document.removeEventListener('keydown', this._keydownHandler, true)
      }
      if (this._toolbarClickHandler) {
        const container = document.getElementById('luckysheet-container')
        if (container) container.removeEventListener('click', this._toolbarClickHandler, true)
      }
      if (window.luckysheet) window.luckysheet.destroy()
    } catch (e) {
      console.warn('Luckysheet destroy error:', e)
    }
  },

  methods: {
    /* =====================================================
     * 1. 初始化：加载文档元信息 → 逐 Sheet 加载 celldata → 渲染 Luckysheet
     * ===================================================== */
    async initDocument() {
      const loading = this.$loading({
        lock: true,
        text: '正在加载文档结构...',
        background: 'rgba(255,255,255,0.8)'
      })

      try {
        // Step1：拉取文档元信息（快，不含 celldata）
        const res = await getDocument(this.documentId)
        if (!res.data || !res.data.success) {
          this.$message.error('文档不存在')
          this.$router.push('/')
          return
        }

        const docData = res.data.data
        this.documentId = docData.id
        this.documentName = docData.name

        const sheetMetas = docData.sheets || []
        
        // 建立索引与数据库 sheetId 的映射关系，供后续更新使用
        sheetMetas.forEach((meta, idx) => {
          this.sheetIdMap[idx] = meta.sheetId
        })

        // Step2：准备空 Luckysheet 结构（先用元信息 + 空 celldata 渲染壳子）
        const luckySheets = sheetMetas.map((meta, idx) => ({
          name: meta.sheetName || ('Sheet' + (idx + 1)),
          index: String(idx),
          status: idx === 0 ? 1 : 0,
          order: idx,
          celldata: [],   // 先空，Step3 再填入
          config: {
            merge: meta.mergeConfig || {},
            columnlen: meta.columnLen || {}
          },
          // 暂存元信息，后续加载 celldata 用
          _sheetId: meta.sheetId,
          _chunkCount: meta.chunkCount,
          _totalRows: meta.totalRows
        }))

        if (luckySheets.length === 0) {
          luckySheets.push({
            name: 'Sheet1', index: '0', status: 1, order: 0,
            celldata: [], config: { merge: {}, columnlen: {} }
          })
        }

        // Step3：加载第一个 Sheet 的 celldata（活动 Sheet 优先）
        loading.close()

        // 加载第一个 Sheet 全量数据
        const firstMeta = sheetMetas[0]
        if (firstMeta) {
          loading.setText && loading.setText(`正在加载 ${firstMeta.sheetName} 数据...`)
          const celldata = await this.fetchSheetCelldata(firstMeta.sheetId, firstMeta.chunkCount, firstMeta.totalRows)
          luckySheets[0].celldata = celldata
        }

        // Step4：初始化 Luckysheet
        this.initLuckysheet(luckySheets)

        // Step5：后台静默加载其余 Sheet 的 celldata
        if (sheetMetas.length > 1) {
          this.loadRestSheets(sheetMetas, luckySheets)
        }

      } catch (e) {
        loading.close()
        this.$message.error('加载失败: ' + e.message)
        console.error(e)
        this.$router.push('/')
      }
    },

    /* =====================================================
     * 2. 加载指定 Sheet 的全量 celldata（自动合并所有分块）
     * @param {Number} sheetId    - Sheet 数据库 ID
     * @param {Number} chunkCount - 分块总数
     * @param {Number} totalRows  - 总行数（用于进度提示）
     * ===================================================== */
    async fetchSheetCelldata(sheetId, chunkCount, totalRows) {
      this.loadingSheet = true
      this.loadedChunks = 0
      this.totalChunks = chunkCount || 1

      try {
        // 直接调用 /all 接口（后端一次性合并所有块返回）
        // 如需前端逐块控制进度，可改为逐块调用 loadChunks
        const res = await loadAllCelldata(this.documentId, sheetId)
        if (res.data && res.data.success) {
          this.loadedChunks = this.totalChunks
          const celldata = res.data.data.celldata || []
          console.log(`[Sheet ${sheetId}] 加载完成: ${celldata.length} 个单元格，共 ${totalRows} 行`)
          return celldata
        }
        return []
      } catch (e) {
        console.error(`[Sheet ${sheetId}] celldata 加载失败:`, e)
        return []
      } finally {
        this.loadingSheet = false
      }
    },

    /* =====================================================
     * 3. 后台静默加载剩余 Sheet（不阻塞用户操作）
     * ===================================================== */
    async loadRestSheets(sheetMetas, luckySheets) {
      for (let i = 1; i < sheetMetas.length; i++) {
        const meta = sheetMetas[i]
        try {
          const celldata = await this.fetchSheetCelldata(meta.sheetId, meta.chunkCount, meta.totalRows)
          luckySheets[i].celldata = celldata

          // 【关键修复】必须更新 Luckysheet 的内部内存，而不是我们初始化的 local 数组
          if (window.luckysheet && typeof window.luckysheet.getluckysheetfile === 'function') {
            try {
              const allFiles = window.luckysheet.getluckysheetfile()
              // Luckysheet 内部默认使用 String(index) 作为唯一标识
              const targetFile = allFiles.find(f => f.index === String(i))
              if (targetFile) {
                targetFile.celldata = celldata
                // 如果用户已经切换到了这个 Sheet（手速极快），或者它正处于激活状态，
                // 仅更新 celldata 是不够的，需要强制它根据 celldata 重构底层二维数组 data
                if (targetFile.data && targetFile.data.length > 0) {
                   targetFile.data = window.luckysheet.buildGridData(targetFile)
                   if (window.luckysheet.getSheet().index === targetFile.index) {
                     window.luckysheet.refresh() // 刷新视图
                   }
                }
              }
              console.log(`[Sheet ${meta.sheetName}] 后台数据加载完成: ${celldata.length} 格`)
            } catch (e) {
              console.warn('更新 Sheet 数据失败:', e)
            }
          }
        } catch (e) {
          console.error(`后台加载 Sheet[${meta.sheetName}] 失败:`, e)
        }
      }
    },

    /* =====================================================
     * 4. 初始化 Luckysheet
     * ===================================================== */
    initLuckysheet(sheets) {
      setTimeout(() => {
        try { window.luckysheet.destroy() } catch (e) { /* ignore */ }

        window.luckysheet.create({
          container: 'luckysheet-container',
          lang: 'zh',
          showtoolbar: true,
          showinfobar: false,
          showstatisticBar: true,
          allowUpdate: true, // 打开 Luckysheet 原生的实时协同，消除 WebSocket 报错
          forceCalculation: true, // 强制加载时重算并建立公式计算链

          data: sheets.map((sheet, index) => ({
            name: sheet.name || ('Sheet' + (index + 1)),
            index: String(index),
            status: sheet.status !== undefined ? sheet.status : (index === 0 ? 1 : 0),
            order: index,
            celldata: sheet.celldata || [],
            config: sheet.config || { merge: {}, columnlen: {} }
          })),

          hook: {
            // 全局更新钩子
            updated: (operate) => {
              if (!operate || !this.documentId) return
              this._markCurrentSelectionDirty()
            },
            // 当单元格编辑模式（双击输入框）完成时触发
            cellUpdated: (r, c, oldValue, newValue, isRefresh) => {
              if (!this.documentId) return
              const dbSheetId = this.sheetIdMap[window.luckysheet.getSheet().index]
              if (!dbSheetId) return
              
              const key = `${dbSheetId}_${r}_${c}`
              const newDirty = { ...this.dirtyCells }
              
              // 优先使用传入的最新的 newValue（含有公式 f），若为空再降级从全局内存中取，避免因异步计算时序造成的公式丢失
              let fullCell = null
              if (newValue && typeof newValue === 'object') {
                fullCell = newValue
              } else {
                const sheetData = window.luckysheet.getSheetData()
                fullCell = sheetData[r] ? sheetData[r][c] : null
              }

              newDirty[key] = { sheetId: dbSheetId, r, c, v: fullCell }
              this.dirtyCells = newDirty
            }
          }
        })

        // ====== 终极防御：统一拦截所有的键盘删除与工具栏点击 ======
        this._keydownHandler = (e) => {
          if (e.key === 'Delete' || e.key === 'Backspace') {
            // 稍微延迟一下，等待 luckysheet 处理完内存数据清空
            setTimeout(() => this._markCurrentSelectionDirty(), 50)
          }
        }
        
        this._toolbarClickHandler = (e) => {
          // 如果点击了工具栏、右键菜单、颜色面板等，说明可能修改了样式
          // 稍微延迟一下，等待 luckysheet 完成样式更新
          setTimeout(() => this._markCurrentSelectionDirty(), 100)
        }

        // 绑定捕获阶段事件
        document.addEventListener('keydown', this._keydownHandler, true)
        const container = document.getElementById('luckysheet-container')
        if (container) {
          container.addEventListener('click', this._toolbarClickHandler, true)
        }

      }, 300)
    },

    // 提取公共方法：主动将当前选中的所有单元格（并抓取它们的最新数据）标记为已修改
    _markCurrentSelectionDirty() {
      if (!window.luckysheet) return
      const rangeArray = window.luckysheet.getRange()
      if (!rangeArray || rangeArray.length === 0) return
      
      const currentSheetIndex = window.luckysheet.getSheet().index
      const dbSheetId = this.sheetIdMap[currentSheetIndex]
      if (!dbSheetId) return

      let newDirty = { ...this.dirtyCells }
      const sheetData = window.luckysheet.getSheetData()

      rangeArray.forEach(rObj => {
        if (!rObj.row || !rObj.column) return
        for (let r = rObj.row[0]; r <= rObj.row[1]; r++) {
          for (let c = rObj.column[0]; c <= rObj.column[1]; c++) {
            const key = `${dbSheetId}_${r}_${c}`
            const fullCell = sheetData[r] ? sheetData[r][c] : null
            newDirty[key] = { sheetId: dbSheetId, r, c, v: fullCell }
          }
        }
      })
      this.dirtyCells = newDirty
    },

    /* =====================================================
     * 6. 手动保存（批量更新）
     * ===================================================== */
    async handleSave() {
      const updates = Object.values(this.dirtyCells)
      if (updates.length === 0) {
        this.$message.info('没有需要保存的修改')
        return
      }

      this.saving = true
      try {
        await batchUpdateCells(this.documentId, updates)
        this.$message.success(`保存成功（共 ${updates.length} 个单元格）`)
        // 清空脏数据标记
        this.dirtyCells = {}
      } catch (e) {
        this.$message.error('保存失败: ' + e.message)
      } finally {
        this.saving = false
      }
    },

    /* =====================================================
     * 7. 导出为 xlsx（由前端导出，完美保留样式）
     * ===================================================== */
    async handleExport() {
      if (this.hasUnsavedChanges) {
        this.$message.warning('您有未保存的修改，请先保存再导出')
        return
      }
      this.exporting = true
      try {
        this.$message.info('正在导出，请稍候...')
        if (!window.luckysheet) {
          throw new Error('表格未初始化')
        }
        await exportExcel(window.luckysheet.getAllSheets(), this.documentName)
        this.$message.success('导出成功')
      } catch (e) {
        this.$message.error('导出失败: ' + e.message)
        console.error(e)
      } finally {
        this.exporting = false
      }
    }
  }
}
</script>

<style scoped>
.sheet-editor {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden; /* 彻底锁定视口，防止出现浏览器外层原生滚动条 */
}

/* 顶部工具栏 */
.editor-toolbar {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
  z-index: 10;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.doc-name {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 加载进度提示 */
.load-progress {
  font-size: 12px;
  color: #909399;
  display: flex;
  align-items: center;
  gap: 4px;
}

/* Luckysheet 容器占满剩余空间 */
#luckysheet-container {
  flex: 1;
  position: relative;
  overflow: hidden;
  height: 0; /* 阻止 flex 布局在没有显式高度时被内部绝对定位子元素无限撑开 */
}
</style>
