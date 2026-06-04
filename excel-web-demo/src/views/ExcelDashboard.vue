<template>
  <div class="dashboard">
    <!-- 顶部栏 -->
    <div class="header">
      <h1 class="title">Excel 协作编辑 Demo</h1>
      <el-button type="primary" size="large" @click="handleUpload">
        上传 Excel 文件
      </el-button>
      <input
        ref="fileInput"
        type="file"
        accept=".xlsx,.xls"
        style="display:none"
        @change="onFileChange"
      />
    </div>

    <!-- 加载提示 -->
    <el-alert
      v-if="uploading"
      title="正在解析文件..."
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />

    <!-- 文档列表 -->
    <el-table :data="documents" border stripe highlight-current-row
              v-loading="loading" element-loading-text="加载中...">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="文档名称" min-width="200">
        <template #default="{ row }">
          <el-link type="primary" @click="openDocument(row.id)">
            {{ row.name }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="Sheet 数" width="100">
        <template #default="{ row }">
          {{ row.sheetCount }} 个
        </template>
      </el-table-column>
      <el-table-column label="文件大小" width="120">
        <template #default="{ row }">
          {{ formatSize(row.fileSize) }}
        </template>
      </el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="180">
        <template #default="{ row }">
          {{ formatTime(row.updateTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button size="small" type="primary"
                     @click="openDocument(row.id)">编辑</el-button>
          <el-button size="small" type="success"
                     @click="handleDownload(row.id)">导出</el-button>
          <el-button size="small" type="danger"
                     @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      style="margin-top: 16px; text-align: center"
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page.sync="pageNum"
      @current-change="loadDocuments"
    />
  </div>
</template>

<script>
import { getDocumentList, uploadExcel, downloadExcel, deleteDocument } from '@/api/excel'

export default {
  name: 'ExcelDashboard',
  data() {
    return {
      documents: [],
      loading: false,
      uploading: false,
      total: 0,
      pageNum: 1,
      pageSize: 20
    }
  },
  mounted() {
    this.loadDocuments()
  },
  methods: {
    /* ========== 加载文档列表 ========== */
    async loadDocuments() {
      this.loading = true
      try {
        const res = await getDocumentList(this.pageNum, this.pageSize)
        if (res.data && res.data.success) {
          const data = res.data.data
          this.documents = data.records || []
          this.total = data.total || 0
        }
      } catch (e) {
        this.$message.error('加载失败: ' + e.message)
      } finally {
        this.loading = false
      }
    },

    /* ========== 上传 Excel ========== */
    handleUpload() {
      this.$refs.fileInput.click()
    },

    async onFileChange(e) {
      const file = e.target.files[0]
      if (!file) return

      // 检查扩展名
      if (!/\.(xlsx|xls)$/i.test(file.name)) {
        this.$message.warning('请选择 .xlsx 或 .xls 文件')
        this.$refs.fileInput.value = ''
        return
      }

      this.uploading = true
      try {
        const res = await uploadExcel(file)
        if (res.data && res.data.success) {
          const data = res.data.data
          this.$message.success('上传成功！' + data.sheetCount + ' 个 Sheet 已解析')

          // 自动跳转到编辑页
          this.$router.push({ name: 'SheetEditor', params: { id: data.documentId } })
        } else {
          this.$message.error('上传失败: ' + (res.data ? res.data.message : '未知错误'))
        }
      } catch (e) {
        this.$message.error('上传异常: ' + e.message)
      } finally {
        this.uploading = false
        this.$refs.fileInput.value = ''
      }
    },

    /* ========== 打开文档编辑 ========== */
    openDocument(id) {
      this.$router.push({ name: 'SheetEditor', params: { id } })
    },

    /* ========== 导出下载 ========== */
    async handleDownload(id) {
      try {
        this.$message.info('正在导出...')
        await downloadExcel(id)
        this.$message.success('导出成功')
      } catch (e) {
        this.$message.error('导出失败: ' + e.message)
      }
    },

    /* ========== 删除文档 ========== */
    handleDelete(row) {
      this.$confirm(`确定要删除文档 "${row.name}" 吗？此操作不可恢复。`, '危险提示', {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          await deleteDocument(row.id)
          this.$message.success('删除成功')
          // 如果当前页只有一条数据，且不是第一页，删除后应该退回上一页
          if (this.documents.length === 1 && this.pageNum > 1) {
            this.pageNum -= 1
          }
          this.loadDocuments()
        } catch (e) {
          this.$message.error('删除失败: ' + e.message)
        }
      }).catch(() => {
        // 用户点击取消，忽略
      })
    },

    /* ========== 工具函数 ========== */
    formatSize(bytes) {
      if (!bytes || bytes === 0) return '0 B'
      const units = ['B', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(1024))
      return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i]
    },

    formatTime(time) {
      if (!time) return '-'
      return time.replace('T', ' ').substring(0, 19)
    }
  }
}
</script>

<style scoped>
.dashboard {
  max-width: 1000px;
  margin: 40px auto;
  padding: 0 20px;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.title {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}
</style>
