<template>
  <div class="dashboard">
    <!-- 待签收提醒：管理员全局 / 班主任本班（点击直达配送管理一键签收） -->
    <el-alert
      v-if="stats.pendingSignCount > 0"
      type="warning"
      :closable="false"
      class="pending-alert"
      show-icon
      @click="goPendingSign"
    >
      <template #title>
        今日还有 <b>{{ stats.pendingSignCount }}</b> 条配送记录待签收，点击前往一键签收（次日凌晨将自动签收兜底）
      </template>
    </el-alert>

    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stats-row">
      <el-col :span="6">
        <StatsCard label="订单总数" :value="stats.totalOrders" icon="List" icon-bg="#409eff" />
      </el-col>
      <el-col :span="6">
        <StatsCard label="在订学生" :value="stats.activeStudents" icon="Avatar" icon-bg="#67c23a" />
      </el-col>
      <el-col :span="6">
        <StatsCard label="本月销售额" :value="stats.monthlySales" icon="Money" icon-bg="#e6a23c" />
      </el-col>
      <el-col :span="6">
        <StatsCard label="今日机动余量" :value="stats.todayQuotaRemaining" icon="Warning" icon-bg="#f56c6c" />
      </el-col>
    </el-row>

    <!-- 图表区域 -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="12">
        <div class="chart-card">
          <h3 class="chart-title">订单趋势</h3>
          <div ref="trendChartRef" class="chart-container"></div>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="chart-card">
          <h3 class="chart-title">奶品品类占比</h3>
          <div ref="categoryChartRef" class="chart-container"></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="chart-row">
      <el-col :span="12">
        <div class="chart-card">
          <h3 class="chart-title">班级订购排行榜</h3>
          <div ref="rankingChartRef" class="chart-container"></div>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="chart-card">
          <h3 class="chart-title">学生喝奶覆盖率</h3>
          <div ref="coverageChartRef" class="chart-container"></div>
        </div>
      </el-col>
    </el-row>

    <!-- 营养摄入总览：由配送签收自动生成的摄入记录聚合而来（/stats/nutrition/dashboard） -->
    <el-row :gutter="20" class="chart-row">
      <el-col :span="24">
        <div class="chart-card">
          <h3 class="chart-title">营养摄入总览</h3>
          <el-row :gutter="16">
            <el-col v-for="item in nutritionItems" :key="item.label" :span="6">
              <div class="nutrition-item">
                <div class="nutrition-value">{{ item.value }}</div>
                <div class="nutrition-label">{{ item.label }}</div>
              </div>
            </el-col>
          </el-row>
          <div class="nutrition-hint">
            口径：签收即视为当日饮用，摄入量 = 奶品每 100ml 营养成分 × 实际饮用量(ml) ÷ 100；
            营养记录由配送签收自动生成，不可手工修改（共覆盖 {{ nutrition.days }} 天 / {{ nutrition.studentCount }} 名学生）。
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import StatsCard from '@/components/StatsCard.vue'
import {
  getDashboard, getOrderTrend, getOrderCategory,
  getClassRanking, getCoverage, getNutritionDashboard
} from '@/api/stats'

const router = useRouter()

const stats = reactive({
  totalOrders: 0,
  activeStudents: 0,
  monthlySales: '¥0',
  todayQuotaRemaining: 0,
  pendingSignCount: 0
})

const nutrition = reactive({
  totalEnergy: '0', totalProtein: '0', totalCalcium: '0',
  totalMl: 0, days: 0, studentCount: 0,
  avgDailyProtein: '0', avgDailyCalcium: '0'
})

/** 营养摄入总览指标（后端在无数据时也返回 0，前端不会出现 NaN） */
const nutritionItems = computed(() => [
  { label: '累计能量 (kJ)', value: nutrition.totalEnergy },
  { label: '累计蛋白质 (g)', value: nutrition.totalProtein },
  { label: '累计钙 (mg)', value: nutrition.totalCalcium },
  { label: '累计奶量 (ml)', value: nutrition.totalMl },
  { label: '覆盖天数', value: nutrition.days },
  { label: '覆盖学生数', value: nutrition.studentCount },
  { label: '日均每生蛋白质 (g)', value: nutrition.avgDailyProtein },
  { label: '日均每生钙 (mg)', value: nutrition.avgDailyCalcium }
])

const trendChartRef = ref<HTMLElement>()
const categoryChartRef = ref<HTMLElement>()
const rankingChartRef = ref<HTMLElement>()
const coverageChartRef = ref<HTMLElement>()

let trendChart: echarts.ECharts | null = null
let categoryChart: echarts.ECharts | null = null
let rankingChart: echarts.ECharts | null = null
let coverageChart: echarts.ECharts | null = null

onMounted(() => {
  initCharts()
  loadAllData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  categoryChart?.dispose()
  rankingChart?.dispose()
  coverageChart?.dispose()
})

function handleResize() {
  trendChart?.resize()
  categoryChart?.resize()
  rankingChart?.resize()
  coverageChart?.resize()
}

async function loadAllData() {
  await Promise.all([
    loadDashboard(),
    loadTrend(),
    loadCategory(),
    loadRanking(),
    loadCoverage(),
    loadNutrition()
  ])
}

async function loadDashboard() {
  const res: any = await getDashboard()
  const d = res.data
  stats.totalOrders = d.totalOrders || 0
  stats.activeStudents = d.activeStudents || 0
  stats.monthlySales = '¥' + Number(d.monthlySales || 0).toFixed(2)
  stats.todayQuotaRemaining = d.todayQuotaRemaining || 0
  stats.pendingSignCount = d.pendingSignCount || 0
}

/** 点击待签收提醒 → 配送管理-配送记录页（默认今日，一键签收） */
function goPendingSign() {
  router.push({ path: '/delivery', query: { tab: 'record' } })
}

async function loadTrend() {
  const res: any = await getOrderTrend({ type: 'day' })
  const d = res.data
  if (trendChart) {
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 50, right: 20, top: 30, bottom: 30 },
      xAxis: { type: 'category', data: d.dates || [], axisLabel: { fontSize: 11 } },
      yAxis: { type: 'value' },
      series: [{
        name: '订单数', data: d.orderCounts || [], type: 'line',
        smooth: true, areaStyle: { opacity: 0.15 },
        itemStyle: { color: '#409eff' }
      }]
    })
  }
}

async function loadCategory() {
  const res: any = await getOrderCategory()
  const data = res.data || []
  if (categoryChart) {
    categoryChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c}瓶 ({d}%)' },
      legend: { bottom: 0, textStyle: { fontSize: 12 } },
      series: [{
        type: 'pie',
        radius: ['40%', '65%'],
        center: ['50%', '45%'],
        label: { fontSize: 12 },
        data: data.length ? data : [{ value: 1, name: '暂无数据' }]
      }]
    })
  }
}

async function loadRanking() {
  const res: any = await getClassRanking(10)
  const list = res.data || []
  if (rankingChart) {
    rankingChart.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: 90, right: 30, top: 20, bottom: 30 },
      xAxis: { type: 'value' },
      yAxis: {
        type: 'category',
        data: list.map((i: any) => i.className).reverse(),
        axisLabel: { fontSize: 12 }
      },
      series: [{
        type: 'bar',
        data: list.map((i: any) => i.orderCount).reverse(),
        itemStyle: { color: '#67c23a', borderRadius: [0, 4, 4, 0] },
        barWidth: '50%'
      }]
    })
  }
}

async function loadCoverage() {
  const res: any = await getCoverage()
  const d = res.data
  const rate = d?.totalRate || 0
  if (coverageChart) {
    coverageChart.setOption({
      tooltip: { trigger: 'item' },
      series: [{
        type: 'gauge',
        progress: { show: true, width: 18 },
        axisLine: { lineStyle: { width: 18 } },
        axisTick: { show: false },
        splitLine: { length: 12, lineStyle: { width: 2 } },
        pointer: { width: 5 },
        detail: {
          valueAnimation: true,
          formatter: '{value}%',
          fontSize: 26,
          offsetCenter: [0, '70%']
        },
        data: [{ value: rate, name: '覆盖率' }],
        title: { fontSize: 13, offsetCenter: [0, '95%'] }
      }]
    })
  }
}

async function loadNutrition() {
  const res: any = await getNutritionDashboard()
  const d = res.data || {}
  nutrition.totalEnergy = Number(d.totalEnergy || 0).toFixed(0)
  nutrition.totalProtein = Number(d.totalProtein || 0).toFixed(1)
  nutrition.totalCalcium = Number(d.totalCalcium || 0).toFixed(0)
  nutrition.totalMl = Number(d.totalMl || 0)
  nutrition.days = Number(d.days || 0)
  nutrition.studentCount = Number(d.studentCount || 0)
  nutrition.avgDailyProtein = Number(d.avgDailyProtein || 0).toFixed(1)
  nutrition.avgDailyCalcium = Number(d.avgDailyCalcium || 0).toFixed(0)
}

function initCharts() {
  if (trendChartRef.value) trendChart = echarts.init(trendChartRef.value)
  if (categoryChartRef.value) categoryChart = echarts.init(categoryChartRef.value)
  if (rankingChartRef.value) rankingChart = echarts.init(rankingChartRef.value)
  if (coverageChartRef.value) coverageChart = echarts.init(coverageChartRef.value)
}
</script>

<style scoped lang="scss">
.dashboard {
  padding: 20px;
}

.stats-row {
  margin-bottom: 20px;
}

.pending-alert {
  margin-bottom: 20px;
  cursor: pointer;
}

.chart-row {
  margin-bottom: 20px;
}

.chart-card {
  background: #fff;
  padding: 20px;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.05);
}

.chart-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 16px;
}

.chart-container {
  width: 100%;
  height: 300px;
}

.nutrition-item {
  text-align: center;
  padding: 12px 8px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 12px;
}

.nutrition-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  line-height: 1.4;
}

.nutrition-label {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.nutrition-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
