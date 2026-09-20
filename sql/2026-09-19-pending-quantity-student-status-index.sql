-- ============================================================
-- 2026-09-19 家长端「剩余待配送盒数」查询索引  增量迁移脚本
-- 背景：GET /api/delivery/task/pending-quantity 按 student_id + status IN (1,2) 过滤，
--       delivery_task 原索引（uk_task_no / uk_order_product_date / idx_delivery_date /
--       idx_class_id / idx_status）均无法直接覆盖，并发打开首页时可能扫 idx_status 大集合。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：ADD INDEX 先查 information_schema.STATISTICS 再动态执行，可整体重复执行
-- ============================================================
USE student_milk_order;

SET @ddl_student_status := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE delivery_task ADD INDEX idx_student_status (student_id, status)',
    'SELECT ''idx_student_status 已存在，跳过''') AS ddl
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'delivery_task' AND INDEX_NAME = 'idx_student_status');
PREPARE stmt_student_status FROM @ddl_student_status;
EXECUTE stmt_student_status;
DEALLOCATE PREPARE stmt_student_status;