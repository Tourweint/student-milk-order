<template>
  <el-dialog
    v-model="visible" title="创建订单" width="640px"
    :close-on-click-modal="false" @closed="resetForm"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="学生" prop="studentId">
        <el-select v-model="form.studentId" placeholder="请选择学生" filterable class="full-width">
          <el-option-group v-for="g in groupedStudents" :key="g.className" :label="g.className">
            <el-option v-for="s in g.students" :key="s.id" :label="`${s.studentName}（${s.studentNo}）`" :value="s.id" />
          </el-option-group>
        </el-select>
      </el-form-item>

      <el-form-item label="套餐">
        <el-select v-model="form.packageId" placeholder="可选，不选则按明细计价" clearable class="full-width">
          <el-option v-for="p in packageList" :key="p.id"
            :label="`${p.packageName}（¥${p.discountPrice}）`" :value="p.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="配送日期" prop="deliveryStartDate">
        <el-date-picker
          v-model="deliveryRange" type="daterange" range-separator="至"
          start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
          :disabled-date="disabledDate"
          class="full-width"
        />
      </el-form-item>

      <el-form-item label="订单明细" required>
        <div class="items-table">
          <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
            <el-select v-model="item.productId" placeholder="奶品" filterable class="product-select">
              <el-option v-for="p in productList" :key="p.id"
                :label="`${p.productName} ${p.spec || ''}（¥${p.price}）`" :value="p.id" />
            </el-select>
            <el-input-number v-model="item.quantity" :min="1" :max="99" controls-position="right" class="qty-input" />
            <el-button link type="danger" :icon="Delete" :disabled="form.items.length <= 1" @click="removeItem(idx)" />
          </div>
          <el-button type="primary" plain :icon="Plus" size="small" @click="addItem">添加奶品</el-button>
        </div>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">创建订单</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { Plus, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createOrder, checkAllergy } from '@/api/order'
import { getStudentList } from '@/api/student'
import { getProductList, getPackageList } from '@/api/product'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'success'): void
}>()

const visible = ref(props.visible)
watch(() => props.visible, (v) => { visible.value = v })
watch(visible, (v) => { emit('update:visible', v) })

const formRef = ref<FormInstance>()
const submitting = ref(false)
const studentList = ref<any[]>([])
const packageList = ref<any[]>([])
const productList = ref<any[]>([])
const deliveryRange = ref<[string, string] | null>(null)

const form = reactive({
  studentId: undefined as number | undefined,
  packageId: undefined as number | undefined,
  deliveryStartDate: undefined as string | undefined,
  deliveryEndDate: undefined as string | undefined,
  items: [{ productId: undefined as number | undefined, quantity: 1 }],
  remark: ''
})

/** 学生按班级分组展示，长列表下更容易定位 */
const groupedStudents = computed(() => {
  const groups: { className: string; students: any[] }[] = []
  studentList.value.forEach((s) => {
    const className = s.className || '未分班'
    let g = groups.find((x) => x.className === className)
    if (!g) {
      g = { className, students: [] }
      groups.push(g)
    }
    g.students.push(s)
  })
  return groups
})

const rules: FormRules = {
  studentId: [{ required: true, message: '请选择学生', trigger: 'change' }],
  deliveryStartDate: [{
    required: true,
    validator: (_r, _v, cb) => {
      if (!deliveryRange.value || deliveryRange.value.length < 2) {
        cb(new Error('请选择配送日期'))
      } else { cb() }
    },
    trigger: 'change'
  }]
}

/** 禁止选择今天之前的日期：过去日期会补造历史配送任务并被自动签收兜底签掉（服务端同样校验） */
function disabledDate(date: Date) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return date.getTime() < today.getTime()
}

/** 今天（YYYY-MM-DD），用于提交前的字符串比较校验 */
function todayStr() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function addItem() {
  form.items.push({ productId: undefined, quantity: 1 })
}
function removeItem(idx: number) {
  form.items.splice(idx, 1)
}

async function loadOptions() {
  const [sRes, pRes, pkgRes]: any[] = await Promise.all([
    getStudentList({ pageNum: 1, pageSize: 999 }),
    getProductList({ pageNum: 1, pageSize: 999 }),
    getPackageList()
  ])
  studentList.value = sRes.data.list || []
  productList.value = pRes.data.list || []
  packageList.value = pkgRes.data || []
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate()
  // 校验明细
  if (!form.items.length || form.items.some((i) => !i.productId)) {
    ElMessage.warning('请完善订单明细')
    return
  }
  if (!deliveryRange.value) {
    ElMessage.warning('请选择配送日期')
    return
  }
  if (deliveryRange.value[0] < todayStr()) {
    ElMessage.warning('配送开始日期不能早于今天')
    return
  }
  // 过敏/禁忌软警示（警示优于拦截）：提交前预检，命中则让用户确认；预检本身失败不阻断下单
  const productIds = form.items.map((i) => i.productId).filter((x): x is number => !!x)
  try {
    const res: any = await checkAllergy({ studentId: form.studentId!, productIds })
    const warnings: any[] = res?.data ?? []
    if (warnings.length) {
      await ElMessageBox.confirm(
        warnings.map((w) => w.message).join('\n') + '\n\n系统只做提示，是否仍要为该学生下单？',
        '过敏/禁忌提示',
        { confirmButtonText: '仍要下单', cancelButtonText: '返回修改', type: 'warning' }
      )
    }
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') {
      return // 用户在警示弹窗选择"返回修改"
    }
    // 预检本身异常（如网络/接口问题）不得阻断下单：软警示的定位是"提示"，不是闸门
    console.error('过敏预检失败（不影响下单）', e)
  }
  submitting.value = true
  try {
    await createOrder({
      studentId: form.studentId,
      packageId: form.packageId,
      deliveryStartDate: deliveryRange.value[0],
      deliveryEndDate: deliveryRange.value[1],
      items: form.items.map((i) => ({ productId: i.productId, quantity: i.quantity })),
      remark: form.remark
    })
    ElMessage.success('订单创建成功')
    emit('success')
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  formRef.value?.resetFields()
  form.studentId = undefined
  form.packageId = undefined
  form.items = [{ productId: undefined, quantity: 1 }]
  form.remark = ''
  deliveryRange.value = null
}

onMounted(loadOptions)
</script>

<style scoped lang="scss">
.full-width { width: 100%; }
.items-table { width: 100%; }
.item-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  .product-select { flex: 1; }
  .qty-input { width: 120px; }
}
</style>
