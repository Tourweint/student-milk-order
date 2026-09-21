-- ============================================================
-- 2026-09-21 过敏/禁忌软警示  增量迁移脚本
-- 背景：见 docs/变更记录/2026-09-21-过敏与禁忌软警示.md
--   1) student 增加 allergy_tags：学生的过敏/禁忌标签；
--   2) product 增加 allergen_tags：奶品的过敏原标签。
-- 语义：两者**共用同一套受控编码**（AllergenType：LACTOSE/NUTS/PEANUT/SOY/FLAVORING/EGG/OTHER），
--   下单前预检取二者交集，命中即**软警示**（只提示、不拦截，提示优于拦截）。
--   标签不参与计价、扣减、状态流转与任何不变量判定；编码写入时由应用层规范化并拒绝未知编码。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：ADD COLUMN 先查 information_schema.COLUMNS，可整体重复执行。
--         执行后实验库 student_milk_order_test 须同步重建（见 AGENTS.md 与
--         docs/实验/实验环境与运行说明.md §3）。
-- ============================================================
USE student_milk_order;

-- 1. student.allergy_tags
SET @ddl_student_allergy := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE student ADD COLUMN allergy_tags VARCHAR(255) NULL COMMENT ''过敏/禁忌标签（逗号分隔的受控编码，与 product.allergen_tags 同一套编码，供下单前软警示比对）''',
    'SELECT ''student.allergy_tags 已存在，跳过''') AS ddl
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'student' AND COLUMN_NAME = 'allergy_tags');
PREPARE stmt_student_allergy FROM @ddl_student_allergy;
EXECUTE stmt_student_allergy;
DEALLOCATE PREPARE stmt_student_allergy;

-- 2. product.allergen_tags
SET @ddl_product_allergen := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE product ADD COLUMN allergen_tags VARCHAR(255) NULL COMMENT ''过敏原标签（逗号分隔的受控编码，与学生 allergy_tags 同一套编码，仅供下单前软警示）''',
    'SELECT ''product.allergen_tags 已存在，跳过''') AS ddl
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'allergen_tags');
PREPARE stmt_product_allergen FROM @ddl_product_allergen;
EXECUTE stmt_product_allergen;
DEALLOCATE PREPARE stmt_product_allergen;
