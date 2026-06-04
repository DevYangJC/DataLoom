import axios from 'axios'

// 后端地址（开发时通过 vue.config.js 代理到 localhost:9191）
const API_BASE = '/api/excel'

const api = axios.create({
  baseURL: API_BASE,
  timeout: 300000  // 十万级数据加载可能需要更长时间
})

/**
 * 上传 Excel 文件
 * @param {File} file - 上传的文件对象
 * @param {Function} onProgress - 上传进度回调 (percent: 0~100)
 * @returns {Promise} - { documentId, name, sheetCount, sheets(元信息) }
 */
export function uploadExcel(file, onProgress) {
  const formData = new FormData()
  formData.append('file', file)
  return api.post('/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: e => {
      if (onProgress && e.total) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    }
  })
}

/**
 * 获取文档列表（分页）
 * @returns {Promise} - { total, pages, current, records[] }
 */
export function getDocumentList(pageNum = 1, pageSize = 20) {
  return api.get('/document/list', { params: { pageNum, pageSize } })
}

/**
 * 获取文档元信息（含各 Sheet 元信息，不含 celldata）
 * 返回结构：{ id, name, sheetCount, sheets: [{ sheetId, sheetName, totalRows, totalCols, chunkCount, mergeConfig, columnLen }] }
 * @param {Number} id - 文档 ID
 */
export function getDocument(id) {
  return api.get(`/document/${id}`)
}

/**
 * 加载指定 Sheet 的全量 celldata（适用于小数据量，谨慎用于大文件）
 * 返回结构：{ sheetId, celldata: [...], cellCount }
 * @param {Number} docId   - 文档 ID
 * @param {Number} sheetId - Sheet ID
 */
export function loadAllCelldata(docId, sheetId) {
  return api.get(`/document/${docId}/sheet/${sheetId}/all`)
}

/**
 * 按块序号范围加载 celldata（分块懒加载，适合十万级大数据）
 * 返回结构：{ sheetId, chunkStart, chunkEnd, rowStart, rowEnd, celldata: [...], cellCount }
 * @param {Number} docId      - 文档 ID
 * @param {Number} sheetId    - Sheet ID
 * @param {Number} chunkStart - 起始块序号（0 起始）
 * @param {Number} chunkEnd   - 结束块序号（含）
 */
export function loadChunks(docId, sheetId, chunkStart = 0, chunkEnd = 0) {
  return api.get(`/document/${docId}/sheet/${sheetId}/chunks`, {
    params: { chunkStart, chunkEnd }
  })
}

/**
 * 增量更新单个单元格
 * @param {Number} docId   - 文档 ID
 * @param {Number} sheetId - Sheet ID
 * @param {Number} r       - 行号
 * @param {Number} c       - 列号
 * @param {Object} v       - 单元格数据对象 (传 null 代表清空)
 */
export function updateCell(docId, sheetId, r, c, v) {
  return api.put(`/document/${docId}/sheet/${sheetId}/cell`, { r, c, v })
}

/**
 * 批量更新单元格（手动保存）
 * @param {Number} docId   - 文档 ID
 * @param {Array}  updates - 修改记录 [{sheetId, r, c, v}]
 */
export function batchUpdateCells(docId, updates) {
  return api.put(`/document/${docId}/cells/batch`, updates)
}

/**
 * 导出为 .xlsx 文件（流式下载）
 * @param {Number} id - 文档 ID
 */
export function downloadExcel(id) {
  return api.get(`/${id}/export`, { responseType: 'blob' })
    .then(res => {
      const disposition = res.headers['content-disposition']
      let fileName = `document_${id}.xlsx`
      if (disposition) {
        const match = disposition.match(/filename\*=UTF-8''(.+)/)
        if (match) fileName = decodeURIComponent(match[1])
      }
      const url = window.URL.createObjectURL(new Blob([res.data]))
      const a = document.createElement('a')
      a.href = url
      a.download = fileName
      a.click()
      window.URL.revokeObjectURL(url)
    })
}

/**
 * 更新文档名称
 * @param {Number} id      - 文档 ID
 * @param {String} newName - 新名称
 */
export function renameDocument(id, newName) {
  return api.put(`/document/${id}/name`, { name: newName })
}

/**
 * 删除文档
 * @param {Number} id - 文档 ID
 */
export function deleteDocument(id) {
  return api.delete(`/document/${id}`)
}
