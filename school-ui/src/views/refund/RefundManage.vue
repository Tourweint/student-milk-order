<template>
  <div class="refund-manage">
    <!-- 筛选栏 -->
    <div class="search-bar">
      <el-select v-model="query.status" placeholder="全部状态" clearable class="filter-select" @change="handleSearch">
        <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-input
        v-model="query.orderNo"
        placeholder="订单号（模糊）"
        clearable
        class="search-input"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="退款按「可退期次」计算：待配送期次与缺货取消期次可退，已配送/已完成与真拒收（已补送）不退；金额以执行时刻为准。"
      class="hint"
    />

    <!-- 退款单表格 -->
    <el-table v-loading="loading" :data="tableData" stripe>
      <el-table-column prop="refundNo" label="退款单号" min-width="200" />
      <el-table-column prop="orderNo" label="订单号" min-width="180" />
      <el-table-column prop="studentName" label="学生" width="100">
        <template #default="{ row }">{{ row.studentName || '—' }}</template>
      </el-table-column>
      <el-table-column label="申请/已退盒数" width="130">
        <template #default="{ row }">{{ row.applyBoxCount ?? '—' }} / {{ row.refundedBoxes ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="退款金额" width="110">
        <template #default="{ row }">
          <span v-if="row.refundAmount != null" class="text-price">¥{{ Number(row.refundAmount).toFixed(2) }}</span>
          <span v-else class="text-muted">待执行</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="applyReason" label="申请原因" min-width="150" show-overflow-tooltip />
      <el-table-column prop="auditRemark" label="审核意见" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ row.auditRemark || '—' }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="申请时间" min-width="160" />
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openPreview(row)">预览</el-button>
          <el-button v-if="row.status === 1" link type="success" @click="handleAudit(row, true)">通过</el-button>
          <el-button v-if="row.status === 1" link type="danger" @click="handleAudit(row, false)">拒绝</el-button>
          <el-button v-if="row.status === 2" link type="warning" @click="handleExecute(row)">执行退款</el-button>
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

    <!-- 退款预览抽屉 -->
    <RefundPreviewDrawer v-model:visible="previewVisible" :order-id="previewOrderId" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getRefundList, auditRefund, executeRefund, getRefundPreview, type RefundOrderVO
} from '@/api/refund'
import RefundPreviewDrawer from './components/RefundPreviewDrawer.vue'

const route = useRoute()

const loading = ref(false)
const tableData = ref<RefundOrderVO[]>([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: undefined as number | undefined,
  orderNo: undefined as string | undefined
})

const statusOptions = [
  { value: 1, label: '待审核' },
  { value: 2, label: '已审核待退款' },
  { value: 3, label: '已退款' },
  { value: 4, label: '已拒绝' }
]
const statusTag = (s: number): any => {
  const map: Record<number, string> = { 1: 'warning', 2: 'primary', 3: 'success', 4: 'danger', 5: 'info' }
  return map[s] ?? 'info'
}

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getRefundList(query)
    tableData.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.status = undefined
  query.orderNo = undefined
  query.pageNum = 1
  fetchList()
}

/** 审核：通过 → 已审核待退款；拒绝 → 已拒绝（家长/管理员可重新申请新单） */
async function handleAudit(row: RefundOrderVO, approved: boolean) {
  let remark = ''
  if (approved) {
    await ElMessageBox.confirm(
      `确认通过退款单「${row.refundNo}」的审核？通过后还需执行退款才会真正作废期次并落账。`,
      '审核通过确认',
      { confirmButtonText: '确定通过', cancelButtonText: '取消', type: 'warning' }
    )
  } else {
    const res = await ElMessageBox.prompt('请输入拒绝原因（可选）', '拒绝退款', {
      confirmButtonText: '确定拒绝',
      cancelButtonText: '取消',
      type: 'warning',
      inputPlaceholder: '可留空'
    }).catch(() => null)
    if (!res) return
    remark = res.value || ''
  }
  await auditRefund(row.id, { approved, remark: remark || undefined })
  ElMessage.success(approved ? '已通过审核' : '已拒绝')
  fetchList()
}

/** 执行退款：先取预览（实际可退盒数/金额以执行时刻为准）再二次确认 */
async function handleExecute(row: RefundOrderVO) {
  let boxes = 0
  let amount = 0
  try {
    const res: any = await getRefundPreview(row.orderId)
    boxes = res.data.refundableBoxes
    amount = Number(res.data.estimatedAmount || 0)
  } catch {
    // 预览失败不阻塞（执行时以后端为准），仅少一层提示
  }
  await ElMessageBox.confirm(
    `执行后将作废订单「${row.orderNo}」当前 ${boxes} 个可退盒数的期次并退款 ¥${amount.toFixed(2)}（模拟通道即时到账），`
      + '作废不可逆（对应期次不再配送）；若部分期次已被并发配送，金额按实际作废盒数结算。确认执行吗？',
    '执行退款确认',
    { confirmButtonText: '确定执行', cancelButtonText: '取消', type: 'warning' }
  )
  await executeRefund(row.id)
  ElMessage.success('退款已执行')
  fetchList()
}

// 预览
const previewVisible = ref(false)
const previewOrderId = ref<number | null>(null)
function openPreview(row: RefundOrderVO) {
  previewOrderId.value = row.orderId
  previewVisible.value = true
}

onMounted(() => {
  // 支持从订单管理页按订单号跳转过来（/refund?orderNo=MO...）
  const orderNo = route.query.orderNo
  if (typeof orderNo === 'string' && orderNo) {
    query.orderNo = orderNo
  }
  fetchList()
})
</script>

<style scoped lang="scss">
.refund-manage {
  padding: 20px;
}
.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  .filter-select { width: 180px; }
  .search-input { width: 240px; }
}
.hint {
  margin-bottom: 16px;
}
.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.text-price {
  color: #f56c6c;
  font-weight: 600;
}
.text-muted {
  color: #909399;
}
</style>
