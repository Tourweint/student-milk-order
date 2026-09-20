<template>
  <div class="record-tab">
    <!-- 今日待签收提醒 + 一键签收（次日凌晨未签收将自动签收兜底） -->
    <div v-if="pendingSign.total > 0" class="pending-bar">
      <el-icon class="pending-icon"><Bell /></el-icon>
      <span class="pending-text">
        今日待签收 <b>{{ pendingSign.total }}</b> 条<span v-if="pendingSign.classes.length">（{{ classText }}）</span>，
        请及时签收；次日凌晨未签收将自动签收兜底。
      </span>
      <el-button type="warning" size="small" :loading="batchSigning" @click="handleBatchSign(today())">一键签收今日</el-button>
    </div>

    <!-- 筛选 -->
    <div class="toolbar">
      <el-date-picker v-model="queryDate" type="date" placeholder="配送日期" value-format="YYYY-MM-DD" class="filter-item" />
      <GradeClassFilter v-model="query.classId" @change="handleClassChange" />
      <el-select v-model="query.studentId" placeholder="全部学生" clearable filterable class="filter-item">
        <el-option v-for="s in studentList" :key="s.id" :label="s.studentName" :value="s.id" />
      </el-select>
      <el-select v-model="query.signStatus" placeholder="全部签收状态" clearable class="filter-item">
        <el-option v-for="s in signOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-button type="primary" @click="fetchList">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
      <el-button type="success" :loading="batchSigning" @click="handleBatchSign">批量签收</el-button>
    </div>

    <!-- 表格 -->
    <el-table v-loading="loading" :data="tableData" stripe>
      <el-table-column prop="taskNo" label="任务编号" min-width="180" />
      <el-table-column prop="deliveryDate" label="配送日期" width="120" />
      <el-table-column prop="className" label="班级" width="110" />
      <el-table-column prop="studentName" label="学生" width="90" />
      <el-table-column prop="productName" label="奶品" min-width="110" />
      <el-table-column prop="spec" label="规格" width="90" />
      <el-table-column prop="quantity" label="数量" width="70" />
      <el-table-column label="签收状态" width="100">
        <template #default="{ row }">
          <el-tag :type="signTag(row.signStatus)" size="small">{{ signText(row.signStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="signTime" label="签收时间" min-width="160">
        <template #default="{ row }">{{ row.signTime || '—' }}</template>
      </el-table-column>
      <el-table-column prop="signPerson" label="签收人" width="100">
        <template #default="{ row }">{{ row.signPerson || '—' }}</template>
      </el-table-column>
      <el-table-column label="拒收原因" min-width="160">
        <template #default="{ row }">
          <span v-if="row.signStatus === 3">{{ rejectReasonText(row) }}</span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.signStatus === 2" link type="success" @click="handleSign(row)">签收</el-button>
          <el-button v-if="row.signStatus === 2" link type="danger" @click="handleReject(row)">拒收</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @size-change="fetchList"
        @current-change="fetchList"
      />
    </div>

    <!-- 签收对话框 -->
    <el-dialog v-model="signVisible" title="签收确认" width="400px">
      <el-form label-width="80px">
        <el-form-item label="签收人">
          <el-input v-model="signPerson" placeholder="学生姓名或班主任，留空默认当前用户" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="signRemark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="signVisible = false">取消</el-button>
        <el-button type="primary" :loading="signing" @click="confirmSign">确认签收</el-button>
      </template>
    </el-dialog>

    <!-- 拒收对话框（结构化原因，提交后后端自动落补送） -->
    <el-dialog v-model="rejectVisible" title="拒收确认" width="440px">
      <el-alert type="warning" :closable="false"
        title="拒收后系统会自动在次日补送一盒（套餐订单合并到次日任务、零散订单另建任务），无需人工登记。" />
      <el-form label-width="80px" style="margin-top: 14px">
        <el-form-item label="拒收原因">
          <el-select v-model="rejectForm.reasonCode" placeholder="选择原因分类" clearable style="width: 100%">
            <el-option v-for="o in rejectReasonOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="详细描述">
          <el-input v-model="rejectForm.reasonDetail" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejecting" @click="confirmReject">确认拒收</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDeliveryRecordList, signDeliveryRecord, rejectDeliveryRecord, batchSignDeliveryRecords, getPendingSign
} from '@/api/delivery'
import { getAllClass } from '@/api/clazz'
import { getStudentList } from '@/api/student'
import GradeClassFilter from '@/views/clazz/components/GradeClassFilter.vue'

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const classList = ref<any[]>([])
const studentList = ref<any[]>([])
const queryDate = ref(today())
const signVisible = ref(false)
const signPerson = ref('')
const signRemark = ref('')
const signing = ref(false)
const currentRecord = ref<any>(null)
const batchSigning = ref(false)

/** 今日待签收汇总（后端 /delivery/record/pending-sign，班主任限本班） */
const pendingSign = reactive({ total: 0, classes: [] as any[] })
const classText = computed(() =>
  (pendingSign.classes as any[]).map((c) => `${c.className || '未知班级'}${c.count}条`).join('、')
)

function today(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

async function fetchPendingSign() {
  const res: any = await getPendingSign(today())
  pendingSign.total = Number(res.data?.total ?? 0)
  pendingSign.classes = res.data?.classes || []
}

const query = reactive({
  pageNum: 1, pageSize: 10,
  classId: undefined as number | undefined,
  studentId: undefined as number | undefined,
  signStatus: undefined as number | undefined
})

const signOptions = [
  { value: 1, label: '已签收' },
  { value: 2, label: '未签收' },
  { value: 3, label: '拒收' }
]
const signText = (s: number) => signOptions.find((x) => x.value === s)?.label ?? '未知'
const signTag = (s: number): any => {
  const map: Record<number, string> = { 1: 'success', 2: 'warning', 3: 'danger' }
  return map[s] ?? 'info'
}
const classLabel = (c: any) => c.gradeName ? `${c.gradeName} · ${c.className}` : c.className

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getDeliveryRecordList({
      ...query,
      deliveryDate: queryDate.value || undefined
    })
    tableData.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

async function fetchClasses() {
  const res: any = await getAllClass()
  classList.value = res.data
}

async function fetchStudents(classId?: number) {
  const res: any = await getStudentList({ pageNum: 1, pageSize: 999, classId })
  studentList.value = res.data.list || []
}

function handleClassChange() {
  query.studentId = undefined
  fetchStudents(query.classId)
}

function handleReset() {
  queryDate.value = ''
  query.classId = undefined
  query.studentId = undefined
  query.signStatus = undefined
  query.pageNum = 1
  fetchStudents()
  fetchList()
}

function handleSign(row: any) {
  currentRecord.value = row
  signPerson.value = ''
  signRemark.value = ''
  signVisible.value = true
}

async function confirmSign() {
  if (!currentRecord.value) return
  signing.value = true
  try {
    await signDeliveryRecord({
      recordId: currentRecord.value.id,
      signPerson: signPerson.value || undefined,
      remark: signRemark.value || undefined
    })
    ElMessage.success('签收成功')
    signVisible.value = false
    fetchList()
  } finally {
    signing.value = false
  }
}

/** 按配送日期（可选班级）批量签收当日全部未签收记录；date 缺省用当前筛选日期（一键签收固定传 today） */
async function handleBatchSign(date?: string) {
  const targetDate = date || queryDate.value
  if (!targetDate) {
    ElMessage.warning('请先选择配送日期')
    return
  }
  const cls = query.classId ? classList.value.find((c) => c.id === query.classId) : null
  const scopeText = cls ? `班级「${classLabel(cls)}」` : '全部班级'
  try {
    await ElMessageBox.confirm(
      `确定将 ${targetDate} ${scopeText} 的全部未签收记录批量签收吗？签收后将逐条生成营养摄入记录。`,
      '批量签收确认',
      { confirmButtonText: '确定签收', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  batchSigning.value = true
  try {
    const res: any = await batchSignDeliveryRecords({ deliveryDate: targetDate, classId: query.classId })
    const count = Number(res?.data ?? 0)
    if (count > 0) {
      ElMessage.success(`已批量签收 ${count} 条记录`)
    } else {
      ElMessage.info('该日期下没有可签收的未签收记录')
    }
    fetchList()
    fetchPendingSign()
  } finally {
    batchSigning.value = false
  }
}

const rejectReasonOptions = [
  { value: 'DAMAGED', label: '包装破损' },
  { value: 'SOUR', label: '变质异味' },
  { value: 'WRONG_PRODUCT', label: '错发品种' },
  { value: 'SHORTAGE', label: '数量短缺' },
  { value: 'OTHER', label: '其他' }
]
const rejectReasonText = (row: any) => {
  const label = rejectReasonOptions.find((x) => x.value === row.rejectReasonCode)?.label
  const detail = row.rejectReasonDetail
  if (label && detail) return `${label}（${detail}）`
  return label || detail || row.remark || '—'
}

const rejectVisible = ref(false)
const rejecting = ref(false)
const rejectRow = ref<any>(null)
const rejectForm = reactive({ reasonCode: '', reasonDetail: '' })

function handleReject(row: any) {
  rejectRow.value = row
  rejectForm.reasonCode = ''
  rejectForm.reasonDetail = ''
  rejectVisible.value = true
}

async function confirmReject() {
  if (!rejectRow.value) return
  rejecting.value = true
  try {
    await rejectDeliveryRecord({
      recordId: rejectRow.value.id,
      reasonCode: rejectForm.reasonCode || undefined,
      reasonDetail: rejectForm.reasonDetail || undefined
    })
    ElMessage.success('已拒收，系统已自动生成补送')
    rejectVisible.value = false
    fetchList()
  } finally {
    rejecting.value = false
  }
}

onMounted(() => {
  fetchClasses()
  fetchStudents()
  fetchList()
  fetchPendingSign()
})
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
  .filter-item { width: 150px; }
}
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
.pending-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 16px;
  background: #fdf3e3;
  border: 1px solid #f0d9b0;
  border-radius: 6px;
  .pending-icon { color: #e6a23c; font-size: 18px; }
  .pending-text { flex: 1; min-width: 200px; color: #7a5a20; font-size: 14px; }
  b { color: #c77700; }
}
</style>
