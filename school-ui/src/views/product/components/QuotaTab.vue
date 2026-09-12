<template>
  <div class="quota-tab">
    <div class="toolbar">
      <el-date-picker
        v-model="dateRange" type="daterange" range-separator="至"
        start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
        class="filter-item" @change="fetchList"
      />
      <el-button type="primary" @click="fetchList">查询</el-button>
      <el-button type="success" :icon="Plus" @click="openDialog()">设置配额</el-button>
    </div>

    <el-alert
      type="info" :closable="false" class="quota-tip"
      title="每日机动配额仅用于单日零散订购（临时补订、换口味、插班临时订购）；当日未售完自动结转次日继续可售，按 3 天保质期滚动，超龄自动作废。学期套餐不占用配额。"
    />

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="quotaDate" label="日期" width="140" />
      <el-table-column prop="totalQuota" label="机动总盒数" width="120" />
      <el-table-column prop="usedQuota" label="已售盒数" width="120" />
      <el-table-column label="剩余盒数" width="120">
        <template #default="{ row }">
          <span :class="{ 'quota-low': row.totalQuota - row.usedQuota <= 0 }">
            {{ row.totalQuota - row.usedQuota }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">调整</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!loading && !list.length" class="empty-tip">所选区间未设置配额，当日不可零散订购</div>

    <el-dialog v-model="dialogVisible" :title="form.quotaDate ? '调整配额' : '设置配额'" width="420px">
      <el-form label-width="100px">
        <el-form-item label="配额日期" required>
          <el-date-picker v-model="form.quotaDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
        </el-form-item>
        <el-form-item label="机动总盒数" required>
          <el-input-number v-model="form.totalQuota" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" />
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
import { ElMessage } from 'element-plus'
import { getQuotaList, setQuota } from '@/api/product'

const loading = ref(false)
const submitting = ref(false)
const list = ref<any[]>([])
const dateRange = ref<[string, string] | null>(defaultRange())
const dialogVisible = ref(false)
const form = reactive<any>({ quotaDate: '', totalQuota: 50, remark: '' })

function defaultRange(): [string, string] {
  const fmt = (d: Date) => d.toISOString().slice(0, 10)
  const start = new Date()
  const end = new Date(Date.now() + 6 * 86400000)
  return [fmt(start), fmt(end)]
}

async function fetchList() {
  loading.value = true
  try {
    const params: any = {}
    if (dateRange.value) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    const res: any = await getQuotaList(params)
    list.value = res.data
  } finally {
    loading.value = false
  }
}

function openDialog(row?: any) {
  form.quotaDate = row?.quotaDate ?? ''
  form.totalQuota = row?.totalQuota ?? 50
  form.remark = row?.remark ?? ''
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.quotaDate) {
    ElMessage.warning('请选择配额日期')
    return
  }
  submitting.value = true
  try {
    await setQuota({ quotaDate: form.quotaDate, totalQuota: form.totalQuota, remark: form.remark || undefined })
    ElMessage.success('已保存')
    dialogVisible.value = false
    fetchList()
  } finally {
    submitting.value = false
  }
}

onMounted(fetchList)
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  .filter-item { width: 280px; }
}
.quota-tip { margin-bottom: 12px; }
.empty-tip {
  text-align: center;
  color: #909399;
  padding: 24px 0;
}
.quota-low { color: #f56c6c; font-weight: 600; }
</style>
