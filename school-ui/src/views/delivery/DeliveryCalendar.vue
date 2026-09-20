<template>
  <div class="delivery-calendar">
    <!-- 开学提醒条：周末停送开启后提示先维护例外再重排 -->
    <el-alert
      :type="weekendStop ? 'success' : 'info'"
      :closable="false"
      show-icon
      class="tip"
      :title="weekendStop
        ? '周末停送已开启：请先维护本学期调休例外（停送日 / 补课日），再执行「日历重排」。'
        : '周末停送未开启：周末照常配送（与现状一致）。开启后，周末任务才会经日历重排并入工作日。'" />

    <!-- 周末停送开关 -->
    <el-card shadow="never" class="section">
      <div class="section-head">
        <span class="section-title">周末停送</span>
        <el-switch v-model="weekendStop" :loading="switchLoading" @change="toggleWeekendStop" />
      </div>
      <div class="section-desc">
        开启后：周六、周日不送奶，重排时把周末任务并入工作日（周六→周四、周日→周五，各提前 2 天，仍在保质期内）；
        系统参数键 <code>delivery.weekend.stop</code>，修改即刻生效。
      </div>
    </el-card>

    <!-- 调休例外维护 -->
    <el-card shadow="never" class="section">
      <div class="section-head">
        <span class="section-title">调休例外</span>
        <el-button type="primary" size="small" :icon="Plus" @click="openException()">新增例外</el-button>
      </div>
      <el-alert type="info" :closable="false" class="tip"
        title="系统不做官方节假日自动推算（各地调休不同、每年发布），例外表是唯一权威：停送日＝默认要送但不送（并入前一个工作日）；补课日＝默认不送但要送（周末调休上课，当天照常配送）。只允许维护今天及以后的日期。" />
      <div class="toolbar">
        <el-date-picker v-model="exDateRange" type="daterange" range-separator="至"
          start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
          class="filter-item date-range" />
        <el-button type="primary" @click="fetchExceptions">查询</el-button>
        <el-button @click="handleExceptionReset">重置</el-button>
      </div>

      <el-table v-loading="exLoading" :data="exceptions" stripe>
        <el-table-column prop="exceptionDate" label="例外日期" width="140" />
        <el-table-column label="星期" width="90">
          <template #default="{ row }">{{ weekdayText(row.exceptionDate) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <el-tag :type="row.type === 1 ? 'danger' : 'success'" size="small">{{ typeText(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openException(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDeleteException(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 一键日历重排 -->
    <el-card shadow="never" class="section">
      <div class="section-head">
        <span class="section-title">一键日历重排</span>
      </div>
      <el-alert type="warning" :closable="false" class="tip"
        title="顺序约束：先维护例外 → 再日历重排 → 最后学期末摊平。重排只改配送日与合并数量，不改订单金额、套餐盒数与 deliveryEndDate（盒数守恒）；可重复执行（幂等）。超单日 3 盒上限时自动向前找未满的工作日，找不到则跳过并提示。" />
      <div class="toolbar">
        <el-date-picker v-model="rebalanceRange" type="daterange" range-separator="至"
          start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
          class="filter-item date-range" />
        <el-button type="warning" :loading="rebalancing" :disabled="!weekendStop" @click="handleRebalance">
          执行日历重排
        </el-button>
      </div>

      <el-descriptions v-if="rebalanceResult" :column="5" border size="small" class="result">
        <el-descriptions-item label="待重排">{{ rebalanceResult.requested ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="已重排">{{ rebalanceResult.shifted ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="合并">{{ rebalanceResult.merged ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="新建">{{ rebalanceResult.created ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="跳过">{{ rebalanceResult.skipped ?? 0 }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="rebalanceResult && rebalanceResult.messages && rebalanceResult.messages.length" class="messages">
        <p v-for="(m, i) in rebalanceResult.messages" :key="i">{{ m }}</p>
      </div>
    </el-card>

    <!-- 例外新增/编辑对话框 -->
    <el-dialog v-model="exDialogVisible" :title="exForm.id ? '编辑例外' : '新增例外'" width="460px">
      <el-form label-width="90px">
        <el-form-item label="例外日期" required>
          <el-date-picker v-model="exForm.exceptionDate" type="date" placeholder="选择日期"
            value-format="YYYY-MM-DD" :disabled-date="disabledDate" class="full-width" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="exForm.type">
            <el-radio :label="1">停送（默认要送但不送）</el-radio>
            <el-radio :label="2">补课（默认不送但要送）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="exForm.remark" placeholder="选填，如：五一调休 / 6-13 补课" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="exSaving" @click="handleSaveException">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDeliveryExceptionList, saveDeliveryException, deleteDeliveryException, calendarRebalanceTasks
} from '@/api/delivery'
import { getSysConfigList, updateSysConfig } from '@/api/system'

const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const CONFIG_KEY_WEEKEND_STOP = 'delivery.weekend.stop'

function fmtDate(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const TODAY = fmtDate(new Date())
const DEFAULT_END = fmtDate(new Date(Date.now() + 120 * 24 * 3600 * 1000))

function weekdayText(date: string) {
  if (!date) return '—'
  const d = new Date(`${date}T00:00:00`)
  if (Number.isNaN(d.getTime())) return '—'
  return WEEKDAYS[(d.getDay() + 6) % 7]
}

function typeText(type: number) {
  return type === 1 ? '停送' : type === 2 ? '补课（补送）' : '未知'
}

function disabledDate(date: Date) {
  return date.getTime() < new Date(`${TODAY}T00:00:00`).getTime()
}

// ==================== 周末停送开关 ====================
const weekendStop = ref(false)
const weekendStopId = ref<number | null>(null)
const switchLoading = ref(false)

async function fetchConfig() {
  const res: any = await getSysConfigList()
  const item = (res.data || []).find((c: any) => c.configKey === CONFIG_KEY_WEEKEND_STOP)
  if (item) {
    weekendStopId.value = item.id
    weekendStop.value = String(item.configValue).toLowerCase() === 'true'
  }
}

async function toggleWeekendStop(val: any) {
  if (weekendStopId.value == null) {
    ElMessage.warning(`未找到系统参数 ${CONFIG_KEY_WEEKEND_STOP}，请检查数据库种子`)
    weekendStop.value = !val
    return
  }
  switchLoading.value = true
  try {
    await updateSysConfig({ id: weekendStopId.value, configValue: val ? 'true' : 'false' })
    ElMessage.success(val ? '已开启周末停送' : '已关闭周末停送')
  } catch (e) {
    weekendStop.value = !val
  } finally {
    switchLoading.value = false
  }
}

// ==================== 例外表维护 ====================
const exceptions = ref<any[]>([])
const exLoading = ref(false)
const exDateRange = ref<any>([TODAY, DEFAULT_END])
const exDialogVisible = ref(false)
const exSaving = ref(false)
const exForm = reactive({
  id: undefined as number | undefined,
  exceptionDate: '',
  type: 1,
  remark: ''
})

async function fetchExceptions() {
  exLoading.value = true
  try {
    const params: any = {}
    if (exDateRange.value && exDateRange.value.length === 2) {
      params.startDate = exDateRange.value[0]
      params.endDate = exDateRange.value[1]
    }
    const res: any = await getDeliveryExceptionList(params)
    exceptions.value = res.data || []
  } finally {
    exLoading.value = false
  }
}

function handleExceptionReset() {
  exDateRange.value = [TODAY, DEFAULT_END]
  fetchExceptions()
}

function openException(row?: any) {
  if (row) {
    exForm.id = row.id
    exForm.exceptionDate = row.exceptionDate
    exForm.type = row.type
    exForm.remark = row.remark || ''
  } else {
    exForm.id = undefined
    exForm.exceptionDate = ''
    exForm.type = 1
    exForm.remark = ''
  }
  exDialogVisible.value = true
}

async function handleSaveException() {
  if (!exForm.exceptionDate) {
    ElMessage.warning('请选择例外日期')
    return
  }
  exSaving.value = true
  try {
    await saveDeliveryException({
      id: exForm.id,
      exceptionDate: exForm.exceptionDate,
      type: exForm.type,
      remark: exForm.remark || undefined
    })
    ElMessage.success('已保存')
    exDialogVisible.value = false
    fetchExceptions()
  } finally {
    exSaving.value = false
  }
}

async function handleDeleteException(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除 ${row.exceptionDate} 的例外配置？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteDeliveryException(row.id)
  ElMessage.success('已删除')
  fetchExceptions()
}

// ==================== 一键日历重排 ====================
const rebalanceRange = ref<any>([TODAY, DEFAULT_END])
const rebalancing = ref(false)
const rebalanceResult = ref<any>(null)

async function handleRebalance() {
  if (!rebalanceRange.value || rebalanceRange.value.length !== 2) {
    ElMessage.warning('请选择要重排的日期范围')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将对 ${rebalanceRange.value[0]} 至 ${rebalanceRange.value[1]} 范围内落在周末/停送日的待配送任务执行日历重排，确认继续？`,
      '日历重排确认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  rebalancing.value = true
  try {
    const res: any = await calendarRebalanceTasks({
      startDate: rebalanceRange.value[0],
      endDate: rebalanceRange.value[1]
    })
    rebalanceResult.value = res.data || {}
    const d = rebalanceResult.value
    ElMessage.success(`重排完成：待重排 ${d.requested ?? 0}，已重排 ${d.shifted ?? 0}，跳过 ${d.skipped ?? 0}`)
  } finally {
    rebalancing.value = false
  }
}

onMounted(() => {
  fetchConfig()
  fetchExceptions()
})
</script>

<style scoped lang="scss">
.delivery-calendar {
  padding: 20px;
}
.section {
  margin-top: 16px;
  :deep(.el-card__body) {
    padding: 16px;
  }
}
.section-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
}
.section-desc {
  color: #909399;
  font-size: 12px;
  margin-bottom: 14px;
  line-height: 1.6;
  code {
    background: #f5f7fa;
    padding: 0 4px;
    border-radius: 3px;
  }
}
.toolbar {
  display: flex;
  gap: 10px;
  margin: 12px 0 16px;
  flex-wrap: wrap;
  align-items: center;
  .filter-item { width: 180px; }
  .date-range { width: 320px; }
}
.tip { margin-bottom: 8px; }
.full-width { width: 100%; }
.result { margin-top: 12px; }
.messages {
  margin-top: 10px;
  color: #e6a23c;
  font-size: 12px;
  line-height: 1.7;
  p { margin: 0; }
}
</style>
