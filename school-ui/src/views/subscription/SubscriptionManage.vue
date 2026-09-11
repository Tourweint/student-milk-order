<template>
  <div class="subscription-manage">
    <!-- 筛选 -->
    <div class="toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable class="filter-item" @change="handleSearch">
        <el-option label="已开启" :value="1" />
        <el-option label="已关闭" :value="0" />
      </el-select>
      <el-button type="primary" @click="fetchList">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">开启自动续订</el-button>
    </div>

    <!-- 表格 -->
    <el-table v-loading="loading" :data="tableData" stripe>
      <el-table-column prop="studentName" label="学生" width="100" />
      <el-table-column prop="packageName" label="套餐" min-width="130" />
      <el-table-column prop="originalOrderNo" label="原订单号" min-width="180" />
      <el-table-column label="周期" width="100">
        <template #default="{ row }">{{ row.cycleTypeText }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="nextRenewalTime" label="下次续订" min-width="160" />
      <el-table-column prop="lastRenewalTime" label="上次续订" min-width="160">
        <template #default="{ row }">{{ row.lastRenewalTime || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" link type="warning" @click="handleTrigger(row)">手动续订</el-button>
          <el-button v-if="row.status === 1" link type="danger" @click="handleClose(row)">关闭</el-button>
          <el-button v-else link type="primary" @click="handleReopen(row)">重新开启</el-button>
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

    <!-- 开启续订对话框 -->
    <el-dialog v-model="createVisible" title="开启自动续订" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="学生" prop="studentId">
          <el-select v-model="form.studentId" placeholder="选择学生" filterable class="full-width" @change="onStudentChange">
            <el-option v-for="s in studentList" :key="s.id" :label="`${s.studentName}（${s.studentNo}）`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="原订单" prop="originalOrderId">
          <el-select v-model="form.originalOrderId" placeholder="选择已支付订单" filterable class="full-width" :disabled="!form.studentId">
            <el-option v-for="o in paidOrders" :key="o.id"
              :label="`${o.orderNo}（¥${o.payAmount}，${o.deliveryStartDate}~${o.deliveryEndDate}）`" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="套餐" prop="packageId">
          <el-select v-model="form.packageId" placeholder="选择套餐" class="full-width">
            <el-option v-for="p in packageList" :key="p.id" :label="`${p.packageName}（¥${p.discountPrice}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="续订周期">
          <el-radio-group v-model="form.cycleType">
            <el-radio :value="1">每月续订</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">开启</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  getSubscriptionList, createSubscription, deleteSubscription, triggerSubscription
} from '@/api/subscription'
import { getStudentList } from '@/api/student'
import { getOrderList } from '@/api/order'
import { getPackageList } from '@/api/product'

const loading = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const studentList = ref<any[]>([])
const paidOrders = ref<any[]>([])
const packageList = ref<any[]>([])
const createVisible = ref(false)
const creating = ref(false)
const formRef = ref<FormInstance>()

const query = reactive({
  pageNum: 1, pageSize: 10,
  status: undefined as number | undefined
})

const form = reactive({
  studentId: undefined as number | undefined,
  originalOrderId: undefined as number | undefined,
  packageId: undefined as number | undefined,
  cycleType: 1,
  remark: ''
})

const rules: FormRules = {
  studentId: [{ required: true, message: '请选择学生', trigger: 'change' }],
  originalOrderId: [{ required: true, message: '请选择原订单', trigger: 'change' }],
  packageId: [{ required: true, message: '请选择套餐', trigger: 'change' }]
}

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getSubscriptionList(query)
    tableData.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

async function fetchStudents() {
  const res: any = await getStudentList({ pageNum: 1, pageSize: 999 })
  studentList.value = res.data.list || []
}

async function fetchPackages() {
  const res: any = await getPackageList()
  packageList.value = res.data || []
}

async function onStudentChange() {
  form.originalOrderId = undefined
  if (!form.studentId) return
  const res: any = await getOrderList({ pageNum: 1, pageSize: 999, studentId: form.studentId, status: 2 })
  paidOrders.value = res.data.list || []
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.status = undefined
  query.pageNum = 1
  fetchList()
}

function openCreate() {
  form.studentId = undefined
  form.originalOrderId = undefined
  form.packageId = undefined
  form.cycleType = 1
  form.remark = ''
  paidOrders.value = []
  createVisible.value = true
}

async function handleCreate() {
  if (!formRef.value) return
  await formRef.value.validate()
  creating.value = true
  try {
    await createSubscription({ ...form })
    ElMessage.success('已开启自动续订')
    createVisible.value = false
    fetchList()
  } finally {
    creating.value = false
  }
}

async function handleTrigger(row: any) {
  await ElMessageBox.confirm(`确定立即为「${row.studentName}」执行续订吗？将生成新订单并自动支付。`, '手动续订', {
    type: 'warning', confirmButtonText: '确定续订', cancelButtonText: '取消'
  })
  const res: any = await triggerSubscription(row.id)
  ElMessage.success(`续订成功，新订单ID：${res.data}`)
  fetchList()
}

async function handleClose(row: any) {
  await ElMessageBox.confirm(`确定关闭「${row.studentName}」的自动续订吗？`, '提示', { type: 'warning' })
  await deleteSubscription(row.id)
  ElMessage.success('已关闭')
  fetchList()
}

async function handleReopen(row: any) {
  // 重新开启：直接修改状态为1
  ElMessage.info('请通过"开启自动续订"创建新计划')
}

onMounted(() => {
  fetchStudents()
  fetchPackages()
  fetchList()
})
</script>

<style scoped lang="scss">
.subscription-manage { padding: 20px; }
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  align-items: center;
  .filter-item { width: 160px; }
  .spacer { flex: 1; }
}
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
.full-width { width: 100%; }
</style>
