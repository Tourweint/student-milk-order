-- ============================================================
-- 2026-09-21 家长端「当日豁免」  增量迁移脚本
-- 背景：见 docs/变更记录/2026-09-21-家长端当日豁免.md
--   1) 新增 delivery_parent_exemption（学生 × 自然月一行的次数台账）；
--   2) sys_config 增加 delivery.parent.exemption.monthly-limit（默认 3）。
-- 语义：家长当天临时不要这份奶（病假/外出）→ 取消该学生**当天尚未送出**的待配送任务，
--   上限按学生×自然月计；取消走统一迁移出口留痕，配额按台账回补原池。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：建表 IF NOT EXISTS、种子用 NOT EXISTS 守卫，可整体重复执行。
--         执行后实验库 student_milk_order_test 须同步重建（见 AGENTS.md 与
--         docs/实验/实验环境与运行说明.md §3）。
-- ============================================================
USE student_milk_order;

-- 1. 豁免次数台账
CREATE TABLE IF NOT EXISTS delivery_parent_exemption (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    student_id BIGINT NOT NULL COMMENT '学生ID',
    exempt_month CHAR(7) NOT NULL COMMENT '自然月（YYYY-MM）',
    used_count INT NOT NULL DEFAULT 0 COMMENT '当月已用豁免次数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_student_month (student_id, exempt_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家长端当日豁免次数台账（学生×自然月）';

-- 2. 每月次数上限
INSERT INTO sys_config (config_key, config_value, description)
SELECT 'delivery.parent.exemption.monthly-limit', '3',
       '家长端「当日豁免」每月次数上限（按学生×自然月计数，落库于 delivery_parent_exemption）；仅当天尚未送出（待配送）的任务可豁免，配额按台账回补原池'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'delivery.parent.exemption.monthly-limit');
