-- ============================================================
-- 2026-09-14 支付一致性 + 订阅边界 + 状态机可配置化  增量迁移脚本
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：建表 IF NOT EXISTS；种子数据用 INSERT IGNORE（依赖唯一键），可重复执行
-- ============================================================
USE student_milk_order;

-- 1. 续订计划：状态注释扩展 + 暂停审计列（0-已关闭，1-已开启，2-已暂停）
-- MODIFY COLUMN 本身可重复执行；ADD COLUMN 先查 information_schema 再动态执行，
-- 保证本脚本可整体重复执行（不会因列已存在而中断后续建表/种子）
ALTER TABLE subscription_plan
    MODIFY COLUMN status TINYINT DEFAULT 1 COMMENT '状态：0-已关闭，1-已开启，2-已暂停';

SET @ddl_pause_time := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE subscription_plan ADD COLUMN pause_time DATETIME COMMENT ''暂停时间'' AFTER reminder_sent',
    'SELECT ''pause_time 已存在，跳过''') AS ddl
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subscription_plan' AND COLUMN_NAME = 'pause_time');
PREPARE stmt_pause_time FROM @ddl_pause_time;
EXECUTE stmt_pause_time;
DEALLOCATE PREPARE stmt_pause_time;

SET @ddl_pause_reason := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE subscription_plan ADD COLUMN pause_reason VARCHAR(255) COMMENT ''暂停原因'' AFTER pause_time',
    'SELECT ''pause_reason 已存在，跳过''') AS ddl
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subscription_plan' AND COLUMN_NAME = 'pause_reason');
PREPARE stmt_pause_reason FROM @ddl_pause_reason;
EXECUTE stmt_pause_reason;
DEALLOCATE PREPARE stmt_pause_reason;

-- 2. 系统参数配置表
CREATE TABLE IF NOT EXISTS sys_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配置ID',
    config_key VARCHAR(100) NOT NULL COMMENT '配置键',
    config_value VARCHAR(500) NOT NULL COMMENT '配置值',
    description VARCHAR(255) COMMENT '配置说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数配置表';

-- 3. 状态迁移规则表
CREATE TABLE IF NOT EXISTS state_transition_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '规则ID',
    scene VARCHAR(30) NOT NULL COMMENT '状态机场景：ORDER-订单，DELIVERY_TASK-配送任务，SUBSCRIPTION_PLAN-续订计划',
    action VARCHAR(30) NOT NULL COMMENT '动作编码：PAY/CANCEL/DELIVER/AUTO_COMPLETE/COMPLETE/DISPATCH/TASK_CANCEL/SIGN/REJECT/STOCKOUT_CANCEL/RENEW/PAUSE/RESUME/CLOSE',
    from_status TINYINT NOT NULL COMMENT '来源状态码',
    allowed TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许迁移：1-允许，0-禁止',
    description VARCHAR(255) COMMENT '规则说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_scene_action_from (scene, action, from_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态迁移规则表（管理端可配置）';

-- 4. 系统参数种子
INSERT IGNORE INTO sys_config (config_key, config_value, description) VALUES
('order.pay.timeout.minutes', '15', '待支付订单超时自动取消阈值（分钟）；超时后先查单对账兜底再取消'),
('order.pay.reconcile.enabled', 'true', '支付对账补偿任务开关：对待支付订单主动查单，回调丢失时补偿落账');

-- 5. 状态迁移规则种子（默认规则 = 现行硬编码行为；管理端可在线调整）
INSERT IGNORE INTO state_transition_rule (scene, action, from_status, allowed, description) VALUES
-- 订单（1待支付 2已支付 3配送中 4已完成 5已退订）
('ORDER', 'PAY', 1, 1, '待支付订单允许支付成功（模拟支付/微信回调）'),
('ORDER', 'PAY', 2, 0, '已支付订单拒绝重复支付（重复回调由幂等分支应答）'),
('ORDER', 'PAY', 3, 0, '配送中订单拒绝支付回调'),
('ORDER', 'PAY', 4, 0, '已完成订单拒绝支付回调'),
('ORDER', 'PAY', 5, 0, '已取消订单拒绝支付回调，防止乱改状态'),
('ORDER', 'CANCEL', 1, 1, '待支付订单允许取消/退订'),
('ORDER', 'CANCEL', 2, 1, '已支付订单允许退订（存在配送中任务时另有退款闸门）'),
('ORDER', 'CANCEL', 3, 0, '配送中订单禁止退订（奶已送出）'),
('ORDER', 'CANCEL', 4, 0, '已完成订单禁止退订'),
('ORDER', 'CANCEL', 5, 0, '已取消订单禁止重复取消'),
('ORDER', 'DELIVER', 2, 1, '已支付订单允许进入配送中（今日已送出联动）'),
('ORDER', 'DELIVER', 3, 0, '配送中订单不可重复流转'),
('ORDER', 'DELIVER', 1, 0, '待支付订单不可开始配送'),
('ORDER', 'DELIVER', 4, 0, '已完成订单禁止回退'),
('ORDER', 'DELIVER', 5, 0, '已取消订单禁止回退'),
('ORDER', 'AUTO_COMPLETE', 3, 1, '配送中订单在任务全部终态后自动完成'),
('ORDER', 'AUTO_COMPLETE', 2, 0, '已支付订单未开始配送不自动完成'),
('ORDER', 'COMPLETE', 3, 1, '配送中订单允许手动完成'),
('ORDER', 'COMPLETE', 2, 0, '已支付订单须先开始配送'),
('ORDER', 'COMPLETE', 4, 0, '已完成订单禁止重复完成'),
-- 配送任务（1待配送 2配送中 3已完成 4已取消）
('DELIVERY_TASK', 'DISPATCH', 1, 1, '待配送任务允许开始配送（今日已送出）'),
('DELIVERY_TASK', 'DISPATCH', 2, 0, '配送中任务不可重复送出'),
('DELIVERY_TASK', 'DISPATCH', 3, 0, '已完成任务禁止回退'),
('DELIVERY_TASK', 'DISPATCH', 4, 0, '已取消任务禁止回退'),
('DELIVERY_TASK', 'TASK_CANCEL', 1, 1, '待配送任务允许取消（退订/暂停取消/终止取消）'),
('DELIVERY_TASK', 'TASK_CANCEL', 2, 1, '配送中任务允许取消（拒收联动）'),
('DELIVERY_TASK', 'TASK_CANCEL', 3, 0, '已完成任务禁止取消（禁止状态回退）'),
('DELIVERY_TASK', 'TASK_CANCEL', 4, 0, '已取消任务禁止重复取消'),
('DELIVERY_TASK', 'SIGN', 2, 1, '已送出任务允许签收完成'),
('DELIVERY_TASK', 'SIGN', 3, 0, '已完成任务禁止重复签收'),
('DELIVERY_TASK', 'REJECT', 2, 1, '已送出任务允许拒收'),
('DELIVERY_TASK', 'REJECT', 3, 0, '已完成任务禁止改为拒收（禁止状态回退）'),
('DELIVERY_TASK', 'STOCKOUT_CANCEL', 1, 1, '配送前缺货仅可取消待配送任务（单期子订单取消）'),
('DELIVERY_TASK', 'STOCKOUT_CANCEL', 2, 0, '配送中任务不可缺货自动取消'),
('DELIVERY_TASK', 'STOCKOUT_CANCEL', 3, 0, '已完成任务禁止缺货取消（禁止状态回退）'),
-- 续订计划（0已关闭 1已开启 2已暂停）
('SUBSCRIPTION_PLAN', 'RENEW', 1, 1, '已开启计划允许续订'),
('SUBSCRIPTION_PLAN', 'RENEW', 2, 0, '已暂停计划禁止续订（暂停期间不扣款）'),
('SUBSCRIPTION_PLAN', 'RENEW', 0, 0, '已关闭计划禁止续订'),
('SUBSCRIPTION_PLAN', 'PAUSE', 1, 1, '已开启计划允许暂停'),
('SUBSCRIPTION_PLAN', 'PAUSE', 2, 0, '已暂停计划禁止重复暂停'),
('SUBSCRIPTION_PLAN', 'PAUSE', 0, 0, '已关闭计划无需暂停'),
('SUBSCRIPTION_PLAN', 'RESUME', 2, 1, '已暂停计划允许恢复（续订时间顺延）'),
('SUBSCRIPTION_PLAN', 'RESUME', 1, 0, '已开启计划无需恢复'),
('SUBSCRIPTION_PLAN', 'RESUME', 0, 0, '已关闭计划不可恢复（需重新开启）'),
('SUBSCRIPTION_PLAN', 'CLOSE', 1, 1, '已开启计划允许关闭（终止订阅）'),
('SUBSCRIPTION_PLAN', 'CLOSE', 2, 1, '已暂停计划允许关闭（终止订阅）'),
('SUBSCRIPTION_PLAN', 'CLOSE', 0, 0, '已关闭计划禁止重复关闭');
