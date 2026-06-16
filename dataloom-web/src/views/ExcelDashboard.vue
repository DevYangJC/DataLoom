<template>
  <main class="dl-page dashboard-page">
    <section class="dl-shell dashboard-shell">
      <header class="workspace-header">
        <div>
          <p class="eyebrow">DataLoom Workspace</p>
          <h1>Excel 在线协作编辑</h1>
          <p class="summary">支持 Excel 文档上传、解析、在线编辑和导出。后端技术栈为 Spring Boot，前端技术栈为 Vue 3 + Vite + Element Plus。</p>
        </div>

        <div class="header-actions">
          <el-button :icon="Refresh" :loading="loading" @click="loadDocuments">刷新</el-button>
          <el-button type="primary" :icon="UploadFilled" :loading="uploading" @click="selectFile">
            上传 Excel
          </el-button>
          <input
            ref="fileInput"
            class="file-input"
            type="file"
            accept=".xlsx,.xls"
            @change="handleFileChange"
          />
        </div>
      </header>

      <section class="metrics-row">
        <div class="metric">
          <span>文档总数</span>
          <strong>{{ total }}</strong>
        </div>
        <div class="metric">
          <span>当前页</span>
          <strong>{{ pageNum }}</strong>
        </div>
        <div class="metric metric-accent">
          <span>后端端口</span>
          <strong>9191</strong>
        </div>
      </section>

      <el-card class="document-panel" shadow="never">
        <template #header>
          <div class="panel-header">
            <div>
              <h2>文档列表</h2>
              <p>点击文档名进入在线编辑器。</p>
            </div>
            <el-tag v-if="uploading" type="warning" effect="light">正在解析上传文件</el-tag>
          </div>
        </template>

        <el-table
          v-loading="loading"
          :data="documents"
          stripe
          class="document-table"
          empty-text="还没有文档，先上传一个 Excel 文件吧。"
        >
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="文档名称" min-width="260">
            <template #default="{ row }">
              <button class="doc-link" @click="openDocument(row.id)">
                <span>{{ row.name }}</span>
              </button>
            </template>
          </el-table-column>
          <el-table-column label="Sheet" width="100">
            <template #default="{ row }">{{ row.sheetCount || 0 }} 个</template>
          </el-table-column>
          <el-table-column label="文件大小" width="130">
            <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="更新时间" width="190">
            <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="310" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" :icon="EditPen" @click="openDocument(row.id)">
                编辑
              </el-button>
              <el-button size="small" :icon="Edit" @click="renameDocumentAction(row)">
                重命名
              </el-button>
              <el-button size="small" type="danger" :icon="Delete" @click="removeDocument(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination-wrap">
          <el-pagination
            v-model:current-page="pageNum"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next"
            background
            @current-change="loadDocuments"
          />
        </div>
      </el-card>
    </section>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Delete, Edit, EditPen, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteDocument, getDocumentList, renameDocument, uploadExcel } from '@/api/excel'

const router = useRouter()
const fileInput = ref(null)
const documents = ref([])
const loading = ref(false)
const uploading = ref(false)
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20

onMounted(() => {
  loadDocuments()
})

async function loadDocuments() {
  loading.value = true
  try {
    const response = await getDocumentList(pageNum.value, pageSize)
    const payload = response.data
    if (!payload?.success) throw new Error(payload?.message || '接口返回异常')

    documents.value = payload.data?.records || []
    total.value = payload.data?.total || 0
  } catch (error) {
    ElMessage.error(`加载文档失败：${error.message}`)
  } finally {
    loading.value = false
  }
}

function selectFile() {
  fileInput.value?.click()
}

async function handleFileChange(event) {
  const file = event.target.files?.[0]
  if (!file) return

  if (!/\.(xlsx|xls)$/i.test(file.name)) {
    ElMessage.warning('请选择 .xlsx 或 .xls 文件')
    event.target.value = ''
    return
  }

  uploading.value = true
  try {
    const response = await uploadExcel(file)
    const payload = response.data
    if (!payload?.success) throw new Error(payload?.message || '上传失败')

    const documentId = payload.data?.documentId
    ElMessage.success(`上传成功，已解析 ${payload.data?.sheetCount || 0} 个 Sheet`)
    if (documentId) router.push({ name: 'SheetEditor', params: { id: documentId } })
    else loadDocuments()
  } catch (error) {
    ElMessage.error(`上传失败：${error.message}`)
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

function openDocument(id) {
  router.push({ name: 'SheetEditor', params: { id } })
}

async function renameDocumentAction(row) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新名称', '重命名文档', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      inputValue: row.name,
      inputValidator: (val) => (val && val.trim() ? true : '名称不能为空'),
      inputErrorMessage: '名称不能为空'
    })
    if (!value || !value.trim()) return

    await renameDocument(row.id, value.trim())
    row.name = value.trim()
    ElMessage.success('重命名成功')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(`重命名失败：${error.message}`)
    }
  }
}

async function removeDocument(row) {
  try {
    await ElMessageBox.confirm(`确定删除文档”${row.name}”吗？`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })

    await deleteDocument(row.id)
    ElMessage.success('删除成功')
    if (documents.value.length === 1 && pageNum.value > 1) pageNum.value -= 1
    loadDocuments()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(`删除失败：${error.message}`)
    }
  }
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / Math.pow(1024, index)).toFixed(1)} ${units[index]}`
}

function formatTime(time) {
  if (!time) return '-'
  return String(time).replace('T', ' ').substring(0, 19)
}
</script>

<style scoped>
.dashboard-shell {
  padding: 36px 0 48px;
}

.workspace-header {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 24px;
  align-items: end;
  padding: 30px 0 26px;
}

.eyebrow {
  margin: 0 0 8px;
  color: var(--dl-accent-strong);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  font-size: clamp(30px, 4vw, 48px);
  line-height: 1.08;
  letter-spacing: 0;
}

.summary {
  max-width: 700px;
  margin: 14px 0 0;
  color: var(--dl-muted);
  font-size: 15px;
  line-height: 1.7;
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.file-input {
  display: none;
}

.metrics-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.metric {
  min-height: 86px;
  padding: 16px 18px;
  border: 1px solid var(--dl-line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--dl-shadow);
}

.metric span {
  display: block;
  color: var(--dl-muted);
  font-size: 13px;
}

.metric strong {
  display: block;
  margin-top: 6px;
  font-size: 28px;
  line-height: 1;
}

.metric-accent strong {
  color: var(--dl-warm);
}

.document-panel {
  border: 1px solid var(--dl-line);
  box-shadow: var(--dl-shadow);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.panel-header h2 {
  margin: 0;
  font-size: 18px;
}

.panel-header p {
  margin: 6px 0 0;
  color: var(--dl-muted);
  font-size: 13px;
}

.document-table {
  width: 100%;
}

.doc-link {
  max-width: 100%;
  padding: 0;
  border: 0;
  color: var(--dl-accent-strong);
  background: transparent;
  cursor: pointer;
  font-weight: 650;
  text-align: left;
}

.doc-link span {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  padding-top: 18px;
}

@media (max-width: 760px) {
  .dl-shell {
    width: min(100% - 24px, 1180px);
  }

  .workspace-header {
    grid-template-columns: 1fr;
  }

  .header-actions {
    justify-content: stretch;
  }

  .header-actions .el-button {
    flex: 1;
  }

  .metrics-row {
    grid-template-columns: 1fr;
  }
}
</style>
