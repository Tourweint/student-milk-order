-- ============================================================
-- 2026-09-20 周末停送与调休例外（配送日历轻量版）  增量迁移脚本
-- 背景：见 docs/研发规范/项目开发规范.md §5.4.2、docs/基线文档/可靠性设计.md §十四
--       与 docs/变更记录/2026-09-20-周末停送与调休例外.md
--   1) 新增 delivery_exception 配送例外表（停送日/补课日，管理员手工维护，日期唯一）；
--   2) sys_config 增加开关 delivery.weekend.stop（默认 false，不改变现状行为）。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：建表用 IF NOT EXISTS、种子用 NOT EXISTS 守卫，可整体重复执行。
--         执行后实验库 student_milk_order_test 须同步（见 AGENTS.md 与 docs/实验/实验环境与运行说明.md §3）。
-- ============================================================
USE student_milk_order;

-- 1. 配送例外表
CREATE TABLE IF NOT EXISTS delivery_exception (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '例外ID',
    exception_date DATE NOT NULL COMMENT '例外日期',
    type TINYINT NOT NULL COMMENT '类型：1-停送（默认要送但不送），2-补送（默认不送但要送）',
    remark VARCHAR(255) COMMENT '备注（如"五一调休""6-13 补课"）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_exception_date (exception_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送例外表（停送日/补课日，管理员维护）';

-- 2. 周末停送开关（默认关闭＝与现状一致）
INSERT INTO sys_config (config_key, config_value, description)
SELECT 'delivery.weekend.stop', 'false',
       '周末停送开关：开启后周末任务经「配送日历重排」并入工作日（配合 delivery_exception 维护调休例外）；默认关闭＝不改动现状'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key = 'delivery.weekend.stop');
