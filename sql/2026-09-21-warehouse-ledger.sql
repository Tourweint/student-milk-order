-- ============================================================
-- 2026-09-21 仓库余量台账（供给侧）  增量迁移脚本
-- 背景：见 docs/设计方案/2026-09-21-仓库余量与出库边界-设计方案.md
--     （口径以该文 §11「评审修订」为准）、
--     docs/基线文档/业务边界与责任域.md §三 P1（上游边界从"轻登记"升级为"余量台账"）
--   1) 新增 warehouse_ledger：单表一条进出账（IN 到货 / OUT 送出 / IN_BACK 退回 / ADJ 修正 / INIT 期初），
--      W(品种) = ΣIN + ΣIN_BACK + ΣINIT + ΣADJ(带符号) − ΣOUT；
--   2) 余量是「机动配额发行」的物理上限（方案 R5′）：不登记到货则 W=0，配额设不了——
--      显式失败优于静默。
-- 语义边界（重要）：这不是库存/ERP。不做效期预警、先进先出、盘点、库位、采购订货。
--   台账只增不改（与 process_transition_log 同一原则）：记错走反向 ADJ 冲销，两条留痕。
-- 适用：已按旧版 schema.sql/data.sql 初始化的数据库
--   新库直接执行最新 schema.sql + data.sql 即可，无需本脚本
-- 幂等性：建表 IF NOT EXISTS，可整体重复执行；期初登记见 §2（唯一键保证重跑不重复）
--   执行后实验库 student_milk_order_test 须同步重建（见 AGENTS.md 与
--   docs/实验/实验环境与运行说明.md §3.4）。
-- ============================================================
USE student_milk_order;

-- ============================================================
-- 1. 建表
-- ============================================================
CREATE TABLE IF NOT EXISTS warehouse_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '台账ID',
    biz_type VARCHAR(12) NOT NULL COMMENT '账目类型：IN-到货(人工登记) / OUT-送出(自动,挂任务) / IN_BACK-退回(自动,拒收触发) / ADJ-修正(人工,带符号) / INIT-期初',
    biz_date DATE NOT NULL COMMENT '业务日期：IN=到货日；OUT=任务配送日期；IN_BACK=拒收日；ADJ/INIT=登记日',
    product_id BIGINT NOT NULL COMMENT '奶品ID（按品种独立记账，不混账）',
    quantity INT NOT NULL COMMENT '盒数：IN/OUT/IN_BACK/INIT 恒为正（方向由 biz_type 表达）；ADJ 带符号（正=增加余量，负=减少余量）',
    ref_type VARCHAR(30) COMMENT '关联对象类型：OUT=delivery_task / IN_BACK=delivery_record；其余为空',
    ref_id BIGINT COMMENT '关联对象ID（OUT/IN_BACK 的幂等业务键）',
    receipt_no VARCHAR(64) COMMENT '到货凭证号：IN 必填（首行 MAIN、第二车填送货单号）；INIT 固定 INIT；其余为空',
    batch_no VARCHAR(64) COMMENT '可选：到货批次号（批次追溯钩子的数据来源，不参与任何流转）',
    reason VARCHAR(255) COMMENT 'ADJ/IN_BACK 必填（修正原因/拒收原因）；IN 可填供应商与送货单号',
    operator VARCHAR(64) COMMENT '操作人（自动入账记 system）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    -- OUT / IN_BACK 的业务幂等键：一个任务只出一次库、一条签收记录只退回一次。
    -- 合并成一条键：biz_type 不同的两行 ref_id 空间互不干扰（任务ID 与记录ID 会撞号）；
    -- IN/ADJ/INIT 行的 ref_id 为 NULL，MySQL/MariaDB 唯一索引允许多个 NULL，故互不约束。
    UNIQUE KEY uk_biz_ref (biz_type, ref_id),
    -- IN / INIT 的幂等键：到货登记双击、迁移脚本重跑都必须只落一行。
    -- 分批到货（同日同品种第二车）用不同 receipt_no 合法新增，仍是"显式登记"而非"偷偷补行"。
    UNIQUE KEY uk_in_receipt (biz_type, biz_date, product_id, receipt_no),
    KEY idx_product_date (product_id, biz_date),
    KEY idx_biz_type_date (biz_type, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库余量台账（一条进出账：到货/送出/退回/修正/期初）';

-- ============================================================
-- 2. 期初登记（上线一次性动作，方案 R6；必须在开放设配额之前完成）
-- ============================================================
-- 为什么必须做：不登记到货则 W=0，管理员设任何机动配额都会被 R5′ 拒绝（显式失败优于静默）。
-- 唯一键 uk_in_receipt 含 receipt_no='INIT'，故本脚本可安全重跑（重复执行会撞键而非重复入账）。
--
-- 登记口径：
--   quantity  = 该品种「仓库现存实物盒数」+「已卖出但尚未送出（status=1 任务）的盒数」
--               （已售未送出的奶已经出账到任务上，不再算作可卖余量，但实物还在仓里）；
--   下界要求：每个品种 W ≥ Σ池剩余 + Σ待送出任务，见 §3 核查清单；
--   实际填数请先跑 §3 的核查查询，按结果填 INIT，再复跑一次核查确认全绿。
--
-- 模板（把 <盒数> 替换为实际清点数；也可以只登记有存量的品种，未登记的品种 W=0）：
-- INSERT INTO warehouse_ledger (biz_type, biz_date, product_id, quantity, receipt_no, reason, operator)
-- SELECT 'INIT', CURDATE(), p.id, <盒数>, 'INIT', '系统上线期初登记（清点日期见 reason）', 'system'
--   FROM product p WHERE p.deleted = 0 AND p.id = <品种ID>;
--
-- 便捷写法（先导出核查结果，逐行确认后再执行；未确认前请勿直接跑）：
-- INSERT INTO warehouse_ledger (biz_type, biz_date, product_id, quantity, receipt_no, reason, operator)
-- SELECT 'INIT', CURDATE(), id, 0, 'INIT', '系统上线期初登记（表内数量需人工改写）', 'system'
--   FROM product WHERE deleted = 0;
--
-- 登记后立即核对（应等于实物清点数）：
-- SELECT product_id, SUM(quantity) AS init_boxes FROM warehouse_ledger
--  WHERE biz_type = 'INIT' AND deleted = 0 GROUP BY product_id ORDER BY product_id;

-- ============================================================
-- 3. 上线前核查清单（只读查询，可随时重复执行）
-- ============================================================
-- 核查① 每品种当前余量 W（恒等式：IN + IN_BACK + INIT + ADJ(带符号) − OUT）
-- SELECT l.product_id, p.product_name,
--        SUM(CASE WHEN l.biz_type IN ('IN', 'IN_BACK', 'INIT') THEN l.quantity
--                 WHEN l.biz_type = 'OUT' THEN -l.quantity
--                 ELSE l.quantity END) AS w
--   FROM warehouse_ledger l
--   LEFT JOIN product p ON p.id = l.product_id
--  WHERE l.deleted = 0
--  GROUP BY l.product_id, p.product_name
--  ORDER BY l.product_id;
--
-- 核查② 逐（未来池日 D × 品种）判定 R5′ 是否成立：
--        需求 = Σ_{池: quota_date ∈ [D−2, D]} GREATEST(total−used, 0)
--             + Σ_{任务: status=1, delivery_date ≤ D} quantity  ≤  W
-- SELECT q.quota_date AS pool_date, q.product_id,
--        (SELECT COALESCE(SUM(GREATEST(q2.total_quota - COALESCE(q2.used_quota, 0), 0)), 0)
--           FROM daily_quota q2
--          WHERE q2.deleted = 0 AND q2.product_id = q.product_id
--            AND q2.quota_date BETWEEN DATE_SUB(q.quota_date, INTERVAL 2 DAY) AND q.quota_date) AS demand_quota,
--        (SELECT COALESCE(SUM(t.quantity), 0)
--           FROM delivery_task t
--          WHERE t.deleted = 0 AND t.status = 1 AND t.product_id = q.product_id
--            AND t.delivery_date <= q.quota_date) AS demand_task,
--        (SELECT COALESCE(SUM(CASE WHEN l.biz_type IN ('IN', 'IN_BACK', 'INIT') THEN l.quantity
--                                  WHEN l.biz_type = 'OUT' THEN -l.quantity
--                                  ELSE l.quantity END), 0)
--           FROM warehouse_ledger l
--          WHERE l.deleted = 0 AND l.product_id = q.product_id) AS w
--   FROM daily_quota q
--  WHERE q.deleted = 0 AND q.quota_date >= CURDATE()
--  GROUP BY q.quota_date, q.product_id
--  ORDER BY q.quota_date, q.product_id;
--   判定：w >= demand_quota + demand_task 即通过；不通过则该行配额设置会被拒绝/该品种需补 INIT。
--
-- 核查③ 台账守恒自检（每品种应无负余量）：承接核查①，任一行 w < 0 即为账错，
--        按「备份 → 反向 ADJ 冲销 → 复核查验」处置，不要直接改历史行（台账只增不改）。
