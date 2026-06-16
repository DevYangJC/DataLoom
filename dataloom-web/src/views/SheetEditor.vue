<template>
  <main class="editor-page">
    <header class="editor-toolbar">
      <div class="toolbar-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回列表</el-button>
        <div class="doc-meta">
          <strong
            v-if="!editingName"
            class="doc-name"
            title="点击重命名"
            @click="startRename"
          >{{ documentName || '未命名文档' }}</strong>
          <div v-else class="doc-name-edit">
            <input
              ref="nameInput"
              v-model="renameValue"
              class="rename-input"
              maxlength="200"
              @keydown.enter="confirmRename"
              @keydown.escape="cancelRename"
              @blur="confirmRename"
            />
          </div>
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
import { batchUpdateCells, getDocument, loadAllCelldata, renameDocument, saveWorkbook } from '@/api/excel'
import { exportExcel } from '@/utils/export'
import { defaultOption } from '@/utils/chartmixDefaultOption'

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
const structureDirty = ref(false)
const sheetIdMap = reactive({})
const dirtyCells = reactive({})

/** 初始化完成后拍的结构快照，用于判断是否需要全量保存 */
let structureSnapshot = null

let keydownHandler = null
let toolbarClickHandler = null

const hasUnsavedChanges = computed(() =>
  workbookDirty.value || structureDirty.value || Object.keys(dirtyCells).length > 0
)

// ── 文档重命名 ──
const editingName = ref(false)
const renameValue = ref('')
const nameInput = ref(null)

function startRename() {
  renameValue.value = documentName.value
  editingName.value = true
  setTimeout(() => nameInput.value?.focus(), 50)
}

async function confirmRename() {
  if (!editingName.value) return
  const newName = renameValue.value.trim()
  editingName.value = false

  if (!newName || newName === documentName.value) return

  try {
    await renameDocument(documentId.value, newName)
    documentName.value = newName
    ElMessage.success('重命名成功')
  } catch (error) {
    ElMessage.error(`重命名失败：${error.message}`)
  }
}

function cancelRename() {
  editingName.value = false
}

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
  console.log("====== [initLuckysheet] 原始传入的 sheets chart:", JSON.stringify(sheets.map(s => s.chart)))
  
  const finalSheets = sheets.map((sheet, index) => {
    const finalChart = (sheet.chart || []).map((c) => {
      if (c) {
        let chartType = 'line'
        const series = c.chartOptions?.series
        if (Array.isArray(series) && series.length > 0) {
          chartType = series[0]?.type || 'line'
        }
        const fullChartType = `echarts|${chartType}|default`
        
        if (!c.chartType) {
          c.chartType = fullChartType
        }
        if (!c.chartOptions) {
          c.chartOptions = {}
        }
        if (!c.chartOptions.chart_id) {
          c.chartOptions.chart_id = c.chart_id
        }
        if (!c.chartOptions.chartAllType) {
          c.chartOptions.chartAllType = fullChartType
        }
        if (!c.chartOptions.rangeArray) {
          c.chartOptions.rangeArray = [{ row: [0, 0], column: [0, 0] }]
        }
        if (!c.chartOptions.rangeColCheck) {
          c.chartOptions.rangeColCheck = { exits: true, clear: [0, 0], type: ['number', 'number'] }
        }
        if (!c.chartOptions.rangeRowCheck) {
          c.chartOptions.rangeRowCheck = { exits: true, clear: [0, 0], type: ['string', 'number'] }
        }
        if (c.chartOptions.rangeConfigCheck === undefined) {
          c.chartOptions.rangeConfigCheck = false
        }
        if (!c.chartOptions.chartDataSeriesOrder) {
          c.chartOptions.chartDataSeriesOrder = { 0: 0, length: 1 }
        }
        
        // Ensure defaultOption exists to prevent chartmix crash
        if (!c.chartOptions.defaultOption) {
          c.chartOptions.defaultOption = JSON.parse(JSON.stringify(defaultOption || {}));
        }
      }
      return c
    })
    console.log(`====== [initLuckysheet] Sheet[${index}] 补全后的 chart:`, JSON.stringify(finalChart))
    return {
      name: sheet.name || `Sheet${index + 1}`,
      index: String(index),
      status: sheet.status ?? (index === 0 ? 1 : 0),
      order: index,
      celldata: sheet.celldata || [],
      config: sheet.config || { merge: {}, columnlen: {}, rowlen: {} },
      hyperlink: sheet.hyperlink || {},
      images: sheet.images || {},
      luckysheet_conditionformat_save: sheet.luckysheet_conditionformat_save || [],
      chart: finalChart
    }
  })
  
  console.log("====== [initLuckysheet] 准备传给 luckysheet.create 的 data 中的 chart:", JSON.stringify(finalSheets.map(s => s.chart)))

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
    pluginsUrl: window.location.origin,
    data: finalSheets,
    hook: {
      workbookCreateAfter: () => {
        console.log("====== [workbookCreateAfter] 触发了")
      },
      updated: () => {
        workbookDirty.value = true
        markCurrentSelectionDirty()
      },
      cellUpdated: (r, c, oldValue, newValue) => markCellDirty(r, c, newValue),
      imageDeleteAfter: (imageItem) => {
        workbookDirty.value = true
        structureDirty.value = true
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
  structureDirty.value = false
  Object.keys(dirtyCells).forEach((key) => delete dirtyCells[key])

  // ★ 拍一张结构快照，用于后续判断是否只需要增量保存单元格
  captureStructureSnapshot()

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

/**
 * 拍摄当前工作簿的结构快照（不含 celldata）。
 * 后续保存时对比快照，若结构未变则只走增量单元格更新。
 */
function captureStructureSnapshot() {
  const luckysheet = window.luckysheet
  const files = luckysheet?.getluckysheetfile?.() || []
  structureSnapshot = files.map((sheet) => ({
    name: sheet.name,
    index: sheet.index,
    status: sheet.status,
    order: sheet.order,
    configSig: stableJson(sheet.config || {}),
    imagesSig: stableJson(sheet.images || {}),
    chartSig: stableJson(sheet.chart || []),
    hyperlinkSig: stableJson(sheet.hyperlink || {}),
    conditionFormatSig: stableJson(sheet.luckysheet_conditionformat_save || [])
  }))
}

/**
 * 对比当前 luckysheetfile 与结构快照。
 * @returns {{ changed: boolean, reason?: string }}
 */
function detectStructuralChange() {
  const luckysheet = window.luckysheet
  const files = luckysheet?.getluckysheetfile?.() || []

  if (!structureSnapshot || files.length !== structureSnapshot.length) {
    return { changed: true, reason: `Sheet 数量变化: ${structureSnapshot?.length ?? 0} → ${files.length}` }
  }

  for (let i = 0; i < files.length; i++) {
    const cur = files[i]
    const snap = structureSnapshot[i]
    if (!snap) return { changed: true, reason: `Sheet[${i}] 为新增` }

    if (cur.name !== snap.name) return { changed: true, reason: `Sheet[${i}] 名称变更: "${snap.name}" → "${cur.name}"` }
    if (cur.status !== snap.status) return { changed: true, reason: `Sheet[${i}] 激活状态变更` }
    if (cur.order !== snap.order) return { changed: true, reason: `Sheet[${i}] 顺序变更` }

    if (stableJson(cur.config || {}) !== snap.configSig) return { changed: true, reason: `Sheet[${i}] config 变更（合并单元格/列宽/行高）` }
    if (stableJson(cur.images || {}) !== snap.imagesSig) return { changed: true, reason: `Sheet[${i}] 图片变更` }
    if (stableJson(cur.chart || []) !== snap.chartSig) return { changed: true, reason: `Sheet[${i}] 图表变更` }
    if (stableJson(cur.hyperlink || {}) !== snap.hyperlinkSig) return { changed: true, reason: `Sheet[${i}] 超链接变更` }
    if (stableJson(cur.luckysheet_conditionformat_save || []) !== snap.conditionFormatSig) return { changed: true, reason: `Sheet[${i}] 条件格式变更` }
  }

  return { changed: false }
}

/**
 * 稳定 JSON 序列化（排序 key），用于结构对比。
 */
function stableJson(obj) {
  return JSON.stringify(obj, (_, value) => {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return Object.keys(value)
        .sort()
        .reduce((acc, key) => { acc[key] = value[key]; return acc }, {})
    }
    return value
  })
}

async function saveChanges() {
  const luckysheet = window.luckysheet
  if (!luckysheet) {
    ElMessage.error('表格尚未初始化')
    return
  }

  saving.value = true
  try {
    const structuralChange = detectStructuralChange()
    const cellDirtyCount = Object.keys(dirtyCells).length

    if (structuralChange.changed) {
      // ── 结构/图片/图表/超链接/条件格式有变化 → 全量快照保存 ──
      console.log(`====== [saveChanges] 结构变更 [${structuralChange.reason}]，使用全量快照保存`)
      const sheets = serializeWorkbook()
      if (!sheets || sheets.length === 0) {
        ElMessage.error('表格数据为空')
        return
      }

      const response = await saveWorkbook(documentId.value, sheets)
      const payload = response.data
      if (payload?.success && payload.data?.sheetIdMap) {
        // ★ 用后端返回的新 sheetId 刷新映射表
        const newIdMap = payload.data.sheetIdMap
        Object.keys(newIdMap).forEach((key) => {
          sheetIdMap[String(key)] = String(newIdMap[key])
        })
        sheetCount.value = payload.data.sheetCount || sheets.length
        console.log('====== [saveChanges] sheetIdMap 已刷新:', JSON.stringify(sheetIdMap))
      } else {
        sheetCount.value = sheets.length
      }
    } else if (cellDirtyCount > 0) {
      // ── 只有单元格数据变化 → 增量更新 ──
      console.log(`====== [saveChanges] 仅单元格变更，增量更新 ${cellDirtyCount} 个单元格`)
      const updates = Object.values(dirtyCells).map((item) => ({
        sheetId: Number(item.sheetId),
        r: item.r,
        c: item.c,
        v: item.v
      }))
      await batchUpdateCells(documentId.value, updates)
    } else if (workbookDirty.value) {
      // dirty 标记被设置了但没有可追踪的变更（兜底：全量保存以防万一）
      console.log('====== [saveChanges] workbookDirty 但无可追踪变更，兜底全量保存')
      const sheets = serializeWorkbook()
      if (sheets && sheets.length > 0) {
        const response = await saveWorkbook(documentId.value, sheets)
        const payload = response.data
        if (payload?.success && payload.data?.sheetIdMap) {
          const newIdMap = payload.data.sheetIdMap
          Object.keys(newIdMap).forEach((key) => {
            sheetIdMap[String(key)] = String(newIdMap[key])
          })
          sheetCount.value = payload.data.sheetCount || sheets.length
        } else {
          sheetCount.value = sheets.length
        }
      }
    } else {
      ElMessage.info('没有需要保存的修改')
      return
    }

    // 重置脏标记
    Object.keys(dirtyCells).forEach((key) => delete dirtyCells[key])
    workbookDirty.value = false
    structureDirty.value = false

    // 全量保存后刷新结构快照
    if (structuralChange.changed) {
      captureStructureSnapshot()
    }

    ElMessage.success(structuralChange.changed
      ? `保存成功（全量），共 ${sheetCount.value} 个 Sheet`
      : `保存成功（增量），更新 ${cellDirtyCount} 个单元格`)
  } catch (error) {
    ElMessage.error(`保存失败：${error.message}`)
  } finally {
    saving.value = false
  }
}

function serializeWorkbook() {
  const luckysheet = window.luckysheet

  // ========================================================================
  // ★ 图表保存策略（四步法）
  //
  // 背景：Luckysheet 2.1.13 chartmix 插件的 chart 数据生命周期：
  //   创建图表 → chartmix Vuex store 中存 chartOptions
  //               → sheet.chart[] 中 push 基本信息（但 chartOptions 可能为空）
  //   保存时需要 → 调用 getluckysheetfile(true) 从 store 回填 chartOptions
  //
  // 问题：如果之前某次保存时 chartOptions 丢失（空对象），重新加载后：
  //   - dh() 初始化时 insertToStore({chartOptions: {}}) 可能失败
  //   - chartmix store 中没有该 chart_id 的条目
  //   - getluckysheetfile(true) 遍历到它就崩溃，阻断后续有效图表
  //
  // 解决：先 ECharts 兜底 → 清理僵尸 → 安全刷新 → 序列化
  // ========================================================================

  // ── 第 1 步：获取 sheet 数据的引用 ──
  // getluckysheetfile() 返回 ga.luckysheetfile 的直接引用，
  // 修改返回值的属性就是修改 Luckysheet 内部状态。
  const rawSheets = luckysheet?.getluckysheetfile?.() || luckysheet?.getAllSheets?.()
  const sheets = Array.isArray(rawSheets) ? rawSheets : rawSheets ? [rawSheets] : []

  // ── 第 2 步：ECharts 实例兜底 ──
  // 先尝试从页面上已渲染的 ECharts 实例提取 chartOptions。
  // 这一步必须在 getluckysheetfile(true) 之前执行，
  // 因为 getluckysheetfile(true) 可能覆盖已有的 chartOptions。
  sheets.forEach((sheet) => {
    if (!Array.isArray(sheet.chart) || sheet.chart.length === 0) return
    sheet.chart.forEach((chartItem) => {
      if (!chartItem || !chartItem.chart_id) return
      // 如果已有有效 chartOptions（例如从后端加载的旧有效图表），跳过
      if (chartItem.chartOptions && typeof chartItem.chartOptions === 'object'
          && Object.keys(chartItem.chartOptions).length > 0) {
        return
      }
      // 尝试从 DOM 中的 ECharts 实例提取配置
      try {
        if (!window.echarts) return
        // chartmix DOM 结构：chart_id + "_c" 是外层容器，
        // .luckysheet-modal-dialog-content（id = chart_id）是 ECharts 渲染目标
        const candidateIds = [chartItem.chart_id, `${chartItem.chart_id}_c`]
        let echartsInstance = null
        for (const domId of candidateIds) {
          const dom = document.getElementById(domId)
          if (!dom) continue
          echartsInstance = window.echarts.getInstanceByDom(dom)
          if (echartsInstance) break
          // chartmix 可能在子 div 上创建 ECharts 实例
          const children = dom.querySelectorAll('div[_echarts_instance_], canvas')
          for (const child of children) {
            echartsInstance = window.echarts.getInstanceByDom(child.parentElement || child)
            if (echartsInstance) break
          }
          if (echartsInstance) break
        }
        if (echartsInstance) {
          chartItem.chartOptions = echartsInstance.getOption()
          console.log(`====== [serializeWorkbook] 从 ECharts 实例恢复 chart[${chartItem.chart_id}] 的 chartOptions`)
        }
      } catch (e) {
        console.warn(`====== [serializeWorkbook] ECharts 恢复 chart[${chartItem.chart_id}] 失败:`, e.message)
      }
    })
  })

  // ── 第 3 步：清理僵尸 chart ──
  // 经过 ECharts 兜底后，仍然没有 chartOptions 的 chart 项就是"僵尸数据"：
  // - 之前某次保存时 chartOptions 丢失
  // - 重新加载时 chartmix 也无法渲染它们
  // - DOM 和 ECharts 实例都不存在
  // 从 ga.luckysheetfile 中清理它们，防止后续 getluckysheetfile(true) 崩溃
  let cleanedCount = 0
  sheets.forEach((sheet) => {
    if (!Array.isArray(sheet.chart) || sheet.chart.length === 0) return
    const before = sheet.chart.length
    sheet.chart = sheet.chart.filter((c) => {
      if (!c || !c.chart_id) return false
      const hasValidOptions = c.chartOptions
        && typeof c.chartOptions === 'object'
        && Object.keys(c.chartOptions).length > 0
      return hasValidOptions
    })
    cleanedCount += before - sheet.chart.length
  })
  if (cleanedCount > 0) {
    console.warn(`====== [serializeWorkbook] 清理了 ${cleanedCount} 个无法恢复的僵尸 chart 数据`)
  }

  // ── 第 4 步：安全调用 getluckysheetfile(true) ──
  // 僵尸 chart 已清理，此时 chart 数组中只剩有效图表。
  // 调用 getluckysheetfile(true) 用 chartmix 原生格式的 chartOptions 覆盖
  // （比 ECharts getOption() 的格式更精确，恢复时兼容性更好）
  try {
    luckysheet?.getluckysheetfile?.(true)
    console.log('====== [serializeWorkbook] getluckysheetfile(true) 成功，已用 chartmix 原生格式刷新 chartOptions')
  } catch (e) {
    // 即使仍然失败，第 2 步的 ECharts 兜底已经保证了 chartOptions 不为空
    console.warn('====== [serializeWorkbook] getluckysheetfile(true) 仍然失败:', e.message, '（将使用 ECharts 兜底数据）')
  }

  // ── 第 5 步：序列化 ──
  // 调试日志
  sheets.forEach((s, i) => {
    const chartSummary = (s.chart || []).map((c) => ({
      id: c?.chart_id,
      hasOptions: !!(c?.chartOptions && Object.keys(c.chartOptions).length > 0)
    }))
    if (chartSummary.length > 0) {
      console.log(`====== [serializeWorkbook] Sheet[${i}] chart 摘要:`, JSON.stringify(chartSummary))
    }
  })

  const serialized = sheets.map((sheet, index) => {
    const data = Array.isArray(sheet.data) ? sheet.data : []
    const celldata = data.length > 0 ? dataMatrixToCelldata(data) : normalizeCelldata(sheet.celldata || [])

    // 最终过滤：只保存有有效 chartOptions 的 chart 项
    let safeChart = []
    if (Array.isArray(sheet.chart)) {
      safeChart = sheet.chart
        .filter((c) => c && c.chart_id && c.chartOptions && Object.keys(c.chartOptions).length > 0)
        .map((c) => {
          if (c && !c.chartType) {
            let chartType = 'line'
            const series = c.chartOptions?.series
            if (Array.isArray(series) && series.length > 0) {
              chartType = series[0]?.type || 'line'
            }
            c.chartType = `echarts|${chartType}|default`
          }
          return { ...c }
        })
    }

    return {
      ...sheet,
      name: sheet.name || `Sheet${index + 1}`,
      order: sheet.order ?? index,
      row: sheet.row || data.length || calcMaxRow(celldata),
      column: sheet.column || calcMaxColumn(data, celldata),
      config: sheet.config || { merge: {}, columnlen: {}, rowlen: {} },
      celldata,
      chart: safeChart
    }
  })
  console.log('====== [serializeWorkbook] 准备提交的 sheets 数据:', serialized)
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

.doc-meta .doc-name {
  cursor: pointer;
  transition: color 0.15s;
}

.doc-meta .doc-name:hover {
  color: var(--dl-accent-strong);
}

.doc-name-edit {
  display: flex;
  align-items: center;
}

.rename-input {
  width: 280px;
  max-width: 200px;
  padding: 4px 8px;
  border: 1px solid var(--dl-accent-strong);
  border-radius: 4px;
  font-size: 14px;
  font-weight: 600;
  outline: none;
  background: #fff;
  box-shadow: 0 0 0 2px rgba(47, 125, 87, 0.12);
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
