<template>
  <div>
    <div class="search-bar">
      <el-select v-model="query.categoryId" placeholder="全部品类" clearable class="filter-select" @change="handleSearch">
        <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="奶品名称 / 口味" clearable class="search-input"
        @keyup.enter="handleSearch" @clear="handleSearch">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增奶品</el-button>
    </div>

    <el-table v-loading="loading" :data="tableData" stripe>
      <el-table-column prop="productName" label="奶品名称" min-width="140" />
      <el-table-column prop="categoryName" label="品类" width="100" />
      <el-table-column prop="spec" label="规格" width="110" />
      <el-table-column prop="flavor" label="口味" width="90" />
      <el-table-column label="单价" width="90">
        <template #default="{ row }">¥{{ Number(row.price).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="库存" width="90">
        <template #default="{ row }">
          <span :class="{ 'low-stock': row.quantity <= 0 }">{{ row.quantity }}</span>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑奶品' : '新增奶品'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="奶品名称" prop="productName">
          <el-input v-model="form.productName" placeholder="如：学生纯牛奶" />
        </el-form-item>
        <el-form-item label="所属品类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="请选择品类" style="width: 100%">
            <el-option v-for="c in categoryList" :key="c.id" :label="c.categoryName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="form.spec" placeholder="如：200ml/盒" />
        </el-form-item>
        <el-form-item label="口味">
          <el-input v-model="form.flavor" placeholder="如：原味" />
        </el-form-item>
        <el-form-item label="单价(元)" prop="price">
          <el-input-number v-model="form.price" :min="0" :precision="2" :step="0.5" />
        </el-form-item>
        <el-form-item label="成本价(元)">
          <el-input-number v-model="form.costPrice" :min="0" :precision="2" :step="0.5" />
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
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
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
import { Plus, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  getProductList, getCategoryList, saveProduct, updateProduct, deleteProduct
} from '@/api/product'

const loading = ref(false)
const submitting = ref(false)
const tableData = ref<any[]>([])
const total = ref(0)
const categoryList = ref<any[]>([])
const query = reactive({ pageNum: 1, pageSize: 10, categoryId: undefined as number | undefined, keyword: '' })

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getProductList(query)
    tableData.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

async function fetchCategories() {
  const res: any = await getCategoryList()
  categoryList.value = res.data
}

function handleSearch() { query.pageNum = 1; fetchList() }
function handleReset() {
  query.categoryId = undefined
  query.keyword = ''
  query.pageNum = 1
  fetchList()
}

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<any>({
  id: null, productName: '', categoryId: null, spec: '', flavor: '',
  price: 0, costPrice: 0, sort: 0, status: 1, description: ''
})
const rules: FormRules = {
  productName: [{ required: true, message: '请输入奶品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择品类', trigger: 'change' }],
  price: [{ required: true, message: '请输入单价', trigger: 'blur' }]
}

function openDialog(row?: any) {
  Object.assign(form, {
    id: row?.id ?? null,
    productName: row?.productName ?? '',
    categoryId: row?.categoryId ?? null,
    spec: row?.spec ?? '',
    flavor: row?.flavor ?? '',
    price: row?.price ?? 0,
    costPrice: row?.costPrice ?? 0,
    sort: row?.sort ?? 0,
    status: row?.status ?? 1,
    description: row?.description ?? ''
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
        await updateProduct(form)
        ElMessage.success('修改成功')
      } else {
        await saveProduct(form)
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
  await ElMessageBox.confirm(`确定删除奶品「${row.productName}」吗？`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  })
  await deleteProduct(row.id)
  ElMessage.success('删除成功')
  fetchList()
}

onMounted(() => {
  fetchCategories()
  fetchList()
})
</script>

<style scoped lang="scss">
.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  .filter-select { width: 160px; }
  .search-input { width: 240px; }
}
.toolbar { margin-bottom: 16px; }
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
.low-stock { color: #f56c6c; font-weight: 600; }
</style>
