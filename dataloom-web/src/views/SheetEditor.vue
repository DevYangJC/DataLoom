<template>
  <main class="editor-page">
    <header class="editor-toolbar">
      <div class="toolbar-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回列表</el-button>
        <div class="doc-meta">
          <strong>{{ documentName || '未命名文档' }}</strong>
          <span v-if="loadingSheet">加载 Sheet 数据：{{ loadedChunks }}/{{ totalChunks }} 块</span>
          <span v-else>{{ sheetCount }} 个 Sheet</span>
        </div>
      </div>

      <div class="toolbar-right">
        <el-tag v-if="hasUnsavedChanges" type="warning" effect="light">有未保存修改</el-tag>
        <el-button type="primary" :icon="Upload" :loading="saving" @click="saveChanges">保存</el-button>
        <el-button type="success" :icon="Download" :loading="exporting" @click="exportCurrentWorkbook">
          导出
        </el-button>
      </div>
    </header>

    <section class="sheet-stage">
      <div v-if="booting" class="boot-panel">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>正在打开表格...</span>
      </div>
      <div id="luckysheet-container"></div>
    </section>
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Download, Loading, Upload } from '@element-plus/icons-vue'
import { ElLoading, ElMessage } from 'element-plus'
import { getDocument, loadAllCelldata, saveWorkbook } from '@/api/excel'
import { exportExcel } from '@/utils/export'

const route = useRoute()
const router = useRouter()

const documentId = ref(route.params.id)
const documentName = ref('')
const sheetCount = ref(0)
const booting = ref(true)
const loadingSheet = ref(false)
const loadedChunks = ref(0)
const totalChunks = ref(0)
const saving = ref(false)
const exporting = ref(false)
const workbookDirty = ref(false)
const sheetIdMap = reactive({})
const dirtyCells = reactive({})

let keydownHandler = null
let toolbarClickHandler = null

const hasUnsavedChanges = computed(() => workbookDirty.value || Object.keys(dirtyCells).length > 0)

onMounted(async () => {
  await initDocument()
})

onBeforeUnmount(() => {
  try {
    if (keydownHandler) document.removeEventListener('keydown', keydownHandler, true)

    const container = document.getElementById('luckysheet-container')
    if (container && toolbarClickHandler) {
      container.removeEventListener('click', toolbarClickHandler, true)
    }

    window.luckysheet?.destroy?.()
  } catch (error) {
    console.warn('Luckysheet destroy error:', error)
  }
})

async function initDocument() {
  const loading = ElLoading.service({
    lock: true,
    text: '正在加载文档结构...',
    background: 'rgba(255,255,255,0.78)'
  })

  try {
    const response = await getDocument(documentId.value)
    const payload = response.data
    if (!payload?.success) throw new Error(payload?.message || '文档不存在')

    const doc = payload.data
    documentId.value = doc.id
    documentName.value = doc.name

    const metas = doc.sheets || []
    sheetCount.value = metas.length
    metas.forEach((meta, index) => {
      sheetIdMap[String(index)] = meta.sheetId
    })

    const sheets = buildInitialSheets(metas)
    for (let index = 0; index < metas.length; index += 1) {
      const meta = metas[index]
      loading.setText(`正在加载 ${meta.sheetName || `Sheet${index + 1}`}...`)
      sheets[index].celldata = await fetchSheetCelldata(meta)
    }

    loading.close()
    await nextTick()
    initLuckysheet(sheets)
  } catch (error) {
    loading.close()
    ElMessage.error(`打开文档失败：${error.message}`)
    router.push({ name: 'Dashboard' })
  } finally {
    booting.value = false
  }
}

function buildInitialSheets(metas) {
  if (metas.length === 0) {
    return [{
      name: 'Sheet1',
      index: '0',
      status: 1,
      order: 0,
      celldata: [],
      config: { merge: {}, columnlen: {}, rowlen: {} },
      hyperlink: {},
      images: {},
      luckysheet_conditionformat_save: [],
      chart: []
    }]
  }

  return metas.map((meta, index) => {
    const config = meta.config && Object.keys(meta.config).length > 0
      ? meta.config
      : {
          merge: meta.mergeConfig || {},
          columnlen: meta.columnLen || {},
          rowlen: meta.rowLen || {}
        }

    return {
      name: meta.sheetName || `Sheet${index + 1}`,
      index: String(index),
      status: index === 0 ? 1 : 0,
      order: index,
      celldata: [],
      config,
      _sheetId: meta.sheetId,
      hyperlink: meta.hyperlink || {},
      images: meta.images || {},
      luckysheet_conditionformat_save: meta.luckysheet_conditionformat_save || [],
      chart: meta.chart || []
    }
  })
}

async function fetchSheetCelldata(meta) {
  loadingSheet.value = true
  loadedChunks.value = 0
  totalChunks.value = meta.chunkCount || 1

  try {
    const response = await loadAllCelldata(documentId.value, meta.sheetId)
    const payload = response.data
    if (!payload?.success) throw new Error(payload?.message || 'Sheet 数据加载失败')

    loadedChunks.value = totalChunks.value
    return payload.data?.celldata || []
  } catch (error) {
    ElMessage.warning(`${meta.sheetName || 'Sheet'} 加载失败：${error.message}`)
    return []
  } finally {
    loadingSheet.value = false
  }
}

async function loadRestSheets(metas, sheets) {
  for (let index = 1; index < metas.length; index += 1) {
    const meta = metas[index]
    const celldata = await fetchSheetCelldata(meta)
    sheets[index].celldata = celldata
    injectSheetData(index, celldata)
  }
  workbookDirty.value = false
  Object.keys(dirtyCells).forEach((key) => delete dirtyCells[key])
}

function initLuckysheet(sheets) {
  if (!window.luckysheet) {
    ElMessage.error('Luckysheet 资源未加载，请检查网络或 CDN 配置')
    return
  }

  try {
    window.luckysheet.destroy?.()
  } catch {
    // Ignore stale instances created by previous route visits.
  }

  window.luckysheet.create({
    container: 'luckysheet-container',
    lang: 'zh',
    showtoolbar: true,
    showinfobar: false,
    showstatisticBar: true,
    allowUpdate: true,
    forceCalculation: true,
    plugins: ['chart'],
    pluginsUrl: 'https://cdn.jsdelivr.net/npm/luckysheet@2.1.13/dist',
    data: sheets.map((sheet, index) => ({
      name: sheet.name || `Sheet${index + 1}`,
      index: String(index),
      status: sheet.status ?? (index === 0 ? 1 : 0),
      order: index,
      celldata: sheet.celldata || [],
      config: sheet.config || { merge: {}, columnlen: {}, rowlen: {} },
      hyperlink: sheet.hyperlink || {},
      images: sheet.images || {},
      luckysheet_conditionformat_save: sheet.luckysheet_conditionformat_save || [],
      chart: sheet.chart || []
    })),
    hook: {
      updated: () => {
        workbookDirty.value = true
        markCurrentSelectionDirty()
      },
      cellUpdated: (r, c, oldValue, newValue) => markCellDirty(r, c, newValue),
      imageDeleteAfter: (imageItem) => {
        workbookDirty.value = true
        console.log("====== [imageDeleteAfter] 触发了, imageItem:", imageItem)
        const luckysheet = window.luckysheet
        if (luckysheet?.getluckysheetfile && imageItem) {
          const files = luckysheet.getluckysheetfile()
          const targetId = imageItem.id || imageItem.imgId || imageItem.imageId
          const targetSrc = imageItem.src

          console.log("====== [imageDeleteAfter] 尝试删除, targetId:", targetId, "targetSrc (长度):", targetSrc ? targetSrc.length : 0)

          files.forEach((sheet) => {
            if (sheet.images) {
              let deleted = false
              
              // 1. 优先通过 ID 匹配删除
              if (targetId && sheet.images[targetId]) {
                console.log("====== [imageDeleteAfter] 根据 ID 成功删除内存图片:", targetId)
                delete sheet.images[targetId]
                deleted = true
              }
              
              // 2. 其次通过 src 匹配删除
              if (targetSrc) {
                Object.keys(sheet.images).forEach((key) => {
                  if (sheet.images[key] && sheet.images[key].src === targetSrc) {
                    console.log("====== [imageDeleteAfter] 根据 src 成功匹配并删除内存图片, key:", key)
                    delete sheet.images[key]
                    deleted = true
                  }
                })
              }
              
              // 3. 最后通过坐标和尺寸做相似度匹配删除
              if (!deleted) {
                Object.keys(sheet.images).forEach((key) => {
                  const img = sheet.images[key]
                  if (img && 
                      Math.abs((img.left || 0) - (imageItem.left || 0)) < 5 &&
                      Math.abs((img.top || 0) - (imageItem.top || 0)) < 5 &&
                      Math.abs((img.width || 0) - (imageItem.width || 0)) < 5 &&
                      Math.abs((img.height || 0) - (imageItem.height || 0)) < 5) {
                    console.log("====== [imageDeleteAfter] 根据坐标尺寸匹配并删除内存图片, key:", key)
                    delete sheet.images[key]
                  }
                })
              }
            }
          })
        }
      }
    }
  })
  workbookDirty.value = false
  Object.keys(dirtyCells).forEach((key) => delete dirtyCells[key])

  keydownHandler = (event) => {
    if (event.key === 'Delete' || event.key === 'Backspace') {
      window.setTimeout(markCurrentSelectionDirty, 50)
    }
  }

  toolbarClickHandler = () => {
    window.setTimeout(markCurrentSelectionDirty, 100)
  }

  document.addEventListener('keydown', keydownHandler, true)
  document.getElementById('luckysheet-container')?.addEventListener('click', toolbarClickHandler, true)
}

function injectSheetData(index, celldata) {
  const luckysheet = window.luckysheet
  if (!luckysheet?.getluckysheetfile) return

  try {
    const files = luckysheet.getluckysheetfile()
    const target = files.find((file) => file.index === String(index))
    if (!target) return

    target.celldata = celldata
    if (target.data && luckysheet.buildGridData) {
      target.data = luckysheet.buildGridData(target)
    }
    if (luckysheet.getSheet?.()?.index === target.index) {
      luckysheet.refresh?.()
    }
  } catch (error) {
    console.warn('Inject sheet data failed:', error)
  }
}

function markCellDirty(r, c, newValue) {
  const luckysheet = window.luckysheet
  const currentSheet = luckysheet?.getSheet?.()
  if (!currentSheet) return

  workbookDirty.value = true

  const dbSheetId = sheetIdMap[currentSheet.index]
  if (!dbSheetId) return

  const sheetData = luckysheet.getSheetData?.() || []
  const fullCell = newValue && typeof newValue === 'object' ? newValue : sheetData[r]?.[c] || null
  dirtyCells[`${dbSheetId}_${r}_${c}`] = { sheetId: dbSheetId, r, c, v: fullCell }
}

function markCurrentSelectionDirty() {
  const luckysheet = window.luckysheet
  const ranges = luckysheet?.getRange?.()
  const currentSheet = luckysheet?.getSheet?.()
  if (!ranges?.length || !currentSheet) return

  workbookDirty.value = true

  const dbSheetId = sheetIdMap[currentSheet.index]
  if (!dbSheetId) return

  const sheetData = luckysheet.getSheetData?.() || []
  ranges.forEach((range) => {
    if (!range.row || !range.column) return

    for (let r = range.row[0]; r <= range.row[1]; r += 1) {
      for (let c = range.column[0]; c <= range.column[1]; c += 1) {
        dirtyCells[`${dbSheetId}_${r}_${c}`] = {
          sheetId: dbSheetId,
          r,
          c,
          v: sheetData[r]?.[c] || null
        }
      }
    }
  })
}

async function saveChanges() {
  const sheets = serializeWorkbook()
  if (!sheets || sheets.length === 0) {
    ElMessage.error('表格尚未初始化')
    return
  }

  saving.value = true
  try {
    await saveWorkbook(documentId.value, sheets)
    Object.keys(dirtyCells).forEach((key) => delete dirtyCells[key])
    workbookDirty.value = false
    sheetCount.value = sheets.length
    sheets.forEach((sheet, index) => {
      if (sheet._sheetId) sheetIdMap[String(index)] = sheet._sheetId
    })
    ElMessage.success(`保存成功，共 ${sheets.length} 个 Sheet`)
  } catch (error) {
    ElMessage.error(`保存失败：${error.message}`)
  } finally {
    saving.value = false
  }
}

function serializeWorkbook() {
  const luckysheet = window.luckysheet
  const rawSheets = luckysheet?.getluckysheetfile?.() || luckysheet?.getAllSheets?.()
  const sheets = Array.isArray(rawSheets) ? rawSheets : rawSheets ? [rawSheets] : []

  const serialized = sheets.map((sheet, index) => {
    const data = Array.isArray(sheet.data) ? sheet.data : []
    const celldata = data.length > 0 ? dataMatrixToCelldata(data) : normalizeCelldata(sheet.celldata || [])

    return {
      ...sheet,
      name: sheet.name || `Sheet${index + 1}`,
      order: sheet.order ?? index,
      row: sheet.row || data.length || calcMaxRow(celldata),
      column: sheet.column || calcMaxColumn(data, celldata),
      config: sheet.config || { merge: {}, columnlen: {}, rowlen: {} },
      celldata
    }
  })
  console.log("====== [serializeWorkbook] 准备提交的 sheets 数据:", serialized)
  return serialized
}

function dataMatrixToCelldata(data) {
  const celldata = []
  data.forEach((row, r) => {
    if (!Array.isArray(row)) return
    row.forEach((cell, c) => {
      if (isEmptyCell(cell)) return
      celldata.push({ r, c, v: cell })
    })
  })
  return celldata
}

function normalizeCelldata(celldata) {
  if (!Array.isArray(celldata)) return []
  return celldata.filter((cell) => cell && !isEmptyCell(cell.v))
}

function isEmptyCell(cell) {
  if (!cell) return true
  if (typeof cell === 'object') return Object.keys(cell).length === 0
  return false
}

function calcMaxRow(celldata) {
  return celldata.reduce((max, cell) => Math.max(max, Number(cell.r || 0) + 1), 1)
}

function calcMaxColumn(data, celldata) {
  const dataCols = data.reduce((max, row) => Math.max(max, Array.isArray(row) ? row.length : 0), 0)
  const cellCols = celldata.reduce((max, cell) => Math.max(max, Number(cell.c || 0) + 1), 0)
  return Math.max(dataCols, cellCols, 1)
}

async function exportCurrentWorkbook() {
  if (hasUnsavedChanges.value) {
    ElMessage.warning('请先保存当前修改后再导出')
    return
  }

  exporting.value = true
  try {
    const sheets = window.luckysheet?.getAllSheets?.()
    if (!sheets) throw new Error('表格尚未初始化')

    await exportExcel(sheets, documentName.value)
    ElMessage.success('导出完成')
  } catch (error) {
    ElMessage.error(`导出失败：${error.message}`)
  } finally {
    exporting.value = false
  }
}

function goBack() {
  router.push({ name: 'Dashboard' })
}
</script>

<style scoped>
.editor-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: #eef2eb;
}

.editor-toolbar {
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--dl-line);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 8px 24px rgba(20, 32, 24, 0.08);
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.doc-meta {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.doc-meta strong,
.doc-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta strong {
  max-width: 360px;
  font-size: 14px;
}

.doc-meta span {
  color: var(--dl-muted);
  font-size: 12px;
}

.sheet-stage {
  position: relative;
  flex: 1;
  min-height: 0;
}

#luckysheet-container {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.boot-panel {
  position: absolute;
  inset: 18px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 1px solid var(--dl-line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--dl-muted);
}

@media (max-width: 760px) {
  .editor-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .toolbar-left,
  .toolbar-right {
    justify-content: space-between;
    width: 100%;
  }

  .doc-meta strong {
    max-width: 190px;
  }
}
</style>
