<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增品类</el-button>
    </div>
    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="categoryName" label="品类名称" min-width="140" />
      <el-table-column prop="categoryCode" label="品类编码" min-width="140" />
      <el-table-column prop="sort" label="排序" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '上架' : '下架' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑品类' : '新增品类'" width="420px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="品类名称" prop="categoryName">
          <el-input v-model="form.categoryName" placeholder="如：纯牛奶" />
        </el-form-item>
        <el-form-item label="品类编码">
          <el-input v-model="form.categoryCode" placeholder="如：PURE_MILK" />
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
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
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
import { getCategoryList, saveCategory, updateCategory, deleteCategory } from '@/api/product'

const loading = ref(false)
const submitting = ref(false)
const list = ref<any[]>([])

async function fetchList() {
  loading.value = true
  try {
    const res: any = await getCategoryList()
    list.value = res.data
  } finally {
    loading.value = false
  }
}

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<any>({ id: null, categoryName: '', categoryCode: '', sort: 0, status: 1, remark: '' })
const rules: FormRules = {
  categoryName: [{ required: true, message: '请输入品类名称', trigger: 'blur' }]
}

function openDialog(row?: any) {
  Object.assign(form, {
    id: row?.id ?? null,
    categoryName: row?.categoryName ?? '',
    categoryCode: row?.categoryCode ?? '',
    sort: row?.sort ?? 0,
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
        await updateCategory(form)
        ElMessage.success('修改成功')
      } else {
        await saveCategory(form)
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
  await ElMessageBox.confirm(`确定删除品类「${row.categoryName}」吗？`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  })
  await deleteCategory(row.id)
  ElMessage.success('删除成功')
  fetchList()
}

onMounted(fetchList)
</script>

<style scoped lang="scss">
.toolbar {
  margin-bottom: 16px;
}
</style>
