-- ============================================================
-- 2026-09-21 退款域（退款单 + 毕业清算）  增量迁移脚本
-- 背景：见 docs/设计方案/2026-09-21-退款与毕业清算-设计方案.md
--   1) order_info 增加 contract_total_boxes：合同总盒数快照（退款金额分母基准，R2）；
--      必须快照而非实时 SUM(delivery_task)：平移/重排作废行、拒收补送加量、期末摊平重写 quantity 都会漂移；
--   2) 新增 refund_order 退款单表（父过程）：生成列 active_order_id + 唯一键 uk_refund_active
--      严格表达「同一订单最多一张进行中退款单」（R6），冲突由 IdempotencyGuard 翻译为"已存在"；
--   3) state_transition_rule 补 REFUND 场景种子（AUDIT/REJECT/EXECUTE × 状态 1..5）。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
-- 新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：ADD COLUMN 先查 information_schema.COLUMNS；建表 IF NOT EXISTS；种子 INSERT IGNORE，
--         可整体重复执行。执行后实验库 student_milk_order_test 须同步重建（见 AGENTS.md）。
-- ============================================================
USE student_milk_order;

-- 1. order_info.contract_total_boxes
SET @ddl_contract_boxes := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE order_info ADD COLUMN contract_total_boxes INT NULL COMMENT ''合同总盒数（支付生成任务时快照，此后不变）：退款金额分母基准''',
    'SELECT ''order_info.contract_total_boxes 已存在，跳过''') AS ddl
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_info' AND COLUMN_NAME = 'contract_total_boxes');
PREPARE stmt_contract_boxes FROM @ddl_contract_boxes;
EXECUTE stmt_contract_boxes;
DEALLOCATE PREPARE stmt_contract_boxes;

-- 2. 历史订单回填：合同总盒数 = 每日明细盒数之和 × 配送天数
--    （与 INV_ORDER_TASK 的"品种 × 配送日"网格同源；补送/平移产生的增量不属于合同盒数，故从明细推导）
UPDATE order_info o
   SET o.contract_total_boxes = (
        SELECT SUM(i.quantity)
          FROM order_item i
         WHERE i.order_id = o.id AND i.deleted = 0
       ) * (DATEDIFF(o.delivery_end_date, o.delivery_start_date) + 1)
 WHERE o.deleted = 0
   AND o.contract_total_boxes IS NULL
   AND o.delivery_start_date IS NOT NULL
   AND o.delivery_end_date IS NOT NULL
   AND EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = o.id AND i.deleted = 0);

-- 3. 退款单（父过程）：一单一行；状态迁移经 state_transition_rule 的 REFUND 场景统一出口
CREATE TABLE IF NOT EXISTS refund_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '退款单ID',
    refund_no VARCHAR(50) NOT NULL COMMENT '退款单号（RF+时间戳+实例标识+序列，跨实例唯一）',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    order_no VARCHAR(50) COMMENT '订单编号（冗余，便于按单号检索）',
    student_id BIGINT COMMENT '学生ID',
    user_id BIGINT COMMENT '申请人用户ID',
    apply_box_count INT COMMENT '申请盒数（参考值；实际退款盒数以执行时刻按 R1 口径计算）',
    refunded_boxes INT NOT NULL DEFAULT 0 COMMENT '累计已退盒数（该订单截至本单的累计值，执行时回填）',
    refund_amount DECIMAL(10,2) COMMENT '退款金额（元，执行时回填）',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-待审核，2-已审核待退款，3-已退款，4-已拒绝，5-已取消',
    apply_reason VARCHAR(255) COMMENT '申请原因',
    audit_user_id BIGINT COMMENT '审核人用户ID',
    audit_time DATETIME COMMENT '审核时间',
    audit_remark VARCHAR(255) COMMENT '审核意见',
    refund_channel TINYINT COMMENT '退款通道：1-模拟微信',
    refund_time DATETIME COMMENT '退款完成时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    -- 生成列放在 status/deleted 之后（表达式引用它们）；NULL 不参与唯一键比较，
    -- 因此终态/逻辑删除后自动释放 uk_refund_active，不会出现"历史单占死唯一键"
    active_order_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN deleted = 0 AND status IN (1, 2) THEN order_id ELSE NULL END) STORED
        COMMENT '进行中退款单的订单ID（生成列，供 uk_refund_active 唯一约束）',
    UNIQUE KEY uk_refund_no (refund_no),
    UNIQUE KEY uk_refund_active (active_order_id),
    KEY idx_order_id (order_id),
    KEY idx_order_no (order_no),
    KEY idx_student_id (student_id),
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款单（退款域父过程）';

-- 4. 规则种子：退款单场景（每个动作显式配齐全部可达来源状态；INSERT IGNORE 依赖 uk_scene_action_from）
INSERT IGNORE INTO state_transition_rule (scene, action, from_status, allowed, description) VALUES
('REFUND', 'AUDIT', 1, 1, '待审核退款单允许审核通过'),
('REFUND', 'REJECT', 1, 1, '待审核退款单允许拒绝'),
('REFUND', 'EXECUTE', 1, 0, '未审核退款单禁止直接执行（须先审核）'),
('REFUND', 'AUDIT', 2, 0, '已审核退款单禁止重复审核'),
('REFUND', 'REJECT', 2, 0, '已审核退款单禁止再拒绝'),
('REFUND', 'EXECUTE', 2, 1, '已审核退款单允许执行退款'),
('REFUND', 'AUDIT', 3, 0, '已退款禁止审核'),
('REFUND', 'REJECT', 3, 0, '已退款禁止拒绝'),
('REFUND', 'EXECUTE', 3, 0, '已退款禁止重复执行（重复回调/重复点击由本闸门挡住）'),
('REFUND', 'AUDIT', 4, 0, '已拒绝退款单禁止审核'),
('REFUND', 'REJECT', 4, 0, '已拒绝退款单禁止再次拒绝（可重新申请新单）'),
('REFUND', 'EXECUTE', 4, 0, '已拒绝退款单禁止执行'),
('REFUND', 'AUDIT', 5, 0, '已取消退款单禁止审核'),
('REFUND', 'REJECT', 5, 0, '已取消退款单禁止拒绝'),
('REFUND', 'EXECUTE', 5, 0, '已取消退款单禁止执行');
