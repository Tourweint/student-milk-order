<template>
  <div class="intake-tab">
    <!-- 筛选 -->
    <div class="filter-bar">
      <el-select v-model="query.studentId" placeholder="选择学生" filterable class="filter-item" @change="handleStudentChange">
        <el-option v-for="s in studentList" :key="s.id" :label="`${s.studentName}（${s.studentNo}）`" :value="s.id" />
      </el-select>
      <el-date-picker
        v-model="dateRange" type="daterange" range-separator="至"
        start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
        class="filter-item date-picker"
      />
      <el-button type="primary" @click="fetchAll">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <template v-if="query.studentId">
      <!-- 汇总卡片 -->
      <div class="summary-cards">
        <div class="card">
          <div class="card-label">总摄入量</div>
          <div class="card-value">{{ totals.totalMl }} <span class="unit">ml</span></div>
        </div>
        <div class="card">
          <div class="card-label">总能量</div>
          <div class="card-value">{{ formatNum(totals.totalEnergy) }} <span class="unit">千焦</span></div>
        </div>
        <div class="card">
          <div class="card-label">总蛋白质</div>
          <div class="card-value">{{ formatNum(totals.totalProtein) }} <span class="unit">克</span></div>
        </div>
        <div class="card">
          <div class="card-label">总脂肪</div>
          <div class="card-value">{{ formatNum(totals.totalFat) }} <span class="unit">克</span></div>
        </div>
        <div class="card">
          <div class="card-label">总钙</div>
          <div class="card-value">{{ formatNum(totals.totalCalcium) }} <span class="unit">毫克</span></div>
        </div>
      </div>

      <!-- 按日汇总 -->
      <div class="section-title">每日摄入汇总</div>
      <el-table :data="summaryList" stripe size="small" class="summary-table">
        <el-table-column prop="intakeDate" label="日期" width="130" />
        <el-table-column prop="totalMl" label="摄入量(ml)" width="120" />
        <el-table-column label="能量(千焦)" width="120">
          <template #default="{ row }">{{ formatNum(row.totalEnergy) }}</template>
        </el-table-column>
        <el-table-column label="蛋白质(克)" width="120">
          <template #default="{ row }">{{ formatNum(row.totalProtein) }}</template>
        </el-table-column>
        <el-table-column label="脂肪(克)" width="110">
          <template #default="{ row }">{{ formatNum(row.totalFat) }}</template>
        </el-table-column>
        <el-table-column label="钙(毫克)" width="110">
          <template #default="{ row }">{{ formatNum(row.totalCalcium) }}</template>
        </el-table-column>
        <el-table-column prop="productCount" label="奶品种类" width="100" />
      </el-table>

      <!-- 摄入明细 -->
      <div class="section-title">摄入明细</div>
      <el-table v-loading="loading" :data="intakeList" stripe size="small">
        <el-table-column prop="intakeDate" label="日期" width="130" />
        <el-table-column prop="productName" label="奶品" min-width="120" />
        <el-table-column prop="quantity" label="摄入量(ml)" width="120" />
        <el-table-column label="能量(千焦)" width="120">
          <template #default="{ row }">{{ formatNum(row.energy) }}</template>
        </el-table-column>
        <el-table-column label="蛋白质(克)" width="120">
          <template #default="{ row }">{{ formatNum(row.protein) }}</template>
        </el-table-column>
        <el-table-column label="脂肪(克)" width="110">
          <template #default="{ row }">{{ formatNum(row.fat) }}</template>
        </el-table-column>
        <el-table-column label="钙(毫克)" width="110">
          <template #default="{ row }">{{ formatNum(row.calcium) }}</template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="fetchIntakeList"
          @current-change="fetchIntakeList"
        />
      </div>
    </template>

    <el-empty v-else description="请先选择学生查看营养摄入统计" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getNutritionIntakeList, getNutritionIntakeSummary } from '@/api/nutrition'
import { getStudentList } from '@/api/student'

const loading = ref(false)
const studentList = ref<any[]>([])
const intakeList = ref<any[]>([])
const summaryList = ref<any[]>([])
const total = ref(0)
const dateRange = ref<[string, string] | null>(null)

const query = reactive({
  pageNum: 1, pageSize: 10,
  studentId: undefined as number | undefined
})

const totals = computed(() => {
  return summaryList.value.reduce((acc, item) => ({
    totalMl: acc.totalMl + (item.totalMl || 0),
    totalEnergy: acc.totalEnergy + (Number(item.totalEnergy) || 0),
    totalProtein: acc.totalProtein + (Number(item.totalProtein) || 0),
    totalFat: acc.totalFat + (Number(item.totalFat) || 0),
    totalCalcium: acc.totalCalcium + (Number(item.totalCalcium) || 0)
  }), { totalMl: 0, totalEnergy: 0, totalProtein: 0, totalFat: 0, totalCalcium: 0 })
})

function formatNum(v: any) {
  if (v === null || v === undefined || v === '') return '—'
  return Number(v).toFixed(1)
}

async function fetchStudents() {
  const res: any = await getStudentList({ pageNum: 1, pageSize: 999 })
  studentList.value = res.data.list || []
}

function handleStudentChange() {
  query.pageNum = 1
  fetchAll()
}

function handleReset() {
  dateRange.value = null
  query.pageNum = 1
  fetchAll()
}

async function fetchAll() {
  if (!query.studentId) return
  query.pageNum = 1
  await Promise.all([fetchSummary(), fetchIntakeList()])
}

async function fetchSummary() {
  const res: any = await getNutritionIntakeSummary({
    studentId: query.studentId!,
    startDate: dateRange.value?.[0],
    endDate: dateRange.value?.[1]
  })
  summaryList.value = res.data || []
}

async function fetchIntakeList() {
  loading.value = true
  try {
    const res: any = await getNutritionIntakeList({
      ...query,
      startDate: dateRange.value?.[0],
      endDate: dateRange.value?.[1]
    })
    intakeList.value = res.data.list
    total.value = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

onMounted(fetchStudents)
</script>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  flex-wrap: wrap;
  align-items: center;
  .filter-item { width: 200px; }
  .date-picker { width: 280px; }
}
.summary-cards {
  display: flex;
  gap: 14px;
  margin-bottom: 20px;
  flex-wrap: wrap;
  .card {
    flex: 1;
    min-width: 140px;
    background: #f5f7fa;
    border-radius: 8px;
    padding: 16px;
    text-align: center;
    .card-label { color: #909399; font-size: 13px; margin-bottom: 8px; }
    .card-value {
      font-size: 22px;
      font-weight: 600;
      color: #303133;
      .unit { font-size: 12px; font-weight: normal; color: #909399; }
    }
  }
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  margin: 20px 0 10px;
  color: #303133;
}
.summary-table { margin-bottom: 8px; }
.pagination { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
