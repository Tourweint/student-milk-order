-- ============================================================
-- 2026-09-21 奶站「当日未送达申报」  增量迁移脚本
-- 背景：见 docs/变更记录/2026-09-21-奶站当日未送达申报.md
--   新增 delivery_undelivered_report 表：奶站申报某任务「已点已送出、物理上没送到」，
--   该任务随即被排除出 DeliveryAutoSignJob 的自动签收候选集。
-- 语义边界（重要）：只做标记与待办，**不新增状态、不自动改任何状态**——
--   任务保持「配送中」，由人工经既有出口处置（签收/拒收/取消），处置后自然离开「配送中」。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：建表 IF NOT EXISTS，可整体重复执行。
--         执行后实验库 student_milk_order_test 须同步重建（见 AGENTS.md 与
--         docs/实验/实验环境与运行说明.md §3）。
-- ============================================================
USE student_milk_order;

CREATE TABLE IF NOT EXISTS delivery_undelivered_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '申报ID',
    task_id BIGINT NOT NULL COMMENT '配送任务ID（一条任务只允许申报一次）',
    order_id BIGINT NOT NULL COMMENT '订单ID（冗余，便于列表与统计）',
    product_id BIGINT NOT NULL COMMENT '奶品ID（冗余）',
    class_id BIGINT NOT NULL COMMENT '班级ID（冗余，与 delivery_task 一致，用于班主任数据范围过滤）',
    delivery_date DATE NOT NULL COMMENT '配送日期（冗余）',
    reason VARCHAR(255) NOT NULL COMMENT '未送达原因（如车辆故障/道路中断/未备齐）',
    report_by VARCHAR(50) COMMENT '申报人（用户名，审计痕迹）',
    handle_status TINYINT NOT NULL DEFAULT 0 COMMENT '跟进状态：0-待跟进，1-已跟进',
    handle_remark VARCHAR(255) COMMENT '跟进说明（人工处置结果）',
    handle_by VARCHAR(50) COMMENT '跟进人',
    handle_time DATETIME COMMENT '跟进时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_task (task_id),
    KEY idx_date_status (delivery_date, handle_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='当日未送达申报（排除出自动签收候选集，人工跟进）';
