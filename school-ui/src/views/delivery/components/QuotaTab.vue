<template>
  <div class="quota-tab">
    <div class="toolbar">
      <el-date-picker
        v-model="editDate" type="date" placeholder="选择配额日期" value-format="YYYY-MM-DD"
        class="filter-item" @change="fetchEditData"
      />
      <el-button type="primary" :loading="saving" @click="handleSave">保存本日配额</el-button>
      <el-button @click="fetchEditData">刷新</el-button>
    </div>

    <el-alert
      type="info" :closable="false" class="quota-tip"
      title="每日机动配额按品种设置，仅用于单日零散订购（临时补订、换口味、插班临时订购）；发行上限受仓库余量封顶，余量不足时请先在「仓库余量」页签登记到货。当日未售完自动结转次日继续可售，按 3 天计划顺延窗口滚动、窗口外自动作废（该参数是商务约定值，不是牛奶保质期）。学期套餐不占用配额。留空的品种当日不可零散订购。到货批次为可选标注，仅用于批次召回反查，不影响扣减与结转。"
    />

    <el-table v-loading="loading" :data="productRows" stripe>
      <el-table-column prop="productName" label="奶品" min-width="140" />
      <el-table-column label="仓库余量（盒）" width="140">
        <template #default="{ row }">
          <span :class="{ 'quota-low': (balanceMap[row.productId] ?? 0) <= 0 }">
            {{ balanceMap[row.productId] ?? 0 }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="当日配额（盒）" width="170">
        <template #default="{ row }">
          <el-input-number v-model="row.inputQuota" :min="0" :max="9999" size="small" placeholder="未设置" />
        </template>
      </el-table-column>
      <el-table-column label="到货批次（可选）" width="190">
        <template #default="{ row }">
          <el-select
            v-model="row.batchNo" size="small" clearable filterable allow-create default-first-option
            placeholder="未标注" class="batch-select"
          >
            <el-option v-for="b in batchOptionsOf(row.productId)" :key="b.batchNo" :label="b.batchNo" :value="b.batchNo" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="已售（盒）" width="110">
        <template #default="{ row }">{{ row.used ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="剩余（含结转，盒）" width="160">
        <template #default="{ row }">
          <span :class="{ 'quota-low': row.remaining <= 0 }">{{ row.remaining }}</span>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="120">
        <template #default="{ row }">{{ row.remark || '—' }}</template>
      </el-table-column>
    </el-table>

    <div class="section-title">已设置配额（近 7 天）</div>
    <el-table :data="recentRows" size="small" stripe>
      <el-table-column prop="quotaDate" label="日期" width="130" />
      <el-table-column prop="productName" label="奶品" min-width="130" />
      <el-table-column prop="totalQuota" label="配额" width="90" />
      <el-table-column prop="usedQuota" label="已售" width="90" />
      <el-table-column prop="remaining" label="当日剩余" width="100" />
      <el-table-column prop="batchNo" label="到货批次" width="150">
        <template #default="{ row }">{{ row.batchNo || '—' }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getQuotaList, getQuotaRemainingList, setQuotaBatch, getProductList, getBatchList } from '@/api/product'
import { getWarehouseBalance } from '@/api/warehouse'

const loading = ref(false)
const saving = ref(false)
const editDate = ref<string>(defaultDate())
const productRows = ref<any[]>([])
const recentRows = ref<any[]>([])
const batchList = ref<any[]>([])
const balanceMap = ref<Record<number, number>>({})

/** 某品种可选批次（已停用状态 3 不提供；未建档的批号可直接输入新建） */
function batchOptionsOf(productId: number) {
  return batchList.value.filter((b) => b.productId === productId && b.status !== 3)
}

function defaultDate(): string {
  return plusDays(new Date(), 1)
}

function plusDays(d: Date, n: number) {
  return new Date(d.getTime() + n * 86400000).toISOString().slice(0, 10)
}

function toBalanceMap(res: any): Record<number, number> {
  const map: Record<number, number> = {}
  for (const b of (res?.data ?? []) as any[]) {
    map[b.productId] = b.balance
  }
  return map
}

async function fetchBalance() {
  balanceMap.value = toBalanceMap(await getWarehouseBalance())
}

async function fetchEditData() {
  if (!editDate.value) return
  loading.value = true
  try {
    const [prodRes, remainRes, dayRes, batchRes, balanceRes] = await Promise.all([
      getProductList({ pageNum: 1, pageSize: 100 }),
      getQuotaRemainingList(editDate.value),
      getQuotaList({ startDate: editDate.value, endDate: editDate.value }),
      getBatchList(),
      getWarehouseBalance()
    ])
    balanceMap.value = toBalanceMap(balanceRes)
    batchList.value = (batchRes.data ?? []) as any[]
    const remainingMap: Record<number, number> = {}
    for (const r of (remainRes.data ?? []) as any[]) {
      remainingMap[r.productId] = r.remaining
    }
    const rowMap: Record<number, any> = {}
    for (const r of (dayRes.data ?? []) as any[]) {
      rowMap[r.productId] = r
    }
    productRows.value = (prodRes.data.list ?? []).map((p: any) => {
      const row = rowMap[p.id]
      return {
        productId: p.id,
        productName: p.productName,
        inputQuota: row ? row.totalQuota : undefined,
        used: row ? row.usedQuota : 0,
        remaining: remainingMap[p.id] ?? 0,
        batchNo: row?.batchNo ?? null,
        remark: row?.remark
      }
    })
  } finally {
    loading.value = false
  }
}

async function fetchRecent() {
  const res: any = await getQuotaList({
    startDate: plusDays(new Date(), -1),
    endDate: plusDays(new Date(), 7)
  })
  recentRows.value = res.data
}

async function handleSave() {
  const items = productRows.value
    .filter((r) => r.inputQuota !== null && r.inputQuota !== undefined)
    .map((r) => ({ productId: r.productId, totalQuota: r.inputQuota, batchNo: r.batchNo || null }))
  if (!items.length) {
    ElMessage.warning('请至少为一个品种填写当日配额')
    return
  }
  saving.value = true
  try {
    await setQuotaBatch({ quotaDate: editDate.value, items })
    ElMessage.success('已保存')
    await Promise.all([fetchEditData(), fetchRecent()])
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await fetchEditData()
  fetchRecent()
})
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  .filter-item { width: 200px; }
}
.quota-tip { margin-bottom: 12px; }
.section-title {
  margin: 20px 0 8px;
  font-weight: 600;
  color: #1f2d3d;
}
.quota-low { color: #f56c6c; font-weight: 600; }
.batch-select { width: 100%; }
</style>
