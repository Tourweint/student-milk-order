<template>
  <div class="page-container">
    <div v-if="title" class="page-header">
      <h2 class="page-title">{{ title }}</h2>
      <div v-if="$slots.extra" class="page-extra">
        <slot name="extra" />
      </div>
    </div>
    <div class="page-content">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title?: string
}>()
</script>

<style scoped lang="scss">
.page-container {
  padding: 20px;
  height: 100%;
  overflow: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin: 0;
}

.page-content {
  background: #fff;
  padding: 20px;
  border-radius: 4px;
  min-height: calc(100% - 60px);
}
</style>
