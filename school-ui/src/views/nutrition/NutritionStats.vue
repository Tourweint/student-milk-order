<template>
  <div class="nutrition-manage">
    <el-tabs v-model="activeTab" class="nutrition-tabs">
      <el-tab-pane label="营养成分管理" name="info">
        <NutritionInfoTab />
      </el-tab-pane>
      <el-tab-pane label="摄入统计" name="intake">
        <NutritionIntakeTab />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import NutritionInfoTab from './components/NutritionInfoTab.vue'
import NutritionIntakeTab from './components/NutritionIntakeTab.vue'

const activeTab = ref('info')
</script>

<style scoped lang="scss">
.nutrition-manage {
  padding: 20px;
}
.nutrition-tabs {
  :deep(.el-tab-pane) {
    padding-top: 8px;
  }
}
</style>
