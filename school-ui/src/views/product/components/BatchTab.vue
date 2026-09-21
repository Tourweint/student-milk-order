<template>
  <div class="batch-tab">
    <el-alert
      type="info" :closable="false" class="batch-tip"
      title="批次是「配额池上的可选标注」，只用于批次事故召回反查（批号 → 配额池 → 扣减台账 → 订单/学生），不参与扣减、结转与配送流程。覆盖边界：学期套餐不占配额，因此反查只覆盖单日零散订购；套餐用奶需按配送日与奶站人工对照。批号未建档也可直接反查。"
    />

    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增批次</el-button>
      <el-button :icon="Refresh" @click="fetchList">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="batchNo" label="批号" min-width="160" />
      <el-table-column label="奶品" min-width="140">
        <template #default="{ row }">{{ productName(row.productId) }}</template>
      </el-table-column>
      <el-table-column prop="productionDate" label="生产日期" width="120" />
      <el-table-column prop="arrivalDate" label="到货日期" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="warning" @click="trace(row.batchNo)">召回反查</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑批次' : '新增批次'" width="460px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="批号" prop="batchNo">
          <el-input v-model="form.batchNo" placeholder="奶站/工厂批号，如 20260915-A1" />
        </el-form-item>
        <el-form-item label="奶品" prop="productId">
          <el-select v-model="form.productId" placeholder="请选择奶品" filterable class="full-width">
            <el-option v-for="p in productList" :key="p.id" :label="p.productName" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="生产日期">
          <el-date-picker v-model="form.productionDate" type="date" value-format="YYYY-MM-DD" class="full-width" />
        </el-form-item>
        <el-form-item label="到货日期">
          <el-date-picker v-model="form.arrivalDate" type="date" value-format="YYYY-MM-DD" class="full-width" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="2">召回中</el-radio>
            <el-radio :value="3">已停用</el-radio>
          </el-radio-group>
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

    <el-dialog v-model="traceVisible" title="批次召回反查" width="1080px" destroy-on-close>
      <div class="trace-query">
        <el-input v-model="traceNo" placeholder="输入批号，如 20260915-A1" style="width: 280px" clearable @keyup.enter="trace()" />
        <el-button type="primary" :loading="tracing" @click="trace()">反查</el-button>
      </div>

      <template v-if="traceResult">
        <el-descriptions :column="3" border size="small" class="trace-summary">
          <el-descriptions-item label="批号">{{ traceResult.batch?.batchNo || traceNo }}</el-descriptions-item>
          <el-descriptions-item label="批次档案">
            {{ traceResult.batch ? productName(traceResult.batch.productId) + '（' + statusText(traceResult.batch.status) + '）' : '未建档（仅池子标注）' }}
          </el-descriptions-item>
          <el-descriptions-item label="召回规模">
            关联池子 {{ traceResult.poolCount ?? 0 }} 个 / 合计 {{ traceResult.totalBoxes ?? 0 }} 盒
          </el-descriptions-item>
        </el-descriptions>

        <div class="section-title">① 批号 → 关联配额池（日期 × 品种）</div>
        <el-table :data="traceResult.pools || []" size="small" stripe>
          <el-table-column prop="quotaDate" label="池子日期" width="120" />
          <el-table-column prop="productName" label="奶品" min-width="130" />
          <el-table-column prop="totalQuota" label="配额" width="90" />
          <el-table-column prop="usedQuota" label="已售" width="90" />
          <el-table-column prop="remark" label="池子备注" min-width="140" />
        </el-table>

        <div class="section-title">② 批号 → 台账 → 订单/学生/配送任务（召回工作清单）</div>
        <el-table :data="traceResult.deliveries || []" size="small" stripe>
          <el-table-column prop="deliveryDate" label="配送日" width="115" />
          <el-table-column prop="quotaDate" label="扣减池子日" width="115" />
          <el-table-column prop="orderNo" label="订单号" min-width="170" />
          <el-table-column prop="studentName" label="学生" width="100" />
          <el-table-column prop="className" label="班级" min-width="120" />
          <el-table-column prop="productName" label="奶品" min-width="120" />
          <el-table-column prop="boxes" label="盒数" width="80" />
          <el-table-column prop="taskNo" label="配送任务号" min-width="170" />
          <el-table-column label="任务状态" width="100">
            <template #default="{ row }">{{ taskStatusText(row.taskStatus) }}</template>
          </el-table-column>
          <el-table-column label="签收状态" width="100">
            <template #default="{ row }">{{ signStatusText(row.signStatus) }}</template>
          </el-table-column>
        </el-table>
        <div class="trace-footnote">
          已签收/已完成说明奶已送达；待配送/配送中可直接拦截。任务号为空表示该配送日的任务已被平移、重排或退订作废。
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { getBatchList, saveBatch, updateBatch, deleteBatch, traceBatch, getProductList } from '@/api/product'

const loading = ref(false)
const submitting = ref(false)
const list = ref<any[]>([])
const productList = ref<any[]>([])

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<any>({
  id: null, batchNo: '', productId: undefined,
  productionDate: null, arrivalDate: null, status: 1, remark: ''
})
const rules: FormRules = {
  batchNo: [{ required: true, message: '请输入批号', trigger: 'blur' }],
  productId: [{ required: true, message: '请选择奶品', trigger: 'change' }]
}

// 召回反查
const traceVisible = ref(false)
const tracing = ref(false)
const traceNo = ref('')
const traceResult = ref<any>(null)

function productName(productId?: number) {
  return productList.value.find((p) => p.id === productId)?.productName || (productId ? `奶品${productId}` : '—')
}
function statusText(status?: number) {
  return status === 2 ? '召回中' : status === 3 ? '已停用' : '正常'
}
function statusTag(status?: number) {
  return status === 2 ? 'danger' : status === 3 ? 'info' : 'success'
}
function taskStatusText(status?: number) {
  if (status == null) return '—'
  return { 1: '待配送', 2: '配送中', 3: '已完成', 4: '已取消' }[status] || String(status)
}
function signStatusText(status?: number) {
  if (status == null) return '—'
  return { 1: '已签收', 2: '未签收', 3: '已拒收' }[status] || String(status)
}

async function fetchList() {
  loading.value = true
  try {
    const [batchRes, prodRes]: any[] = await Promise.all([
      getBatchList(),
      getProductList({ pageNum: 1, pageSize: 200 })
    ])
    list.value = batchRes.data || []
    productList.value = prodRes.data.list || []
  } finally {
    loading.value = false
  }
}

function openDialog(row?: any) {
  Object.assign(form, {
    id: row?.id ?? null,
    batchNo: row?.batchNo ?? '',
    productId: row?.productId ?? undefined,
    productionDate: row?.productionDate ?? null,
    arrivalDate: row?.arrivalDate ?? null,
    status: row?.status ?? 1,
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
        await updateBatch(form)
        ElMessage.success('修改成功')
      } else {
        await saveBatch(form)
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
  await ElMessageBox.confirm(
    `确定删除批次「${row.batchNo}」吗？已标注该批号的配额池会保留批号，召回反查不受影响。`,
    '提示',
    { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
  )
  await deleteBatch(row.id)
  ElMessage.success('删除成功')
  fetchList()
}

async function trace(batchNo?: string) {
  if (batchNo) traceNo.value = batchNo
  if (!traceNo.value) {
    ElMessage.warning('请输入批号')
    return
  }
  tracing.value = true
  traceVisible.value = true
  try {
    const res: any = await traceBatch(traceNo.value.trim())
    traceResult.value = res.data
    if (!res.data?.poolCount) {
      ElMessage.warning('该批号未关联任何配额池（零散订购未标注过它）')
    }
  } finally {
    tracing.value = false
  }
}

onMounted(fetchList)
</script>

<style scoped lang="scss">
.batch-tip { margin-bottom: 12px; }
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}
.full-width { width: 100%; }
.trace-query {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}
.trace-summary { margin-bottom: 4px; }
.section-title {
  margin: 18px 0 8px;
  font-weight: 600;
  color: #1f2d3d;
}
.trace-footnote {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
