<template>
  <el-drawer :model-value="visible" title="退款预览（只读）" size="620px" @update:model-value="emit('update:visible', $event)">
    <div v-loading="loading" class="preview">
      <template v-if="preview">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="预览与实际执行共用同一套口径；差异只可能来自预览之后期次被并发配送。"
          class="hint"
        />
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="订单号">{{ preview.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="订单状态">{{ preview.orderStatusText }}</el-descriptions-item>
          <el-descriptions-item label="合同总盒数">{{ preview.contractTotalBoxes ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="当前可退盒数">{{ preview.refundableBoxes }}</el-descriptions-item>
          <el-descriptions-item label="已退盒数（累计）">{{ preview.refundedBoxes }}</el-descriptions-item>
          <el-descriptions-item label="已退金额">¥{{ Number(preview.refundedAmount || 0).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="预估本次退款">
            <span class="text-price">¥{{ Number(preview.estimatedAmount || 0).toFixed(2) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="进行中退款单">
            <el-tag :type="preview.hasActiveRefund ? 'warning' : 'info'" size="small">
              {{ preview.hasActiveRefund ? '存在（不可重复申请）' : '无' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="sub-title">可退期次（{{ preview.refundableTasks.length }}）</div>
        <el-table :data="preview.refundableTasks" size="small" stripe>
          <el-table-column prop="deliveryDate" label="配送日" width="110" />
          <el-table-column prop="productName" label="奶品" min-width="110">
            <template #default="{ row }">{{ row.productName || '奶品' + row.productId }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="任务盒数" width="90" />
          <el-table-column prop="refundableBoxes" label="可退盒数" width="90" />
          <el-table-column label="期次类型" width="130">
            <template #default="{ row }">
              <el-tag :type="row.pending ? 'primary' : 'warning'" size="small">{{ row.statusText }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!preview.refundableTasks.length" class="empty">当前无可退期次</div>
      </template>
      <div v-else-if="!loading" class="empty">暂无预览数据</div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { getRefundPreview, type RefundPreviewVO } from '@/api/refund'

const props = defineProps<{ visible: boolean; orderId: number | null }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>()

const loading = ref(false)
const preview = ref<RefundPreviewVO | null>(null)

async function load() {
  if (!props.orderId) {
    preview.value = null
    return
  }
  loading.value = true
  try {
    const res: any = await getRefundPreview(props.orderId)
    preview.value = res.data
  } catch {
    preview.value = null
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.visible, props.orderId],
  ([visible]) => {
    if (visible) load()
  }
)
</script>

<style scoped lang="scss">
.preview {
  min-height: 200px;
}
.hint {
  margin-bottom: 12px;
}
.sub-title {
  margin: 16px 0 8px;
  font-weight: 600;
  color: #1f2d3d;
}
.text-price {
  color: #f56c6c;
  font-weight: 600;
}
.empty {
  text-align: center;
  color: #909399;
  padding: 24px 0;
}
</style>
