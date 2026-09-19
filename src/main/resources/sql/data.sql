-- ============================================================
-- 学生奶订购系统 - 初始数据
-- 默认管理员账号：admin / 123456
-- ============================================================

USE student_milk_order;

-- ============================================================
-- 角色数据
-- ============================================================
INSERT INTO sys_role (id, role_code, role_name, description, sort, status) VALUES
(1, 'ADMIN', '系统管理员', '系统最高权限，可管理所有模块', 1, 1),
(2, 'TEACHER', '班主任', '管理本班学生、查看本班订购情况', 2, 1),
(3, 'PARENT', '学生/家长', '为孩子订奶、查看订单和营养统计', 3, 1),
(4, 'DELIVERY', '配送站', '执行每日配送：查看配送任务、点击今日已送出，签收情况只读', 4, 1);

-- ============================================================
-- 默认账号（密码均为：123456，BCrypt加密）
-- ============================================================
INSERT INTO sys_user (id, username, password, real_name, phone, status, class_id, remark) VALUES
(1, 'admin', '$2a$10$FQT4S46u.RHli474uHPJxOZX6FkxJA.OZLf8PeButQwAGW629nZXG', '系统管理员', '13800000000', 1, NULL, '默认管理员账号'),
(2, 'teacher', '$2a$10$FQT4S46u.RHli474uHPJxOZX6FkxJA.OZLf8PeButQwAGW629nZXG', '王老师', '13800000001', 1, 1, '演示班主任账号'),
(3, 'delivery', '$2a$10$FQT4S46u.RHli474uHPJxOZX6FkxJA.OZLf8PeButQwAGW629nZXG', '配送站', '13800000002', 1, NULL, '演示配送站账号');

-- 用户角色关联
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1), (2, 2), (3, 4);

-- ============================================================
-- 测试年级数据
-- ============================================================
INSERT INTO grade (id, grade_name, grade_code, sort) VALUES
(1, '一年级', 'G1', 1),
(2, '二年级', 'G2', 2),
(3, '三年级', 'G3', 3);

-- ============================================================
-- 测试班级数据（一年级1班班主任为王老师，user_id=2）
-- ============================================================
INSERT INTO class_info (id, class_name, grade_id, teacher_id, student_count, remark) VALUES
(1, '一年级1班', 1, 2, 2, '测试班级'),
(2, '一年级2班', 1, NULL, 2, '测试班级'),
(3, '二年级1班', 2, NULL, 2, '测试班级');

-- ============================================================
-- 测试学生数据
-- ============================================================
INSERT INTO student (id, student_no, student_name, gender, class_id, parent_name, parent_phone, remark) VALUES
(1, '20240101', '张小明', 1, 1, '张建国', '13800000001', '演示学生'),
(2, '20240102', '李小红', 0, 1, '李卫国', '13800000002', '演示学生'),
(3, '20240201', '王小刚', 1, 2, '王志强', '13800000003', '演示学生'),
(4, '20240202', '赵小美', 0, 2, '赵秀兰', '13800000004', '演示学生'),
(5, '20230101', '刘小伟', 1, 3, '刘德海', '13800000005', '演示学生'),
(6, '20230102', '陈小芳', 0, 3, '陈桂英', '13800000006', '演示学生');

-- ============================================================
-- 测试奶品品类数据
-- ============================================================
INSERT INTO product_category (id, category_name, category_code, sort, status) VALUES
(1, '纯牛奶', 'PURE_MILK', 1, 1),
(2, '酸奶', 'YOGURT', 2, 1),
(3, '高钙奶', 'HIGH_CALCIUM', 3, 1);

-- ============================================================
-- 测试奶品数据
-- ============================================================
INSERT INTO product (id, product_name, category_id, spec, flavor, price, cost_price, status, sort) VALUES
(1, '学生纯牛奶', 1, '200ml/盒', '原味', 3.50, 2.00, 1, 1),
(2, '学生酸奶', 2, '200ml/盒', '原味', 4.00, 2.50, 1, 2),
(3, '草莓酸奶', 2, '200ml/盒', '草莓', 4.50, 2.80, 1, 3),
(4, '高钙牛奶', 3, '250ml/盒', '原味', 4.00, 2.30, 1, 4);

-- ============================================================
-- 测试套餐数据
-- ============================================================
-- 仅学期套餐（全校统一预约定制）；月度套餐已下线
INSERT INTO meal_package (id, package_name, package_type, description, original_price, discount_price, status, sort) VALUES
(1, '纯牛奶学期套餐', 2, '每日一盒纯牛奶，按学期订购（约5个月），全校统一预约配送', 525.00, 480.00, 1, 1),
(2, '酸奶学期套餐', 2, '每日一盒酸奶，按学期订购（约5个月），全校统一预约配送', 600.00, 560.00, 1, 2),
(3, '混合学期套餐', 2, '纯牛奶+酸奶每日各一盒，按学期订购（约5个月）', 562.50, 525.00, 1, 3);

-- ============================================================
-- 测试每日机动配额数据（未来7天每日50盒，供单日零散订购）
-- ============================================================
INSERT INTO daily_quota (quota_date, product_id, total_quota, used_quota, remark) VALUES
(DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 2 DAY), 1, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 3 DAY), 1, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 4 DAY), 1, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 5 DAY), 1, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 6 DAY), 1, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 7 DAY), 1, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 1 DAY), 2, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 2 DAY), 2, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 3 DAY), 2, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 4 DAY), 2, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 5 DAY), 2, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 6 DAY), 2, 30, 0, '机动配额示例'),
(DATE_ADD(CURDATE(), INTERVAL 7 DAY), 2, 30, 0, '机动配额示例');

-- ============================================================
-- 系统参数配置（支付一致性 / 过程对账，管理端可在线修改）
-- ============================================================
INSERT INTO sys_config (config_key, config_value, description) VALUES
('order.pay.timeout.minutes', '15', '待支付订单超时自动取消阈值（分钟）；超时后先查单对账兜底再取消'),
('order.pay.reconcile.enabled', 'true', '支付对账补偿任务开关：对待支付订单主动查单，回调丢失时补偿落账'),
('order.process.reconcile.enabled', 'true', '过程聚合对账补偿任务开关：修复父订单状态与子任务集合不一致的漂移'),
('process.pending.enabled', 'true', '过程实时自愈通道开关：消费自愈待办，秒级驱动父过程聚合'),
('process.invariant.scan.enabled', 'true', '过程不变量体检开关：周期校验 6 类跨表不变量并自动修复可逆项');

-- ============================================================
-- 过程补偿规则种子（决策层）
-- 前两条 = 重构前的硬编码行为，默认启用，保证行为等价；
-- 第三条为“用配置表达新补偿”的示例，默认停用（需同时放开 state_transition_rule 的 ORDER/CANCEL/3）
-- ============================================================
INSERT INTO process_reconcile_rule
    (name, parent_scene, parent_status, child_condition, action, target_status, enabled, description) VALUES
('联动丢失补偿', 'ORDER', 2, 'HAS_DISPATCHING_TASK', 'DELIVER', 3, 1,
 '订单仍为已支付但已有子任务开始配送 → 补偿推进为配送中（原硬编码场景一）'),
('聚合丢失补偿', 'ORDER', 3, 'ALL_TASKS_TERMINAL', 'AUTO_COMPLETE', 4, 1,
 '订单仍为配送中但子任务已全部到达终态 → 补偿聚合为已完成（原硬编码场景二）'),
('已支付全终态补偿', 'ORDER', 2, 'ALL_TASKS_TERMINAL', 'AUTO_COMPLETE', 4, 1,
 '订单仍为已支付但子任务已全部到达终态 → 补偿聚合为已完成（3.4 补齐实验十一发现的覆盖盲区；'
 '依赖闸门 ORDER/AUTO_COMPLETE/2 放开。注意：子任务全部取消时也会补为已完成，'
 '「全部取消→已退订」的区分见默认停用的全取消补偿规则与退款设计）'),
('全取消补偿', 'ORDER', 3, 'ALL_TASKS_CANCELLED', 'CANCEL', 5, 0,
 '订单配送中但子任务已全部取消 → 补偿为已退订。启用前需同步放开 state_transition_rule 中 ORDER/CANCEL/3，'
 '否则统一出口会以“规则禁止”拒绝该补偿（配置化规则之间的依赖）');

-- ============================================================
-- 状态迁移规则种子（默认规则 = 现行硬编码行为；管理端可在线调整）
-- 场景：ORDER 订单（1待支付 2已支付 3配送中 4已完成 5已退订）
--      DELIVERY_TASK 配送任务（1待配送 2配送中 3已完成 4已取消）
-- ============================================================
INSERT INTO state_transition_rule (scene, action, from_status, allowed, description) VALUES
-- 订单
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
('ORDER', 'AUTO_COMPLETE', 2, 1, '已支付订单在子任务全部终态后允许自动完成（3.4 补齐：聚合出口只在全部任务终态时触发，不会跳过未完成的配送；「须先开始配送」的原约束由该定义保证）'),
('ORDER', 'COMPLETE', 3, 1, '配送中订单允许手动完成'),
('ORDER', 'COMPLETE', 2, 0, '已支付订单须先开始配送'),
('ORDER', 'COMPLETE', 4, 0, '已完成订单禁止重复完成'),
-- 配送任务
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
('DELIVERY_TASK', 'STOCKOUT_CANCEL', 3, 0, '已完成任务禁止缺货取消（禁止状态回退）');

-- ============================================================
-- 测试营养成分数据
-- ============================================================
INSERT INTO nutrition_info (id, product_id, energy, protein, fat, carbohydrate, calcium, sodium) VALUES
(1, 1, 270.00, 3.20, 3.80, 4.80, 104.00, 37.00),
(2, 2, 320.00, 2.80, 3.00, 10.50, 95.00, 40.00),
(3, 3, 340.00, 2.70, 2.90, 12.00, 90.00, 42.00),
(4, 4, 280.00, 3.50, 3.60, 5.00, 120.00, 38.00);
