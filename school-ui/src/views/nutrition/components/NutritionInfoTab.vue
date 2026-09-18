<template>
  <div class="info-tab">
    <div class="toolbar">
      <span class="hint">每 100ml 奶品的营养成分含量，签收时据此计算学生实际摄入量</span>
      <div class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增营养成分</el-button>
    </div>

    <el-table v-loading="loading" :data="tableData" stripe>
      <el-table-column prop="productName" label="奶品" min-width="120" />
      <el-table-column prop="spec" label="规格" width="100" />
      <el-table-column label="能量(千焦)" width="110">
        <template #default="{ row }">{{ row.energy ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="蛋白质(克)" width="110">
        <template #default="{ row }">{{ row.protein ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="脂肪(克)" width="100">
        <template #default="{ row }">{{ row.fat ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="碳水(克)" width="100">
        <template #default="{ row }">{{ row.carbohydrate ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="钙(毫克)" width="100">
        <template #default="{ row }">{{ row.calcium ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="钠(毫克)" width="100">
        <template #default="{ row }">{{ row.sodium ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑营养成分' : '新增营养成分'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="奶品" prop="productId">
          <el-select v-model="form.productId" placeholder="选择奶品" filterable class="full-width" :disabled="isEdit">
            <el-option v-for="p in productList" :key="p.id" :label="`${p.productName}（${p.spec}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="能量(千焦/100ml)">
          <el-input-number v-model="form.energy" :min="0" :precision="2" :step="1" class="full-width" />
        </el-form-item>
        <el-form-item label="蛋白质(克/100ml)">
          <el-input-number v-model="form.protein" :min="0" :precision="2" :step="0.1" class="full-width" />
        </el-form-item>
        <el-form-item label="脂肪(克/100ml)">
          <el-input-number v-model="form.fat" :min="0" :precision="2" :step="0.1" class="full-width" />
        </el-form-item>
        <el-form-item label="碳水(克/100ml)">
          <el-input-number v-model="form.carbohydrate" :min="0" :precision="2" :step="0.1" class="full-width" />
        </el-form-item>
        <el-form-item label="钙(毫克/100ml)">
          <el-input-number v-model="form.calcium" :min="0" :precision="2" :step="1" class="full-width" />
        </el-form-item>
        <el-form-item label="钠(毫克/100ml)">
          <el-input-number v-model="form.sodium" :min="0" :precision="2" :step="1" class="full-width" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { getNutritionInfoList, saveNutritionInfo } from '@/api/nutrition'
import { getProductList } from '@/api/product'

const loading = ref(false)
const tableData = ref<any[]>([])
const productList = ref<any[]>([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = reactive({
  productId: undefined as number | undefined,
  energy: undefined as number | undefined,
  protein: undefined as number | undefined,
  fat: undefined as number | undefined,
  carbohydrate: undefined as number | undefined,
  calcium: undefined as number | undefined,
  sodium: undefined as number | undefined
})

const rules: FormRules = {
  productId: [{ required: true, message: '请选择奶品', trigger: 'change' }]
}

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getNutritionInfoList()
    tableData.value = res.data
  } finally {
    loading.value = false
  }
}

async function fetchProducts() {
  const res: any = await getProductList({ pageNum: 1, pageSize: 999 })
  productList.value = res.data.list || []
}

function openDialog(row?: any) {
  if (row) {
    isEdit.value = true
    form.productId = row.productId
    form.energy = row.energy
    form.protein = row.protein
    form.fat = row.fat
    form.carbohydrate = row.carbohydrate
    form.calcium = row.calcium
    form.sodium = row.sodium
  } else {
    isEdit.value = false
    form.productId = undefined
    form.energy = undefined
    form.protein = undefined
    form.fat = undefined
    form.carbohydrate = undefined
    form.calcium = undefined
    form.sodium = undefined
  }
  dialogVisible.value = true
}

async function handleSave() {
  if (!formRef.value) return
  await formRef.value.validate()
  const productId = form.productId
  if (!productId) {
    ElMessage.warning('请选择奶品')
    return
  }
  saving.value = true
  try {
    await saveNutritionInfo({ ...form, productId })
    ElMessage.success('保存成功')
    dialogVisible.value = false
    fetchList()
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  fetchProducts()
  fetchList()
})
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
  .hint { color: #909399; font-size: 13px; }
  .spacer { flex: 1; }
}
.full-width { width: 100%; }
</style>
