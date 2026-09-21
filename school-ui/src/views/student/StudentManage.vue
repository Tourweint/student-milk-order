<template>
  <div class="student-manage">
    <!-- 搜索栏 -->
    <div class="search-bar">
      <GradeClassFilter v-model="query.classId" @change="handleSearch" />
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
      <el-table-column label="禁忌标签" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ allergenLabel(row.allergyTags, 'student') }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="warning" :loading="settleLoading" @click="handleSettle(row)">毕业清算</el-button>
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
            <el-option-group v-for="g in groupedClass" :key="g.grade" :label="g.grade">
              <el-option v-for="c in g.classes" :key="c.id" :label="c.className" :value="c.id" />
            </el-option-group>
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
        <el-form-item label="禁忌标签">
          <el-select
            v-model="form.allergyTags" multiple clearable placeholder="如：乳糖不耐（下单时仅软提示，不拦截）"
            style="width: 100%"
          >
            <el-option v-for="o in allergenOptions" :key="o.code" :label="o.studentText" :value="o.code" />
          </el-select>
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
            <el-option-group v-for="g in groupedClass" :key="g.grade" :label="g.grade">
              <el-option v-for="c in g.classes" :key="c.id" :label="c.className" :value="c.id" />
            </el-option-group>
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

    <!-- 毕业清算结果（退款域 R8：逐单独立事务，单笔失败不阻塞其余） -->
    <el-dialog v-model="settleVisible" :title="`毕业清算结果 - ${settleStudentName}`" width="760px" destroy-on-close>
      <el-descriptions v-if="settleResult" :column="3" border size="small">
        <el-descriptions-item label="待清算订单">{{ settleResult.totalOrders }}</el-descriptions-item>
        <el-descriptions-item label="取消待支付">{{ settleResult.cancelledCount }}</el-descriptions-item>
        <el-descriptions-item label="已退款">{{ settleResult.refundedCount }}</el-descriptions-item>
        <el-descriptions-item label="无需处理">{{ settleResult.skippedCount }}</el-descriptions-item>
        <el-descriptions-item label="处理失败">{{ settleResult.failedCount }}</el-descriptions-item>
        <el-descriptions-item label="退款合计">
          <span class="text-price">¥{{ Number(settleResult.totalRefundAmount || 0).toFixed(2) }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <div v-if="settleResult && !settleResult.totalOrders" class="settle-empty">
        该学生名下没有待清算订单（待支付/已支付/配送中），无需处理。
      </div>
      <el-table v-if="settleResult && settleResult.totalOrders" :data="settleResult.items" size="small" stripe class="settle-table">
        <el-table-column prop="orderNo" label="订单号" min-width="180" />
        <el-table-column label="处理结果" width="110">
          <template #default="{ row }">
            <el-tag :type="settleActionTag(row.action)" size="small">{{ settleActionText(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="refundedBoxes" label="退款盒数" width="90">
          <template #default="{ row }">{{ row.refundedBoxes ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="退款金额" width="100">
          <template #default="{ row }">
            {{ row.refundAmount == null ? '—' : '¥' + Number(row.refundAmount).toFixed(2) }}
          </template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="200" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button type="primary" @click="settleVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus, Upload, Download, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadInstance, type UploadFile } from 'element-plus'
import {
  getStudentList, saveStudent, updateStudent, deleteStudent,
  importStudents, downloadStudentTemplate
} from '@/api/student'
import { getAllClass } from '@/api/clazz'
import { ensureAllergenOptions, allergenLabel, parseAllergenCodes } from '@/utils/allergen'
import { settleStudent, type SettlementResultVO } from '@/api/refund'
import GradeClassFilter from '@/views/clazz/components/GradeClassFilter.vue'

const loading = ref(false)
const submitting = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const allClass = ref<any[]>([])

// ==================== 毕业清算（退款域 R8） ====================
const settleVisible = ref(false)
const settleLoading = ref(false)
const settleStudentName = ref('')
const settleResult = ref<SettlementResultVO | null>(null)

const settleActionText = (action: string) => {
  const map: Record<string, string> = {
    CANCELLED_UNPAID: '取消待支付',
    REFUNDED: '已退款',
    SKIPPED: '无需处理',
    FAILED: '处理失败'
  }
  return map[action] ?? action
}
const settleActionTag = (action: string): any => {
  const map: Record<string, string> = {
    CANCELLED_UNPAID: 'info', REFUNDED: 'success', SKIPPED: 'warning', FAILED: 'danger'
  }
  return map[action] ?? 'info'
}

/** 毕业清算：待支付单取消、已支付/配送中单按可退期次退款（幂等，可重复执行） */
async function handleSettle(row: any) {
  await ElMessageBox.confirm(
    `毕业清算会处理「${row.studentName}」名下全部未完成订单：待支付订单将被取消，`
      + '已支付/配送中订单按可退期次退款（模拟通道即时到账，作废期次不再配送）。'
      + '完成后该学生不应再有未完成订单；重复执行只会得到「无可清算订单」。确认清算吗？',
    '毕业清算确认',
    { confirmButtonText: '确定清算', cancelButtonText: '取消', type: 'warning' }
  )
  settleLoading.value = true
  try {
    const res: any = await settleStudent(row.id)
    settleStudentName.value = row.studentName
    settleResult.value = res.data
    settleVisible.value = true
    fetchList()
  } finally {
    settleLoading.value = false
  }
}

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

/** 按年级分组，供弹窗内班级下拉使用 */
const groupedClass = computed(() => {
  const groups: { grade: string; classes: any[] }[] = []
  allClass.value.forEach((c) => {
    const grade = c.gradeName || '未分年级'
    let g = groups.find((x) => x.grade === grade)
    if (!g) {
      g = { grade, classes: [] }
      groups.push(g)
    }
    g.classes.push(c)
  })
  return groups
})

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
  parentName: '', parentPhone: '', birthDate: '', allergyTags: [] as string[], remark: ''
})
/** 受控过敏原选项（后端下发，学生侧文案） */
const allergenOptions = ref<any[]>([])
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
    allergyTags: parseAllergenCodes(row?.allergyTags),
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
      // allergyTags 在表单里是数组（多选），落库是逗号分隔串；空数组 → 空串（显式清空，能真正写库）
      const payload = { ...form, allergyTags: (form.allergyTags || []).join(',') }
      if (form.id) {
        await updateStudent(payload)
        ElMessage.success('修改成功')
      } else {
        await saveStudent(payload)
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
  // Element Plus 的 UploadRawFile 在 File 上扩展 uid，运行时由组件内部补全
  uploadRef.value?.handleStart(file as any)
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

onMounted(async () => {
  fetchAllClass()
  fetchList()
  // 过敏原选项失败不阻断页面：只影响标签列的文案与下拉可选项
  try {
    allergenOptions.value = await ensureAllergenOptions()
  } catch (e) {
    console.error('加载过敏原选项失败', e)
  }
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

/* 毕业清算结果 */
.settle-empty {
  margin-top: 12px;
  color: #909399;
  font-size: 13px;
}
.settle-table {
  margin-top: 12px;
}
.text-price {
  color: #f56c6c;
  font-weight: 600;
}
</style>
