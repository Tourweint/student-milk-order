<template>
  <div class="delivery-manage">
    <el-tabs v-model="activeTab" class="delivery-tabs">
      <el-tab-pane label="配送任务" name="task">
        <DeliveryTaskTab />
      </el-tab-pane>
      <el-tab-pane label="配送记录" name="record">
        <DeliveryRecordTab />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import DeliveryTaskTab from './components/DeliveryTaskTab.vue'
import DeliveryRecordTab from './components/DeliveryRecordTab.vue'

const activeTab = ref('task')
</script>

<style scoped lang="scss">
.delivery-manage {
  padding: 20px;
}
.delivery-tabs {
  :deep(.el-tab-pane) {
    padding-top: 8px;
  }
}
</style>
