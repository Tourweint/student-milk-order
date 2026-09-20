-- ============================================================
-- 2026-09-20 配送日平移与拒收补送  增量迁移脚本
-- 背景：见 docs/变更记录/2026-09-20-配送日平移与拒收补送.md 与 docs/基线文档/可靠性设计.md §十三
--   1) delivery_record 增加拒收原因结构化的两列（真拒收写入，退订/缺货取消不写）；
--   2) 新增 delivery_compensation 补偿台账（一个被拒收任务只补一次，uk_source_task 仲裁）。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：ADD COLUMN 先查 information_schema.COLUMNS 再动态执行；建表用 IF NOT EXISTS，
--         可整体重复执行。执行后实验库 student_milk_order_test 须同步重建（见 AGENTS.md）。
-- ============================================================
USE student_milk_order;

-- 1. delivery_record.reject_reason_code
SET @ddl_reject_code := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE delivery_record ADD COLUMN reject_reason_code VARCHAR(32) NULL COMMENT ''拒收原因分类：DAMAGED/SOUR/WRONG_PRODUCT/SHORTAGE/OTHER（仅真拒收写入）''',
    'SELECT ''delivery_record.reject_reason_code 已存在，跳过''') AS ddl
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'delivery_record' AND COLUMN_NAME = 'reject_reason_code');
PREPARE stmt_reject_code FROM @ddl_reject_code;
EXECUTE stmt_reject_code;
DEALLOCATE PREPARE stmt_reject_code;

-- 2. delivery_record.reject_reason_detail
SET @ddl_reject_detail := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE delivery_record ADD COLUMN reject_reason_detail VARCHAR(255) NULL COMMENT ''拒收详细描述（班主任填写，可选）''',
    'SELECT ''delivery_record.reject_reason_detail 已存在，跳过''') AS ddl
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'delivery_record' AND COLUMN_NAME = 'reject_reason_detail');
PREPARE stmt_reject_detail FROM @ddl_reject_detail;
EXECUTE stmt_reject_detail;
DEALLOCATE PREPARE stmt_reject_detail;

-- 3. 补偿台账（一个被拒收任务只补一次）
CREATE TABLE IF NOT EXISTS delivery_compensation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '补偿台账ID',
    source_task_id BIGINT NOT NULL COMMENT '被拒收的原任务ID',
    target_task_id BIGINT NOT NULL COMMENT '补送落账的目标任务ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    boxes INT NOT NULL DEFAULT 1 COMMENT '补送盒数',
    compensation_date DATE NOT NULL COMMENT '补送目标日期（次日）',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_source_task (source_task_id),
    KEY idx_target_task (target_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拒收补送补偿台账';
