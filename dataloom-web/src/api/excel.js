import axios from 'axios'

const api = axios.create({
  baseURL: '/api/excel',
  timeout: 300000
})

// =========================================================
// ① 文件上传
// =========================================================

/** 上传 Excel 文件（支持 .xlsx / .xls），后端 POI 流式解析后分块入库 */
export function uploadExcel(file, onProgress) {
  const formData = new FormData()
  formData.append('file', file)

  return api.post('/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    }
  })
}

// =========================================================
// ② 文档查询
// =========================================================

/** 文档列表 — 仅元数据，不含单元格数据 */
export function getDocumentList(pageNum = 1, pageSize = 20) {
  return api.get('/document/list', {
    params: { pageNum, pageSize }
  })
}

/** 文档详情 — 含各 Sheet 元信息（config/chart/images 等），不含 celldata */
export function getDocument(id) {
  return api.get(`/document/${id}`)
}

// =========================================================
// ③ 数据加载
// =========================================================

/** 加载指定 Sheet 的全部 celldata（合并所有分块后返回） */
export function loadAllCelldata(docId, sheetId) {
  return api.get(`/document/${docId}/sheet/${sheetId}/all`)
}

// =========================================================
// ④ 数据写入
// =========================================================

/** 批量增量更新单元格 — 仅写变更涉及的 Chunk，适合纯数据修改场景 */
export function batchUpdateCells(docId, updates) {
  return api.put(`/document/${docId}/cells/batch`, updates)
}

/** 全量快照保存 — 替换整个工作簿（结构/图片/图表变更时必须走此接口） */
export function saveWorkbook(docId, sheets) {
  return api.put(`/document/${docId}/workbook`, { sheets })
}

// =========================================================
// ⑤ 文档管理
// =========================================================

/** 重命名文档 */
export function renameDocument(id, newName) {
  return api.put(`/document/${id}/name`, { name: newName })
}

/** 删除文档 — 软删除主记录 + 级联清理 Sheet 和 Chunk */
export function deleteDocument(id) {
  return api.delete(`/document/${id}`)
}
