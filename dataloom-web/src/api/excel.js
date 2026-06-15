import axios from 'axios'

const api = axios.create({
  baseURL: '/api/excel',
  timeout: 300000
})

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

export function getDocumentList(pageNum = 1, pageSize = 20) {
  return api.get('/document/list', {
    params: { pageNum, pageSize }
  })
}

export function getDocument(id) {
  return api.get(`/document/${id}`)
}

export function loadAllCelldata(docId, sheetId) {
  return api.get(`/document/${docId}/sheet/${sheetId}/all`)
}

export function loadChunks(docId, sheetId, chunkStart = 0, chunkEnd = 0) {
  return api.get(`/document/${docId}/sheet/${sheetId}/chunks`, {
    params: { chunkStart, chunkEnd }
  })
}

export function updateCell(docId, sheetId, r, c, v) {
  return api.put(`/document/${docId}/sheet/${sheetId}/cell`, { r, c, v })
}

export function batchUpdateCells(docId, updates) {
  return api.put(`/document/${docId}/cells/batch`, updates)
}

export function saveWorkbook(docId, sheets) {
  return api.put(`/document/${docId}/workbook`, { sheets })
}

export async function downloadExcel(id) {
  const response = await api.get(`/${id}/export`, { responseType: 'blob' })
  const disposition = response.headers['content-disposition']
  let fileName = `document_${id}.xlsx`

  if (disposition) {
    const encoded = disposition.match(/filename\*=UTF-8''(.+)/)
    const quoted = disposition.match(/filename="?([^"]+)"?/)
    if (encoded) fileName = decodeURIComponent(encoded[1])
    else if (quoted) fileName = decodeURIComponent(quoted[1])
  }

  const url = window.URL.createObjectURL(new Blob([response.data]))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  window.URL.revokeObjectURL(url)
}

export function renameDocument(id, newName) {
  return api.put(`/document/${id}/name`, { name: newName })
}

export function deleteDocument(id) {
  return api.delete(`/document/${id}`)
}
