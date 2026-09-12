<template>
  <div class="student-manage">
    <!-- 搜索栏 -->
    <div class="search-bar">
      <el-select v-model="query.classId" placeholder="全部班级" clearable filterable class="filter-select" @change="handleSearch">
        <el-option v-for="c in allClass" :key="c.id" :label="classLabel(c)" :value="c.id" />
      </el-select>
      <el-input
        v-model="query.keyword"
        placeholder="学号 / 姓名 / 家长 / 家长电话"
        clearable
        class="search-input"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <!-- 工具栏 -->
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增学生</el-button>
      <el-button type="success" :icon="Upload" @click="openImportDialog">批量导入</el-button>
      <el-button :icon="Download" @click="handleDownloadTemplate">下载导入模板</el-button>
    </div>

    <!-- 学生表格 -->
    <el-table v-loading="loading" :data="tableData" stripe>
      <el-table-column prop="studentNo" label="学号" min-width="130" />
      <el-table-column prop="studentName" label="姓名" min-width="100" />
      <el-table-column label="性别" width="70">
        <template #default="{ row }">{{ genderText(row.gender) }}</template>
      </el-table-column>
      <el-table-column prop="className" label="班级" min-width="120" />
      <el-table-column prop="parentName" label="家长姓名" min-width="100">
        <template #default="{ row }">{{ row.parentName || '—' }}</template>
      </el-table-column>
      <el-table-column prop="parentPhone" label="家长电话" min-width="130">
        <template #default="{ row }">{{ row.parentPhone || '—' }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pagination">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="fetchList"
        @current-change="fetchList"
      />
    </div>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑学生' : '新增学生'" width="500px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="学号" prop="studentNo">
          <el-input v-model="form.studentNo" placeholder="请输入学号" />
        </el-form-item>
        <el-form-item label="姓名" prop="studentName">
          <el-input v-model="form.studentName" placeholder="请输入学生姓名" />
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender">
            <el-radio :value="1">男</el-radio>
            <el-radio :value="0">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="班级" prop="classId">
          <el-select v-model="form.classId" placeholder="请选择班级" filterable style="width: 100%">
            <el-option v-for="c in allClass" :key="c.id" :label="classLabel(c)" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="家长姓名">
          <el-input v-model="form.parentName" placeholder="请输入家长姓名" />
        </el-form-item>
        <el-form-item label="家长电话">
          <el-input v-model="form.parentPhone" placeholder="请输入家长电话" />
        </el-form-item>
        <el-form-item label="出生日期">
          <el-date-picker v-model="form.birthDate" type="date" placeholder="选择日期" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入对话框 -->
    <el-dialog v-model="importVisible" title="批量导入学生" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="导入班级" required>
          <el-select v-model="importClassId" placeholder="请先选择班级" filterable style="width: 100%">
            <el-option v-for="c in allClass" :key="c.id" :label="classLabel(c)" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="选择文件">
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            accept=".xlsx,.xls"
            :on-change="onFileChange"
            :on-exceed="onExceed"
          >
            <el-button :icon="Upload">选择 Excel 文件</el-button>
            <template #tip>
              <div class="upload-tip">列顺序：学号、姓名、性别（男/女）、家长姓名、家长电话、备注</div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>

      <!-- 导入结果 -->
      <el-alert
        v-if="importResult"
        :title="`导入完成：成功 ${importResult.successCount} 条，失败 ${importResult.failCount} 条`"
        :type="importResult.failCount > 0 ? 'warning' : 'success'"
        :closable="false"
        class="import-result"
      />
      <div v-if="importResult && importResult.errors.length" class="error-list">
        <div v-for="(err, idx) in importResult.errors" :key="idx" class="error-item">{{ err }}</div>
      </div>

      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!importFile || !importClassId" @click="handleImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus, Upload, Download, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadInstance, type UploadFile } from 'element-plus'
import {
  getStudentList, saveStudent, updateStudent, deleteStudent,
  importStudents, downloadStudentTemplate
} from '@/api/student'
import { getAllClass } from '@/api/clazz'

const loading = ref(false)
const submitting = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const allClass = ref<any[]>([])

const query = reactive({ pageNum: 1, pageSize: 10, classId: undefined as number | undefined, keyword: '' })

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getStudentList(query)
    tableData.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

async function fetchAllClass() {
  const res: any = await getAllClass()
  allClass.value = res.data
}

function classLabel(c: any): string {
  return c.gradeName ? `${c.gradeName} · ${c.className}` : c.className
}

function genderText(gender: number | null): string {
  if (gender === 1) return '男'
  if (gender === 0) return '女'
  return '—'
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.classId = undefined
  query.keyword = ''
  query.pageNum = 1
  fetchList()
}

// ==================== 新增/编辑 ====================
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<any>({
  id: null, studentNo: '', studentName: '', gender: 1, classId: null,
  parentName: '', parentPhone: '', birthDate: '', remark: ''
})
const formRules: FormRules = {
  studentNo: [{ required: true, message: '请输入学号', trigger: 'blur' }],
  studentName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  classId: [{ required: true, message: '请选择班级', trigger: 'change' }]
}

function openDialog(row?: any) {
  Object.assign(form, {
    id: row?.id ?? null,
    studentNo: row?.studentNo ?? '',
    studentName: row?.studentName ?? '',
    gender: row?.gender ?? 1,
    classId: row?.classId ?? null,
    parentName: row?.parentName ?? '',
    parentPhone: row?.parentPhone ?? '',
    birthDate: row?.birthDate ?? '',
    remark: row?.remark ?? ''
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (form.id) {
        await updateStudent(form)
        ElMessage.success('修改成功')
      } else {
        await saveStudent(form)
        ElMessage.success('新增成功')
      }
      dialogVisible.value = false
      fetchList()
    } finally {
      submitting.value = false
    }
  })
}

async function handleDelete(row: any) {
  await ElMessageBox.confirm(`确定删除学生「${row.studentName}」吗？`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  })
  await deleteStudent(row.id)
  ElMessage.success('删除成功')
  fetchList()
}

// ==================== 批量导入 ====================
const importVisible = ref(false)
const uploadRef = ref<UploadInstance>()
const importFile = ref<File | null>(null)
const importClassId = ref<number | null>(null)
const importResult = ref<any>(null)

function openImportDialog() {
  importFile.value = null
  importClassId.value = null
  importResult.value = null
  importVisible.value = true
}

function onFileChange(file: UploadFile) {
  importFile.value = file.raw ?? null
}

function onExceed(files: File[]) {
  uploadRef.value?.clearFiles()
  const file = files[0]
  uploadRef.value?.handleStart(file)
}

async function handleImport() {
  if (!importFile.value || !importClassId.value) return
  submitting.value = true
  try {
    const res: any = await importStudents(importFile.value, importClassId.value)
    importResult.value = res.data
    ElMessage.success('导入处理完成')
    fetchList()
  } finally {
    submitting.value = false
  }
}

async function handleDownloadTemplate() {
  const blob: any = await downloadStudentTemplate()
  const url = window.URL.createObjectURL(new Blob([blob]))
  const link = document.createElement('a')
  link.href = url
  link.download = '学生导入模板.xlsx'
  link.click()
  window.URL.revokeObjectURL(url)
}

onMounted(() => {
  fetchAllClass()
  fetchList()
})
</script>

<style scoped lang="scss">
.student-manage {
  padding: 20px;
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;

  .filter-select {
    width: 200px;
  }

  .search-input {
    width: 280px;
  }
}

.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.upload-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.import-result {
  margin-bottom: 12px;
}

.error-list {
  max-height: 160px;
  overflow-y: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px 12px;

  .error-item {
    font-size: 12px;
    color: #e6a23c;
    line-height: 1.8;
  }
}
</style>
