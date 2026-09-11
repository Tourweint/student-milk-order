<template>
  <div class="class-manage">
    <el-tabs v-model="activeTab" class="main-tabs">
      <!-- ==================== 年级管理 ==================== -->
      <el-tab-pane label="年级管理" name="grade">
        <div class="toolbar">
          <el-button type="primary" :icon="Plus" @click="openGradeDialog()">新增年级</el-button>
        </div>
        <el-table v-loading="gradeLoading" :data="gradeList" stripe>
          <el-table-column prop="gradeName" label="年级名称" min-width="140" />
          <el-table-column prop="gradeCode" label="年级编码" min-width="120" />
          <el-table-column prop="sort" label="排序" width="100" />
          <el-table-column prop="remark" label="备注" min-width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openGradeDialog(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDeleteGrade(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ==================== 班级管理 ==================== -->
      <el-tab-pane label="班级管理" name="class">
        <div class="search-bar">
          <el-select v-model="classQuery.gradeId" placeholder="全部年级" clearable class="filter-select" @change="handleClassSearch">
            <el-option v-for="g in gradeList" :key="g.id" :label="g.gradeName" :value="g.id" />
          </el-select>
          <el-button @click="handleClassReset">重置</el-button>
        </div>
        <div class="toolbar">
          <el-button type="primary" :icon="Plus" @click="openClassDialog()">新增班级</el-button>
        </div>
        <el-table v-loading="classLoading" :data="classTableData" stripe>
          <el-table-column prop="className" label="班级名称" min-width="140" />
          <el-table-column prop="gradeName" label="所属年级" min-width="120" />
          <el-table-column prop="teacherName" label="班主任" min-width="120">
            <template #default="{ row }">{{ row.teacherName || '—' }}</template>
          </el-table-column>
          <el-table-column prop="studentCount" label="学生人数" width="100" />
          <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openClassDialog(row)">编辑</el-button>
              <el-button link type="danger" @click="handleDeleteClass(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pagination">
          <el-pagination
            v-model:current-page="classQuery.pageNum"
            v-model:page-size="classQuery.pageSize"
            :total="classTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="fetchClassList"
            @current-change="fetchClassList"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 年级对话框 -->
    <el-dialog v-model="gradeDialogVisible" :title="gradeForm.id ? '编辑年级' : '新增年级'" width="420px" destroy-on-close>
      <el-form ref="gradeFormRef" :model="gradeForm" :rules="gradeRules" label-width="80px">
        <el-form-item label="年级名称" prop="gradeName">
          <el-input v-model="gradeForm.gradeName" placeholder="如：一年级" />
        </el-form-item>
        <el-form-item label="年级编码">
          <el-input v-model="gradeForm.gradeCode" placeholder="如：G1" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="gradeForm.sort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="gradeForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="gradeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleGradeSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 班级对话框 -->
    <el-dialog v-model="classDialogVisible" :title="classForm.id ? '编辑班级' : '新增班级'" width="460px" destroy-on-close>
      <el-form ref="classFormRef" :model="classForm" :rules="classRules" label-width="80px">
        <el-form-item label="班级名称" prop="className">
          <el-input v-model="classForm.className" placeholder="如：一年级1班" />
        </el-form-item>
        <el-form-item label="所属年级" prop="gradeId">
          <el-select v-model="classForm.gradeId" placeholder="请选择年级" style="width: 100%">
            <el-option v-for="g in gradeList" :key="g.id" :label="g.gradeName" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="班主任">
          <el-select v-model="classForm.teacherId" placeholder="请选择班主任（可暂不指定）" clearable style="width: 100%">
            <el-option v-for="t in teacherOptions" :key="t.id" :label="t.realName" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="classForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="classDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleClassSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  getGradeList, saveGrade, updateGrade, deleteGrade,
  getClassList, getTeacherOptions, saveClass, updateClass, deleteClass
} from '@/api/clazz'

const activeTab = ref('grade')
const submitting = ref(false)

// ==================== 年级 ====================
const gradeLoading = ref(false)
const gradeList = ref<any[]>([])

async function fetchGradeList() {
  gradeLoading.value = true
  try {
    const res: any = await getGradeList()
    gradeList.value = res.data
  } finally {
    gradeLoading.value = false
  }
}

const gradeDialogVisible = ref(false)
const gradeFormRef = ref<FormInstance>()
const gradeForm = reactive<any>({ id: null, gradeName: '', gradeCode: '', sort: 0, remark: '' })
const gradeRules: FormRules = {
  gradeName: [{ required: true, message: '请输入年级名称', trigger: 'blur' }]
}

function openGradeDialog(row?: any) {
  Object.assign(gradeForm, {
    id: row?.id ?? null,
    gradeName: row?.gradeName ?? '',
    gradeCode: row?.gradeCode ?? '',
    sort: row?.sort ?? 0,
    remark: row?.remark ?? ''
  })
  gradeDialogVisible.value = true
}

async function handleGradeSubmit() {
  if (!gradeFormRef.value) return
  await gradeFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (gradeForm.id) {
        await updateGrade(gradeForm)
        ElMessage.success('修改成功')
      } else {
        await saveGrade(gradeForm)
        ElMessage.success('新增成功')
      }
      gradeDialogVisible.value = false
      fetchGradeList()
    } finally {
      submitting.value = false
    }
  })
}

async function handleDeleteGrade(row: any) {
  await ElMessageBox.confirm(`确定删除年级「${row.gradeName}」吗？`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  })
  await deleteGrade(row.id)
  ElMessage.success('删除成功')
  fetchGradeList()
}

// ==================== 班级 ====================
const classLoading = ref(false)
const classTableData = ref<any[]>([])
const classTotal = ref(0)
const classQuery = reactive({ pageNum: 1, pageSize: 10, gradeId: undefined as number | undefined })
const teacherOptions = ref<any[]>([])

async function fetchClassList() {
  classLoading.value = true
  try {
    const res: any = await getClassList(classQuery)
    classTableData.value = res.data.list
    classTotal.value = Number(res.data.total)
  } finally {
    classLoading.value = false
  }
}

async function fetchTeacherOptions() {
  const res: any = await getTeacherOptions()
  teacherOptions.value = res.data
}

function handleClassSearch() {
  classQuery.pageNum = 1
  fetchClassList()
}

function handleClassReset() {
  classQuery.gradeId = undefined
  classQuery.pageNum = 1
  fetchClassList()
}

const classDialogVisible = ref(false)
const classFormRef = ref<FormInstance>()
const classForm = reactive<any>({ id: null, className: '', gradeId: null, teacherId: null, remark: '' })
const classRules: FormRules = {
  className: [{ required: true, message: '请输入班级名称', trigger: 'blur' }],
  gradeId: [{ required: true, message: '请选择所属年级', trigger: 'change' }]
}

function openClassDialog(row?: any) {
  Object.assign(classForm, {
    id: row?.id ?? null,
    className: row?.className ?? '',
    gradeId: row?.gradeId ?? null,
    teacherId: row?.teacherId ?? null,
    remark: row?.remark ?? ''
  })
  classDialogVisible.value = true
}

async function handleClassSubmit() {
  if (!classFormRef.value) return
  await classFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (classForm.id) {
        await updateClass(classForm)
        ElMessage.success('修改成功')
      } else {
        await saveClass(classForm)
        ElMessage.success('新增成功')
      }
      classDialogVisible.value = false
      fetchClassList()
    } finally {
      submitting.value = false
    }
  })
}

async function handleDeleteClass(row: any) {
  await ElMessageBox.confirm(`确定删除班级「${row.className}」吗？`, '提示', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning'
  })
  await deleteClass(row.id)
  ElMessage.success('删除成功')
  fetchClassList()
}

onMounted(() => {
  fetchGradeList()
  fetchClassList()
  fetchTeacherOptions()
})
</script>

<style scoped lang="scss">
.class-manage {
  padding: 20px;
}

.main-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 16px;
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;

  .filter-select {
    width: 200px;
  }
}

.toolbar {
  margin-bottom: 16px;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
