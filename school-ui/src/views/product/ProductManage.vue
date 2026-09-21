<template>
  <div class="product-manage">
    <el-tabs v-model="activeTab" class="main-tabs">
      <el-tab-pane label="奶品管理" name="product">
        <ProductTab />
      </el-tab-pane>
      <el-tab-pane label="品类管理" name="category">
        <CategoryTab />
      </el-tab-pane>
      <el-tab-pane label="套餐管理" name="package">
        <PackageTab />
      </el-tab-pane>
      <el-tab-pane label="每日机动配额" name="quota">
        <QuotaTab />
      </el-tab-pane>
      <el-tab-pane label="批次追溯" name="batch">
        <BatchTab />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import ProductTab from './components/ProductTab.vue'
import CategoryTab from './components/CategoryTab.vue'
import PackageTab from './components/PackageTab.vue'
import QuotaTab from './components/QuotaTab.vue'
import BatchTab from './components/BatchTab.vue'

const activeTab = ref('product')
</script>

<style scoped lang="scss">
.product-manage {
  padding: 20px;
}

.main-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 16px;
  }
}
</style>
