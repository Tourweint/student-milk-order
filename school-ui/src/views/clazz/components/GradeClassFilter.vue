<template>
  <span class="grade-class-filter">
    <el-select
      v-model="gradeId" placeholder="全部年级" clearable
      class="grade-select" @change="onGradeChange"
    >
      <el-option v-for="g in gradeOptions" :key="g.id" :label="g.gradeName" :value="g.id" />
    </el-select>
    <el-select
      v-model="innerClassId" placeholder="全部班级" clearable filterable
      class="class-select" @change="onClassChange"
    >
      <el-option v-for="c in filteredClasses" :key="c.id" :label="classLabel(c)" :value="c.id" />
    </el-select>
  </span>
</template>

<script setup lang="ts">
/**
 * 年级 → 班级两栏筛选（班级列表下拉数据自带年级信息，直接派生年级选项，
 * 与后端数据权限天然一致：班主任只会看到本班对应的年级）
 */
import { ref, computed, watch, onMounted } from 'vue'
import { getAllClass } from '@/api/clazz'

const props = defineProps<{ modelValue?: number }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: number | undefined): void
  (e: 'change', v: number | undefined): void
}>()

const allClass = ref<any[]>([])
const gradeId = ref<number | undefined>(undefined)
const innerClassId = ref<number | undefined>(undefined)

const gradeOptions = computed(() => {
  const seen = new Map<number, string>()
  allClass.value.forEach((c) => {
    if (c.gradeId != null && !seen.has(c.gradeId)) seen.set(c.gradeId, c.gradeName || '未分年级')
  })
  return Array.from(seen, ([id, gradeName]) => ({ id, gradeName }))
})

const filteredClasses = computed(() =>
  gradeId.value == null
    ? allClass.value
    : allClass.value.filter((c) => c.gradeId === gradeId.value)
)

const classLabel = (c: any) => c.gradeName ? `${c.gradeName} · ${c.className}` : c.className

function onGradeChange() {
  // 年级变化后原班级不在可选范围内时清空；清空年级或班级仍属该年级时保留已选班级
  if (innerClassId.value != null) {
    const cls = allClass.value.find((c) => c.id === innerClassId.value)
    if (gradeId.value != null && cls?.gradeId !== gradeId.value) {
      innerClassId.value = undefined
      emit('update:modelValue', undefined)
      emit('change', undefined)
    }
  }
}

function onClassChange(v: number | undefined) {
  emit('update:modelValue', v)
  emit('change', v)
}

// 外部重置（classId 置空）时联动清空年级；外部回填时同步年级
watch(() => props.modelValue, (v) => {
  innerClassId.value = v
  if (v == null) {
    gradeId.value = undefined
  } else {
    const cls = allClass.value.find((c) => c.id === v)
    gradeId.value = cls?.gradeId
  }
})

onMounted(async () => {
  const res: any = await getAllClass()
  allClass.value = res.data || []
  if (props.modelValue != null) {
    innerClassId.value = props.modelValue
    gradeId.value = allClass.value.find((c) => c.id === props.modelValue)?.gradeId
  }
})
</script>

<style scoped lang="scss">
.grade-class-filter {
  display: inline-flex;
  gap: 8px;
  .grade-select { width: 120px; }
  .class-select { width: 160px; }
}
</style>
