<template>
  <div class="record-tab">
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDeliveryRecordList, signDeliveryRecord, rejectDeliveryRecord, batchSignDeliveryRecords
} from '@/api/delivery'
import { getAllClass } from '@/api/clazz'
import { getStudentList } from '@/api/student'
import GradeClassFilter from '@/views/clazz/components/GradeClassFilter.vue'

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const classList = ref<any[]>([])
const studentList = ref<any[]>([])
const queryDate = ref('')
const signVisible = ref(false)
const signPerson = ref('')
const signRemark = ref('')
const signing = ref(false)
const currentRecord = ref<any>(null)
const batchSigning = ref(false)

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

/** 按配送日期（可选班级）批量签收当日全部未签收记录 */
async function handleBatchSign() {
  if (!queryDate.value) {
    ElMessage.warning('请先选择配送日期')
    return
  }
  const cls = query.classId ? classList.value.find((c) => c.id === query.classId) : null
  const scopeText = cls ? `班级「${classLabel(cls)}」` : '全部班级'
  try {
    await ElMessageBox.confirm(
      `确定将 ${queryDate.value} ${scopeText} 的全部未签收记录批量签收吗？签收后将逐条生成营养摄入记录。`,
      '批量签收确认',
      { confirmButtonText: '确定签收', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  batchSigning.value = true
  try {
    const res: any = await batchSignDeliveryRecords({ deliveryDate: queryDate.value, classId: query.classId })
    const count = Number(res?.data ?? 0)
    if (count > 0) {
      ElMessage.success(`已批量签收 ${count} 条记录`)
    } else {
      ElMessage.info('该日期下没有可签收的未签收记录')
    }
    fetchList()
  } finally {
    batchSigning.value = false
  }
}

async function handleReject(row: any) {
  const { value: reason } = await ElMessageBox.prompt('请输入拒收原因（可选）', '拒收确认', {
    type: 'warning', inputPlaceholder: '可留空'
  }).catch(() => ({ value: undefined as any }))
  if (reason === undefined) return
  await rejectDeliveryRecord(row.id, reason || undefined)
  ElMessage.success('已拒收')
  fetchList()
}

onMounted(() => {
  fetchClasses()
  fetchStudents()
  fetchList()
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
</style>
