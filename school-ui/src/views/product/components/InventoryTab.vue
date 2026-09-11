<template>
  <div>
    <el-tabs v-model="subTab">
      <!-- ============ 当前库存 ============ -->
      <el-tab-pane label="当前库存" name="stock">
        <div class="search-bar">
          <el-input v-model="stockQuery.keyword" placeholder="搜索奶品名称" clearable class="search-input"
            @keyup.enter="handleStockSearch" @clear="handleStockSearch">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="primary" @click="handleStockSearch">搜索</el-button>
          <el-button @click="loadWarning">刷新预警</el-button>
          <el-tag v-if="warningCount > 0" type="danger" size="large" class="warn-tag">
            {{ warningCount }} 种奶品库存预警
          </el-tag>
        </div>

        <el-table v-loading="stockLoading" :data="stockData" stripe>
          <el-table-column prop="productName" label="奶品" min-width="140" />
          <el-table-column prop="categoryName" label="品类" width="100" />
          <el-table-column prop="spec" label="规格" width="110" />
          <el-table-column label="当前库存" width="110">
            <template #default="{ row }">
              <span :class="row.warning ? 'low-stock' : 'normal-stock'">{{ row.quantity }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="warningThreshold" label="预警阈值" width="100" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.warning ? 'danger' : 'success'" size="small">
                {{ row.warning ? '预警' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="warehouseLocation" label="库位" width="110">
            <template #default="{ row }">{{ row.warehouseLocation || '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openChangeDialog(row, 1)">入库</el-button>
              <el-button link type="warning" @click="openChangeDialog(row, 2)">出库</el-button>
              <el-button link @click="openThresholdDialog(row)">阈值/库位</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination">
          <el-pagination
            v-model:current-page="stockQuery.pageNum"
            v-model:page-size="stockQuery.pageSize"
            :total="stockTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="loadStock"
            @current-change="loadStock"
          />
        </div>
      </el-tab-pane>

      <!-- ============ 变动流水 ============ -->
      <el-tab-pane label="变动流水" name="record">
        <div class="search-bar">
          <el-select v-model="recordQuery.changeType" placeholder="全部类型" clearable class="filter-select" @change="handleRecordSearch">
            <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <el-button type="primary" @click="handleRecordSearch">查询</el-button>
          <el-button @click="handleRecordReset">重置</el-button>
        </div>

        <el-table v-loading="recordLoading" :data="recordData" stripe>
          <el-table-column prop="createTime" label="时间" min-width="160" />
          <el-table-column prop="productName" label="奶品" min-width="140" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag :type="typeTag(row.changeType)" size="small">{{ typeText(row.changeType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="变动数量" width="110">
            <template #default="{ row }">
              <span :class="row.changeQuantity >= 0 ? 'in-qty' : 'out-qty'">
                {{ row.changeQuantity >= 0 ? '+' : '' }}{{ row.changeQuantity }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="beforeQuantity" label="变动前" width="90" />
          <el-table-column prop="afterQuantity" label="变动后" width="90" />
          <el-table-column prop="operatorName" label="操作人" width="110" />
          <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        </el-table>

        <div class="pagination">
          <el-pagination
            v-model:current-page="recordQuery.pageNum"
            v-model:page-size="recordQuery.pageSize"
            :total="recordTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="loadRecords"
            @current-change="loadRecords"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 入库/出库/盘点对话框 -->
    <el-dialog v-model="changeVisible" :title="changeTitle" width="420px" destroy-on-close>
      <el-form ref="changeRef" :model="changeForm" :rules="changeRules" label-width="90px">
        <el-form-item label="奶品">
          <span>{{ changeForm.productName }}</span>
        </el-form-item>
        <el-form-item label="变动类型" prop="changeType">
          <el-select v-model="changeForm.changeType" style="width: 100%">
            <el-option v-for="t in typeOptions" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="数量" prop="changeQuantity">
          <el-input-number v-model="changeForm.changeQuantity" :min="1" :step="10" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="changeForm.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="changeVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleChange">确定</el-button>
      </template>
    </el-dialog>

    <!-- 阈值/库位对话框 -->
    <el-dialog v-model="thresholdVisible" title="设置预警阈值与库位" width="420px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="奶品"><span>{{ thresholdForm.productName }}</span></el-form-item>
        <el-form-item label="预警阈值">
          <el-input-number v-model="thresholdForm.warningThreshold" :min="0" :step="10" />
        </el-form-item>
        <el-form-item label="仓库位置">
          <el-input v-model="thresholdForm.warehouseLocation" placeholder="如：A区-01" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="thresholdVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleThreshold">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  getInventoryList, getInventoryWarning, changeInventory,
  updateInventoryThreshold, getInventoryRecords
} from '@/api/product'

const subTab = ref('stock')
const submitting = ref(false)

const typeOptions = [
  { value: 1, label: '入库' },
  { value: 2, label: '出库' },
  { value: 3, label: '盘盈' },
  { value: 4, label: '盘亏' }
]
const typeText = (t: number) => typeOptions.find((x) => x.value === t)?.label ?? '未知'
const typeTag = (t: number) => (t === 1 || t === 3 ? 'success' : 'warning')

// ============ 当前库存 ============
const stockLoading = ref(false)
const stockData = ref<any[]>([])
const stockTotal = ref(0)
const warningCount = ref(0)
const stockQuery = reactive({ pageNum: 1, pageSize: 10, keyword: '' })

async function loadStock() {
  stockLoading.value = true
  try {
    const res: any = await getInventoryList(stockQuery)
    stockData.value = res.data.list
    stockTotal.value = Number(res.data.total)
  } finally {
    stockLoading.value = false
  }
}
function handleStockSearch() { stockQuery.pageNum = 1; loadStock() }
async function loadWarning() {
  const res: any = await getInventoryWarning()
  warningCount.value = res.data.length
  loadStock()
}

// ============ 变动流水 ============
const recordLoading = ref(false)
const recordData = ref<any[]>([])
const recordTotal = ref(0)
const recordQuery = reactive({ pageNum: 1, pageSize: 10, changeType: undefined as number | undefined })

async function loadRecords() {
  recordLoading.value = true
  try {
    const res: any = await getInventoryRecords(recordQuery)
    recordData.value = res.data.list
    recordTotal.value = Number(res.data.total)
  } finally {
    recordLoading.value = false
  }
}
function handleRecordSearch() { recordQuery.pageNum = 1; loadRecords() }
function handleRecordReset() { recordQuery.changeType = undefined; recordQuery.pageNum = 1; loadRecords() }

// ============ 变动对话框 ============
const changeVisible = ref(false)
const changeRef = ref<FormInstance>()
const changeForm = reactive<any>({ productId: null, productName: '', changeType: 1, changeQuantity: 1, remark: '' })
const changeRules: FormRules = {
  changeType: [{ required: true, message: '请选择变动类型', trigger: 'change' }],
  changeQuantity: [{ required: true, message: '请输入数量', trigger: 'blur' }]
}
const changeTitle = ref('库存变动')

function openChangeDialog(row: any, type: number) {
  Object.assign(changeForm, {
    productId: row.productId, productName: row.productName,
    changeType: type, changeQuantity: 1, remark: ''
  })
  changeTitle.value = type === 1 ? '入库' : type === 2 ? '出库' : '库存变动'
  changeVisible.value = true
}

async function handleChange() {
  if (!changeRef.value) return
  await changeRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await changeInventory(changeForm)
      ElMessage.success('操作成功')
      changeVisible.value = false
      loadStock()
      loadWarning()
    } finally {
      submitting.value = false
    }
  })
}

// ============ 阈值对话框 ============
const thresholdVisible = ref(false)
const thresholdForm = reactive<any>({ id: null, productName: '', warningThreshold: 0, warehouseLocation: '' })

function openThresholdDialog(row: any) {
  Object.assign(thresholdForm, {
    id: row.id, productName: row.productName,
    warningThreshold: row.warningThreshold ?? 0,
    warehouseLocation: row.warehouseLocation ?? ''
  })
  thresholdVisible.value = true
}

async function handleThreshold() {
  submitting.value = true
  try {
    await updateInventoryThreshold(thresholdForm)
    ElMessage.success('设置成功')
    thresholdVisible.value = false
    loadStock()
    loadWarning()
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadStock()
  loadRecords()
  loadWarning()
})
</script>

<style scoped lang="scss">
.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  align-items: center;
  .search-input { width: 240px; }
  .filter-select { width: 160px; }
  .warn-tag { margin-left: 8px; }
}
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
.low-stock { color: #f56c6c; font-weight: 700; }
.normal-stock { font-weight: 600; }
.in-qty { color: #67c23a; font-weight: 600; }
.out-qty { color: #e6a23c; font-weight: 600; }
</style>
