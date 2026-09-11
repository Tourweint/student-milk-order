<template>
  <div class="search-form">
    <el-form :inline="true" :model="form" @submit.prevent>
      <slot />
      <el-form-item>
        <el-button type="primary" @click="handleSearch">
          <el-icon><Search /></el-icon>
          搜索
        </el-button>
        <el-button @click="handleReset">
          <el-icon><Refresh /></el-icon>
          重置
        </el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'

const props = defineProps<{
  initialValues?: Record<string, any>
}>()

const emit = defineEmits<{
  search: [values: Record<string, any>]
  reset: []
}>()

const form = reactive<Record<string, any>>(props.initialValues || {})

function handleSearch() {
  emit('search', { ...form })
}

function handleReset() {
  Object.keys(form).forEach(key => {
    form[key] = props.initialValues?.[key] ?? ''
  })
  emit('reset')
}
</script>

<style scoped lang="scss">
.search-form {
  margin-bottom: 16px;
}
</style>
