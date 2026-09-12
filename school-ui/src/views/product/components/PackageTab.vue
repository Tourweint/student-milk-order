<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增套餐</el-button>
    </div>
    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="packageName" label="套餐名称" min-width="160" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="row.packageType === 1 ? '' : 'success'" size="small">
            {{ row.packageType === 1 ? '按月套餐' : '按学期套餐' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="原价" width="100">
        <template #default="{ row }">¥{{ Number(row.originalPrice ?? 0).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="优惠价" width="100">
        <template #default="{ row }">
          <span class="discount">¥{{ Number(row.discountPrice).toFixed(2) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="有效期" min-width="200">
        <template #default="{ row }">
          {{ row.startDate || '—' }} 至 {{ row.endDate || '—' }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '上架' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑套餐' : '新增套餐'" width="500px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="套餐名称" prop="packageName">
          <el-input v-model="form.packageName" placeholder="如：纯牛奶月度套餐" />
        </el-form-item>
        <el-form-item label="套餐类型" prop="packageType">
          <el-radio-group v-model="form.packageType">
            <el-radio :value="1">按月套餐</el-radio>
            <el-radio :value="2">按学期套餐</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="原价(元)">
          <el-input-number v-model="form.originalPrice" :min="0" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item label="优惠价(元)" prop="discountPrice">
          <el-input-number v-model="form.discountPrice" :min="0" :precision="2" :step="1" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker
            v-model="dateRange" type="daterange" range-separator="至"
            start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">上架</el-radio>
            <el-radio :value="0">下架</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="套餐描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="配送明细" prop="items">
          <div class="pkg-items">
            <el-select v-model="selectedProductIds" multiple filterable placeholder="选择套餐包含的奶品（每日按配置数量配送）" class="full-width">
              <el-option v-for="p in products" :key="p.id" :label="p.productName" :value="p.id" />
            </el-select>
            <div v-for="pid in selectedProductIds" :key="pid" class="pkg-item-row">
              <span class="pkg-item-name">{{ productName(pid) }}</span>
              <span class="pkg-item-label">每日</span>
              <el-input-number v-model="itemQty[pid]" :min="1" :max="20" size="small" />
              <span class="pkg-item-label">盒</span>
            </div>
            <div class="pkg-item-tip">套餐内容固定，家长下单时不可自选；价格与明细数量不联动，请据此定价</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { getPackageList, getPackageById, savePackage, updatePackage, deletePackage, getProductList } from '@/api/product'

const loading = ref(false)
const submitting = ref(false)
const list = ref<any[]>([])
const products = ref<any[]>([])
const selectedProductIds = ref<number[]>([])
const itemQty = reactive<Record<number, number>>({})

const productName = (id: number) => products.value.find((p) => p.id === id)?.productName ?? `奶品${id}`

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getPackageList()
    list.value = res.data
  } finally {
    loading.value = false
  }
}

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const dateRange = ref<[string, string] | null>(null)
const form = reactive<any>({
  id: null, packageName: '', packageType: 1, originalPrice: 0, discountPrice: 0,
  startDate: '', endDate: '', sort: 0, status: 1, description: ''
})
const rules: FormRules = {
  packageName: [{ required: true, message: '请输入套餐名称', trigger: 'blur' }],
  packageType: [{ required: true, message: '请选择套餐类型', trigger: 'change' }],
  discountPrice: [{ required: true, message: '请输入优惠价', trigger: 'blur' }]
}

async function openDialog(row?: any) {
  Object.assign(form, {
    id: row?.id ?? null,
    packageName: row?.packageName ?? '',
    packageType: row?.packageType ?? 1,
    originalPrice: row?.originalPrice ?? 0,
    discountPrice: row?.discountPrice ?? 0,
    startDate: row?.startDate ?? '',
    endDate: row?.endDate ?? '',
    sort: row?.sort ?? 0,
    status: row?.status ?? 1,
    description: row?.description ?? ''
  })
  dateRange.value = row?.startDate ? [row.startDate, row.endDate] : null
  // 编辑时加载套餐固定明细
  selectedProductIds.value = []
  Object.keys(itemQty).forEach((k) => delete itemQty[Number(k)])
  if (row?.id) {
    const res: any = await getPackageById(row.id)
    for (const item of (res.data?.items ?? []) as any[]) {
      selectedProductIds.value.push(item.productId)
      itemQty[item.productId] = item.quantity ?? 1
    }
  }
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (!selectedProductIds.value.length) {
      ElMessage.warning('请配置套餐固定配送明细（至少一种奶品）')
      return
    }
    if (dateRange.value) {
      form.startDate = dateRange.value[0]
      form.endDate = dateRange.value[1]
    } else {
      form.startDate = null
      form.endDate = null
    }
    form.items = selectedProductIds.value.map((pid) => ({ productId: pid, quantity: itemQty[pid] ?? 1 }))
    submitting.value = true
    try {
      if (form.id) {
        await updatePackage(form)
        ElMessage.success('修改成功')
      } else {
        await savePackage(form)
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
  await ElMessageBox.confirm(`确定删除套餐「${row.packageName}」吗？`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  })
  await deletePackage(row.id)
  ElMessage.success('删除成功')
  fetchList()
}

onMounted(async () => {
  fetchList()
  const res: any = await getProductList({ pageNum: 1, pageSize: 100 })
  products.value = res.data.list || []
})
</script>

<style scoped lang="scss">
.toolbar { margin-bottom: 16px; }
.discount { color: #f56c6c; font-weight: 600; }
.full-width { width: 100%; }
.pkg-items {
  width: 100%;
  .pkg-item-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    .pkg-item-name { min-width: 120px; }
    .pkg-item-label { color: #909399; }
  }
  .pkg-item-tip {
    margin-top: 8px;
    font-size: 12px;
    color: #909399;
    line-height: 1.5;
  }
}
</style>
