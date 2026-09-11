<template>
  <el-drawer v-model="visible" title="订单详情" size="520px" @closed="reset">
    <div v-loading="loading" class="order-detail">
      <template v-if="order">
        <!-- 状态与金额 -->
        <div class="status-header">
          <el-tag :type="statusTag(order.status)" size="large">{{ statusText(order.status) }}</el-tag>
          <div class="amount">
            <span class="label">实付</span>
            <span class="value">¥{{ Number(order.payAmount).toFixed(2) }}</span>
          </div>
        </div>

        <!-- 基本信息 -->
        <el-descriptions :column="1" border size="small" class="info-block">
          <el-descriptions-item label="订单号">{{ order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="学生">{{ order.studentName }}</el-descriptions-item>
          <el-descriptions-item label="班级">{{ order.className }}</el-descriptions-item>
          <el-descriptions-item label="套餐">{{ order.packageName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="订单类型">{{ order.orderType === 1 ? '按月订购' : '按学期订购' }}</el-descriptions-item>
          <el-descriptions-item label="配送周期">
            {{ order.deliveryStartDate }} 至 {{ order.deliveryEndDate }}
          </el-descriptions-item>
          <el-descriptions-item label="订单金额">
            原价 ¥{{ Number(order.totalAmount).toFixed(2) }}
            <span v-if="order.discountAmount > 0"> / 优惠 ¥{{ Number(order.discountAmount).toFixed(2) }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="order.payTime" label="支付时间">{{ order.payTime }}</el-descriptions-item>
          <el-descriptions-item v-if="order.cancelTime" label="退订时间">{{ order.cancelTime }}</el-descriptions-item>
          <el-descriptions-item v-if="order.cancelReason" label="退订原因">{{ order.cancelReason }}</el-descriptions-item>
          <el-descriptions-item v-if="order.remark" label="备注">{{ order.remark }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ order.createTime }}</el-descriptions-item>
        </el-descriptions>

        <!-- 明细 -->
        <div class="section-title">订单明细</div>
        <el-table :data="order.items" size="small" border>
          <el-table-column prop="productName" label="奶品" min-width="120" />
          <el-table-column prop="spec" label="规格" width="90" />
          <el-table-column label="单价" width="80">
            <template #default="{ row }">¥{{ Number(row.price).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="70" />
          <el-table-column label="小计" width="90">
            <template #default="{ row }">¥{{ Number(row.subtotal).toFixed(2) }}</template>
          </el-table-column>
        </el-table>

        <!-- 操作 -->
        <div class="action-bar">
          <el-button v-if="order.status === 1" type="success" @click="handlePay">模拟支付</el-button>
          <el-button v-if="order.status === 2" type="warning" @click="handleDeliver">开始配送</el-button>
          <el-button v-if="order.status === 3" type="primary" @click="handleComplete">完成订单</el-button>
          <el-button v-if="order.status === 1 || order.status === 2" type="danger" @click="handleCancel">退订</el-button>
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getOrderById, payOrder, cancelOrder, deliverOrder, completeOrder
} from '@/api/order'

const props = defineProps<{ visible: boolean; orderId: number | null }>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'status-changed'): void
}>()

const visible = ref(props.visible)
watch(() => props.visible, (v) => { visible.value = v })
watch(visible, (v) => { emit('update:visible', v) })

const loading = ref(false)
const order = ref<any>(null)

const statusOptions = [
  { value: 1, label: '待支付' },
  { value: 2, label: '已支付' },
  { value: 3, label: '配送中' },
  { value: 4, label: '已完成' },
  { value: 5, label: '已退订' }
]
const statusText = (s: number) => statusOptions.find((x) => x.value === s)?.label ?? '未知'
const statusTag = (s: number): any => {
  const map: Record<number, string> = { 1: 'warning', 2: 'primary', 3: 'info', 4: 'success', 5: 'danger' }
  return map[s] ?? 'info'
}

watch(() => props.orderId, async (id) => {
  if (id && props.visible) {
    await loadDetail(id)
  }
})

async function loadDetail(id: number) {
  loading.value = true
  try {
    const res: any = await getOrderById(id)
    order.value = res.data
  } finally {
    loading.value = false
  }
}

async function handlePay() {
  await ElMessageBox.confirm('确定支付该订单吗？', '提示', { type: 'warning' })
  await payOrder(order.value.id)
  ElMessage.success('支付成功')
  await loadDetail(order.value.id)
  emit('status-changed')
}

async function handleDeliver() {
  await deliverOrder(order.value.id)
  ElMessage.success('已开始配送')
  await loadDetail(order.value.id)
  emit('status-changed')
}

async function handleComplete() {
  await completeOrder(order.value.id)
  ElMessage.success('订单已完成')
  await loadDetail(order.value.id)
  emit('status-changed')
}

async function handleCancel() {
  const { value: reason } = await ElMessageBox.prompt('请输入退订原因（可选）', '退订确认', {
    type: 'warning', inputPlaceholder: '可留空'
  }).catch(() => ({ value: undefined as any }))
  if (reason === undefined) return
  await cancelOrder(order.value.id, reason || undefined)
  ElMessage.success('已退订')
  await loadDetail(order.value.id)
  emit('status-changed')
}

function reset() {
  order.value = null
}
</script>

<style scoped lang="scss">
.order-detail { padding: 0 4px; }
.status-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  .amount {
    display: flex;
    align-items: baseline;
    gap: 6px;
    .label { color: #909399; font-size: 13px; }
    .value { color: #f56c6c; font-size: 22px; font-weight: 600; }
  }
}
.info-block { margin-bottom: 16px; }
.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 16px 0 8px;
  color: #303133;
}
.action-bar {
  margin-top: 20px;
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}
</style>
