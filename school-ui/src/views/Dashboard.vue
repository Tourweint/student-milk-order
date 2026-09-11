<template>
  <div class="dashboard">
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
        <StatsCard label="库存预警" :value="stats.warningCount" icon="Warning" icon-bg="#f56c6c" />
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
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import StatsCard from '@/components/StatsCard.vue'
import {
  getDashboard, getOrderTrend, getOrderCategory,
  getClassRanking, getCoverage
} from '@/api/stats'

const stats = reactive({
  totalOrders: 0,
  activeStudents: 0,
  monthlySales: '¥0',
  warningCount: 0
})

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
    loadCoverage()
  ])
}

async function loadDashboard() {
  const res: any = await getDashboard()
  const d = res.data
  stats.totalOrders = d.totalOrders || 0
  stats.activeStudents = d.activeStudents || 0
  stats.monthlySales = '¥' + Number(d.monthlySales || 0).toFixed(2)
  stats.warningCount = d.warningCount || 0
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
</style>
