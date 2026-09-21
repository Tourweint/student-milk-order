<template>
  <div class="warehouse-page">
    <el-alert
      type="info"
      :closable="false"
      class="page-tip"
      title="仓库余量是机动配额发行的上限（只有仓库余量才能卖）"
      description="余量 = Σ到货 + Σ退回 + Σ期初 + Σ修正(带符号) − Σ送出，按品种独立记账。到货由配送站面板登记；送出与退回在「开始配送」「拒收」时自动入账；台账只增不改，记错走反向修正冲销。"
    />

    <!-- 当前余量 -->
    <div class="section-title">
      <span>当前余量（按品种）</span>
      <el-button link type="primary" @click="fetchBalance">刷新</el-button>
    </div>
    <el-table v-loading="balanceLoading" :data="balanceList" stripe class="balance-table">
      <el-table-column prop="productId" label="奶品ID" width="90" />
      <el-table-column label="奶品" min-width="160">
        <template #default="{ row }">{{ row.productName || '—' }}</template>
      </el-table-column>
      <el-table-column label="仓库余量（盒）" width="150" align="center">
        <template #default="{ row }">
          <el-tag :type="row.balance > 0 ? 'success' : 'danger'" size="small">{{ row.balance }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提示" min-width="220">
        <template #default="{ row }">
          <span v-if="row.balance <= 0" class="warn-text">
            余量为 0：该品种当天无法设置机动配额，请先在配送站面板登记到货
          </span>
          <span v-else class="muted-text">可支撑当日机动配额发行</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 台账查询 -->
    <div class="section-title">
      <span>进出账台账</span>
      <el-button type="primary" @click="openAdjust">修正（ADJ）</el-button>
    </div>
    <div class="toolbar">
      <el-select v-model="query.bizType" placeholder="全部账目类型" clearable class="type-item">
        <el-option v-for="t in bizTypeOptions" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-select v-model="query.productId" placeholder="全部奶品" clearable class="type-item" filterable>
        <el-option
          v-for="p in balanceList"
          :key="p.productId"
          :label="p.productName || '奶品' + p.productId"
          :value="p.productId"
        />
      </el-select>
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="date-item"
      />
      <el-button type="primary" @click="fetchLedger">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
    </div>

    <el-table v-loading="ledgerLoading" :data="ledgerList" stripe>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="bizTypeTag(row.bizType)" size="small">{{ bizTypeText(row.bizType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="bizDate" label="业务日期" width="115" />
      <el-table-column label="奶品" min-width="130">
        <template #default="{ row }">{{ productName(row.productId) }}</template>
      </el-table-column>
      <el-table-column label="盒数" width="90" align="right">
        <template #default="{ row }">
          <span :class="row.quantity < 0 ? 'qty-minus' : 'qty-plus'">
            {{ row.quantity > 0 && row.bizType !== 'OUT' && row.bizType !== 'ADJ' ? '+' : '' }}{{ row.quantity }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="关联对象" width="160">
        <template #default="{ row }">{{ refText(row) }}</template>
      </el-table-column>
      <el-table-column label="凭证 / 批次" width="170">
        <template #default="{ row }">
          {{ row.receiptNo || '—' }}<span v-if="row.batchNo"> / {{ row.batchNo }}</span>
        </template>
      </el-table-column>
      <el-table-column label="原因 / 备注" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reason || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="110">
        <template #default="{ row }">{{ row.operator || '—' }}</template>
      </el-table-column>
      <el-table-column prop="createTime" label="记录时间" min-width="160" />
    </el-table>
    <div class="pagination">
      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @size-change="fetchLedger"
        @current-change="fetchLedger"
      />
    </div>

    <!-- 修正（ADJ）：台账只增不改，纠错靠反向冲销 -->
    <el-dialog v-model="adjustVisible" title="台账修正（ADJ）" width="460px">
      <el-alert
        type="warning"
        :closable="false"
        class="dialog-tip"
        title="修正会改变仓库余量，进而改变机动配额的发行上限"
        description="正数=增加余量，负数=减少余量（如到货记错、盘点差异、破损冲销）。台账不提供修改/删除：记错请再记一条反向修正，两条留痕。"
      />
      <el-form :model="adjustForm" label-width="90px">
        <el-form-item label="奶品" required>
          <el-select v-model="adjustForm.productId" placeholder="选择奶品" filterable class="full-width">
            <el-option
              v-for="p in balanceList"
              :key="p.productId"
              :label="`${p.productName || '奶品' + p.productId}（余量 ${p.balance}）`"
              :value="p.productId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="修正盒数" required>
          <el-input-number v-model="adjustForm.quantity" :step="1" controls-position="right" class="full-width" />
          <div class="hint">正数增加余量、负数减少余量；减少后的余量不允许为负</div>
        </el-form-item>
        <el-form-item label="修正原因" required>
          <el-input v-model="adjustForm.reason" type="textarea" :rows="2" placeholder="如：到货登记 500 误填 5000，冲销 4500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustSubmitting" @click="submitAdjust">确认修正</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getWarehouseBalance, getWarehouseLedger, adjustWarehouse } from '@/api/warehouse'

interface BalanceRow {
  productId: number
  productName: string | null
  balance: number
}

const balanceLoading = ref(false)
const balanceList = ref<BalanceRow[]>([])

const productNameMap = computed<Record<number, string>>(() => {
  const map: Record<number, string> = {}
  balanceList.value.forEach((p) => {
    map[p.productId] = p.productName || `奶品${p.productId}`
  })
  return map
})

const bizTypeOptions = [
  { value: 'IN', label: '到货' },
  { value: 'OUT', label: '送出' },
  { value: 'IN_BACK', label: '退回' },
  { value: 'ADJ', label: '修正' },
  { value: 'INIT', label: '期初' }
]
const bizTypeText = (t: string) => bizTypeOptions.find((x) => x.value === t)?.label ?? t
const bizTypeTag = (t: string): any =>
  ({ IN: 'success', OUT: 'primary', IN_BACK: 'warning', ADJ: 'info', INIT: 'success' }[t] ?? 'info')

const ledgerLoading = ref(false)
const ledgerList = ref<any[]>([])
const total = ref(0)
const dateRange = ref<[string, string] | null>(null)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  bizType: undefined as string | undefined,
  productId: undefined as number | undefined
})

const adjustVisible = ref(false)
const adjustSubmitting = ref(false)
const adjustForm = reactive({
  productId: undefined as number | undefined,
  quantity: 0,
  reason: ''
})

function productName(productId: number) {
  return productNameMap.value[productId] || `奶品${productId}`
}

function refText(row: any) {
  if (!row.refType || !row.refId) {
    return '—'
  }
  const type = row.refType === 'delivery_task' ? '任务' : row.refType === 'delivery_record' ? '签收记录' : row.refType
  return `${type} #${row.refId}`
}

async function fetchBalance() {
  balanceLoading.value = true
  try {
    const res: any = await getWarehouseBalance()
    balanceList.value = res.data || []
  } finally {
    balanceLoading.value = false
  }
}

async function fetchLedger() {
  ledgerLoading.value = true
  try {
    const res: any = await getWarehouseLedger({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      bizType: query.bizType,
      productId: query.productId,
      startDate: dateRange.value?.[0],
      endDate: dateRange.value?.[1]
    })
    ledgerList.value = res.data.list || []
    total.value = Number(res.data.total)
  } finally {
    ledgerLoading.value = false
  }
}

function resetQuery() {
  query.bizType = undefined
  query.productId = undefined
  dateRange.value = null
  query.pageNum = 1
  fetchLedger()
}

function openAdjust() {
  adjustForm.productId = undefined
  adjustForm.quantity = 0
  adjustForm.reason = ''
  adjustVisible.value = true
}

async function submitAdjust() {
  if (!adjustForm.productId) {
    ElMessage.warning('请选择奶品')
    return
  }
  if (!adjustForm.quantity) {
    ElMessage.warning('修正盒数不能为 0')
    return
  }
  if (!adjustForm.reason.trim()) {
    ElMessage.warning('请填写修正原因')
    return
  }
  adjustSubmitting.value = true
  try {
    await adjustWarehouse({
      productId: adjustForm.productId,
      quantity: adjustForm.quantity,
      reason: adjustForm.reason.trim()
    })
    ElMessage.success('已记入修正（台账留痕）')
    adjustVisible.value = false
    fetchBalance()
    fetchLedger()
  } finally {
    adjustSubmitting.value = false
  }
}

onMounted(() => {
  fetchBalance()
  fetchLedger()
})
</script>

<style scoped lang="scss">
.warehouse-page {
  padding: 20px;
}
.page-tip {
  margin-bottom: 16px;
}
.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.balance-table {
  margin-bottom: 8px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 12px;
  .type-item { width: 160px; }
  .date-item { width: 260px; }
}
.warn-text { color: var(--el-color-danger); font-size: 13px; }
.muted-text { color: var(--el-text-color-secondary); font-size: 13px; }
.qty-plus { color: var(--el-color-success); }
.qty-minus { color: var(--el-color-danger); }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
.dialog-tip { margin-bottom: 12px; }
.full-width { width: 100%; }
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
