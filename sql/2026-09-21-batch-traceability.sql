-- ============================================================
-- 2026-09-21 批次追溯钩子  增量迁移脚本
-- 背景：见 docs/论文/后续扩展方案.md §4.7、
--       docs/设计方案/2026-09-21-保质期语义校正与批次追溯-设计方案.md、
--       docs/变更记录/2026-09-21-批次追溯钩子.md
--   1) 新增 product_batch 奶品批次表（批号唯一，管理端维护）；
--   2) daily_quota 增加可选列 batch_no：设置配额池时可标注当日到货批次。
-- 语义边界（重要）：批次只是「配额池上的可选标注」——不参与扣减、结转、台账与任何状态流转，
--   备货仍按计划进行；它的唯一用途是「批号 → 池子 → 台账 → 订单/任务/学生」的召回反查。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：建表 IF NOT EXISTS；ADD COLUMN 先查 information_schema.COLUMNS，可整体重复执行。
--         执行后实验库 student_milk_order_test 须同步重建（见 AGENTS.md 与
--         docs/实验/实验环境与运行说明.md §3）。
-- ============================================================
USE student_milk_order;

-- 1. 奶品批次表
CREATE TABLE IF NOT EXISTS product_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '批次ID',
    batch_no VARCHAR(64) NOT NULL COMMENT '批号（奶站/工厂批号，召回反查的业务键）',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    production_date DATE COMMENT '生产日期',
    arrival_date DATE COMMENT '到货日期',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-正常，2-召回中，3-已停用',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_product_id (product_id),
    KEY idx_arrival_date (arrival_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='奶品批次表（批次追溯钩子，不参与业务流转）';

-- 2. daily_quota.batch_no（可选标注）
SET @ddl_quota_batch_no := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE daily_quota ADD COLUMN batch_no VARCHAR(64) NULL COMMENT ''可选：当日该品种到货批次号（批次追溯钩子，仅作标注，不参与扣减/结转/台账口径）''',
    'SELECT ''daily_quota.batch_no 已存在，跳过''') AS ddl
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_quota' AND COLUMN_NAME = 'batch_no');
PREPARE stmt_quota_batch_no FROM @ddl_quota_batch_no;
EXECUTE stmt_quota_batch_no;
DEALLOCATE PREPARE stmt_quota_batch_no;
