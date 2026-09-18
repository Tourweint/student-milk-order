-- ============================================================
-- 状态机演示数据（毕设答辩专用）
-- 在 data.sql 执行完毕后再执行本脚本（可重复执行，开头会先清理旧数据）
-- 所有演示账号密码：123456
--
-- 本脚本预置 6 张订单，按 order_no 前缀区分演示点：
--   DE0001-PAY     待支付散订  → 现场点【支付成功】（ORDER/PAY 1→2，允许）
--   DE0002-CANCEL  待支付散订  → 现场点【取消】    （ORDER/CANCEL 1→5，允许）
--   DE0003-REFUND  已支付散订  → 现场点【退订】    （ORDER/CANCEL 2→5，允许；配额回补）
--   DE0004-FORBID  配送中散订  → 现场点【退订】    （ORDER/CANCEL 3→5，禁止+退款闸门）
--   DE0005-DONE    已完成套餐  → 现场重复退订/支付 （ORDER/CANCEL 4、PAY 4，禁止）
--   DE0006-TASK    配送中套餐  → 任务状态机全套（DISPATCH/SIGN/REJECT/STOCKOUT_CANCEL/已完成回退）
-- ============================================================

USE student_milk_order;

-- ============================================================
-- 0. 清理（按 order_no 前缀，可重复执行）
-- ============================================================
DELETE ni FROM nutrition_intake ni
INNER JOIN delivery_record dr ON ni.delivery_record_id = dr.id
INNER JOIN delivery_task t    ON dr.task_id = t.id
INNER JOIN order_info o       ON t.order_id = o.id
WHERE o.order_no LIKE 'DE00%';

DELETE dr FROM delivery_record dr
INNER JOIN delivery_task t ON dr.task_id = t.id
INNER JOIN order_info o     ON t.order_id = o.id
WHERE o.order_no LIKE 'DE00%';

DELETE t FROM delivery_task t
INNER JOIN order_info o ON t.order_id = o.id
WHERE o.order_no LIKE 'DE00%';

DELETE FROM order_item  WHERE order_id IN (SELECT id FROM (SELECT id FROM order_info WHERE order_no LIKE 'DE00%') x);
DELETE FROM payment_record WHERE order_no LIKE 'DE00%';
DELETE FROM daily_quota_usage WHERE order_id IN (SELECT id FROM (SELECT id FROM order_info WHERE order_no LIKE 'DE00%') x);
DELETE FROM order_info  WHERE order_no LIKE 'DE00%';

-- 还原演示配额（data.sql 已为明天 product_id=1 插过 30 盒，这里只改 used_quota）
UPDATE daily_quota SET used_quota = 0
WHERE quota_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY) AND product_id = 1;

DELETE FROM sys_user_role WHERE user_id IN (1001, 1002);
UPDATE student SET parent_id = NULL WHERE id IN (1, 2) AND parent_id IN (1001, 1002);
DELETE FROM sys_user WHERE id IN (1001, 1002);

-- ============================================================
-- 1. 演示家长账号（密码均为 123456）
-- ============================================================
INSERT INTO sys_user (id, username, password, real_name, phone, status, class_id, remark) VALUES
(1001, 'parent_zhang', '$2a$10$FQT4S46u.RHli474uHPJxOZX6FkxJA.OZLf8PeButQwAGW629nZXG', '张建国（张小明父）', '13900000001', 1, NULL, '状态机演示家长'),
(1002, 'parent_li',    '$2a$10$FQT4S46u.RHli474uHPJxOZX6FkxJA.OZLf8PeButQwAGW629nZXG', '李卫国（李小红父）', '13900000002', 1, NULL, '状态机演示家长');

INSERT INTO sys_user_role (user_id, role_id) VALUES (1001, 3), (1002, 3);

UPDATE student SET parent_id = 1001 WHERE id = 1;   -- 张小明
UPDATE student SET parent_id = 1002 WHERE id = 2;   -- 李小红

-- ============================================================
-- 2. 演示 A：待支付散订 → 现场点支付（ORDER/PAY from 1，allowed=1）
-- ============================================================
INSERT INTO order_info
  (order_no, student_id, user_id, class_id, package_id, order_type, status,
   total_amount, pay_amount, discount_amount,
   delivery_start_date, delivery_end_date, remark)
VALUES
  ('DE0001-PAY', 1, 1001, 1, NULL, 1, 1,
   3.50, NULL, 0,
   DATE_ADD(CURDATE(), INTERVAL 1 DAY), DATE_ADD(CURDATE(), INTERVAL 1 DAY),
   '【演示A】待支付散订：现场点支付 → 已支付');
SET @o1 = LAST_INSERT_ID();
INSERT INTO order_item (order_id, product_id, product_name, spec, price, quantity, subtotal)
VALUES (@o1, 1, '学生纯牛奶', '200ml/盒', 3.50, 1, 3.50);

-- ============================================================
-- 3. 演示 B：待支付散订 → 现场点取消（ORDER/CANCEL from 1，allowed=1）
-- ============================================================
INSERT INTO order_info
  (order_no, student_id, user_id, class_id, package_id, order_type, status,
   total_amount, pay_amount, discount_amount,
   delivery_start_date, delivery_end_date, remark)
VALUES
  ('DE0002-CANCEL', 2, 1002, 1, NULL, 1, 1,
   4.00, NULL, 0,
   DATE_ADD(CURDATE(), INTERVAL 1 DAY), DATE_ADD(CURDATE(), INTERVAL 1 DAY),
   '【演示B】待支付散订：现场点取消 → 已退订');
SET @o2 = LAST_INSERT_ID();
INSERT INTO order_item (order_id, product_id, product_name, spec, price, quantity, subtotal)
VALUES (@o2, 2, '学生酸奶', '200ml/盒', 4.00, 1, 4.00);

-- ============================================================
-- 4. 演示 C：已支付但未开始配送 → 现场退订成功（ORDER/CANCEL from 2，allowed=1）
--    散订占配额，退订时按 daily_quota_usage 回补原池
-- ============================================================
INSERT INTO order_info
  (order_no, student_id, user_id, class_id, package_id, order_type, status,
   total_amount, pay_amount, discount_amount,
   delivery_start_date, delivery_end_date, pay_time, pay_type, transaction_id, remark)
VALUES
  ('DE0003-REFUND', 1, 1001, 1, NULL, 1, 2,
   3.50, 3.50, 0,
   DATE_ADD(CURDATE(), INTERVAL 1 DAY), DATE_ADD(CURDATE(), INTERVAL 1 DAY),
   NOW(), 1, 'TXN-DE0003',
   '【演示C】已支付未配送：现场退订成功 + 配额回补');
SET @o3 = LAST_INSERT_ID();
INSERT INTO order_item (order_id, product_id, product_name, spec, price, quantity, subtotal)
VALUES (@o3, 1, '学生纯牛奶', '200ml/盒', 3.50, 1, 3.50);
INSERT INTO payment_record (order_id, order_no, transaction_id, amount, pay_type, status, pay_time, user_id, remark)
VALUES (@o3, 'DE0003-REFUND', 'TXN-DE0003', 3.50, 1, 2, NOW(), 1001, '演示支付成功');

-- 明天纯牛奶配额：data.sql 已为 product_id=1 建了 30 盒，这里把 used 调成 1（被本单占用）
-- 退订后 restore() 会按台账把 used_quota 减回 0
UPDATE daily_quota SET used_quota = 1
WHERE quota_date = DATE_ADD(CURDATE(), INTERVAL 1 DAY) AND product_id = 1;
INSERT INTO daily_quota_usage (order_id, product_id, quota_date, boxes)
VALUES (@o3, 1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1);

-- ============================================================
-- 5. 演示 D：配送中订单 → 现场退订被拒（ORDER/CANCEL from 3，allowed=0；
--    且 hasDispatchingTask 退款闸门再拦一道）
-- ============================================================
INSERT INTO order_info
  (order_no, student_id, user_id, class_id, package_id, order_type, status,
   total_amount, pay_amount, discount_amount,
   delivery_start_date, delivery_end_date, pay_time, pay_type, transaction_id, remark)
VALUES
  ('DE0004-FORBID', 2, 1002, 1, NULL, 1, 3,
   4.00, 4.00, 0,
   CURDATE(), CURDATE(), NOW(), 1, 'TXN-DE0004',
   '【演示D】配送中散订：现场退订应被拒绝（奶已送出）');
SET @o4 = LAST_INSERT_ID();
INSERT INTO order_item (order_id, product_id, product_name, spec, price, quantity, subtotal)
VALUES (@o4, 2, '学生酸奶', '200ml/盒', 4.00, 1, 4.00);
INSERT INTO payment_record (order_id, order_no, transaction_id, amount, pay_type, status, pay_time, user_id, remark)
VALUES (@o4, 'DE0004-FORBID', 'TXN-DE0004', 4.00, 1, 2, NOW(), 1002, '演示支付成功');

INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity,
   status, dispatch_by, dispatch_time, remark)
VALUES
  ('DTDE000401', CURDATE(), 1, @o4, 2, 2, 1,
   2, 'delivery', NOW(), '配送中任务（退订闸门）');
SET @t4 = LAST_INSERT_ID();
INSERT INTO delivery_record (task_id, student_id, product_id, quantity, sign_status, remark)
VALUES (@t4, 2, 2, 1, 2, '未签收');

-- ============================================================
-- 6. 演示 E：已完成学期套餐 → 现场重复退订/重复支付被拒
--    （ORDER/CANCEL from 4，allowed=0；ORDER/PAY from 4，allowed=0）
-- ============================================================
INSERT INTO order_info
  (order_no, student_id, user_id, class_id, package_id, order_type, status,
   total_amount, pay_amount, discount_amount,
   delivery_start_date, delivery_end_date, pay_time, pay_type, transaction_id, remark)
VALUES
  ('DE0005-DONE', 1, 1001, 1, 1, 2, 4,
   525.00, 480.00, 45.00,
   DATE_SUB(CURDATE(), INTERVAL 14 DAY), DATE_SUB(CURDATE(), INTERVAL 7 DAY),
   DATE_SUB(NOW(), INTERVAL 14 DAY), 1, 'TXN-DE0005',
   '【演示E】已完成订单：现场重复退订/重复支付均应被拒');
SET @o5 = LAST_INSERT_ID();
INSERT INTO order_item (order_id, product_id, product_name, spec, price, quantity, subtotal)
VALUES (@o5, 1, '学生纯牛奶', '200ml/盒', 3.50, 1, 3.50);
INSERT INTO payment_record (order_id, order_no, transaction_id, amount, pay_type, status, pay_time, user_id, remark)
VALUES (@o5, 'DE0005-DONE', 'TXN-DE0005', 480.00, 1, 2, DATE_SUB(NOW(), INTERVAL 14 DAY), 1001, '演示支付成功');

-- 3 条已完成任务 + 已签收记录 + 营养摄入（纯牛奶 200ml，按每 100ml 两倍折算）
INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity,
   status, dispatch_by, dispatch_time, remark)
VALUES
  ('DTDE000501', DATE_SUB(CURDATE(), INTERVAL 12 DAY), 1, @o5, 1, 1, 1, 3, 'delivery', DATE_SUB(NOW(), INTERVAL 12 DAY), '已完成');
SET @t5a = LAST_INSERT_ID();

INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity,
   status, dispatch_by, dispatch_time, remark)
VALUES
  ('DTDE000502', DATE_SUB(CURDATE(), INTERVAL 11 DAY), 1, @o5, 1, 1, 1, 3, 'delivery', DATE_SUB(NOW(), INTERVAL 11 DAY), '已完成');
SET @t5b = LAST_INSERT_ID();

INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity,
   status, dispatch_by, dispatch_time, remark)
VALUES
  ('DTDE000503', DATE_SUB(CURDATE(), INTERVAL 10 DAY), 1, @o5, 1, 1, 1, 3, 'delivery', DATE_SUB(NOW(), INTERVAL 10 DAY), '已完成');
SET @t5c = LAST_INSERT_ID();

INSERT INTO delivery_record (task_id, student_id, product_id, quantity, sign_status, sign_time, sign_person, remark)
VALUES (@t5a, 1, 1, 1, 1, DATE_SUB(NOW(), INTERVAL 12 DAY), 'teacher', '已签收');
INSERT INTO delivery_record (task_id, student_id, product_id, quantity, sign_status, sign_time, sign_person, remark)
VALUES (@t5b, 1, 1, 1, 1, DATE_SUB(NOW(), INTERVAL 11 DAY), 'teacher', '已签收');
INSERT INTO delivery_record (task_id, student_id, product_id, quantity, sign_status, sign_time, sign_person, remark)
VALUES (@t5c, 1, 1, 1, 1, DATE_SUB(NOW(), INTERVAL 10 DAY), 'teacher', '已签收');
SET @r5c = LAST_INSERT_ID();

INSERT INTO nutrition_intake (student_id, intake_date, product_id, quantity, delivery_record_id, energy, protein, fat, calcium)
VALUES
  (1, DATE_SUB(CURDATE(), INTERVAL 10 DAY), 1, 200, @r5c, 540.00, 6.40, 7.60, 208.00);

-- ============================================================
-- 7. 演示 F：配送中学期套餐 → 任务状态机全套
--    T01 明天 待配送  → DISPATCH（DELIVERY_TASK/DISPATCH from 1，allowed=1）
--    T02 今天 配送中  → SIGN     （DELIVERY_TASK/SIGN     from 2，allowed=1）
--    T03 今天 配送中  → REJECT   （DELIVERY_TASK/REJECT   from 2，allowed=1）
--    T04 昨天 已完成  → 重复签收 （DELIVERY_TASK/SIGN     from 3，allowed=0）
--    T05 明天 待配送  → STOCKOUT （DELIVERY_TASK/STOCKOUT_CANCEL from 1，allowed=1）
-- ============================================================
INSERT INTO order_info
  (order_no, student_id, user_id, class_id, package_id, order_type, status,
   total_amount, pay_amount, discount_amount,
   delivery_start_date, delivery_end_date, pay_time, pay_type, transaction_id, remark)
VALUES
  ('DE0006-TASK', 2, 1002, 1, 2, 2, 3,
   600.00, 560.00, 40.00,
   DATE_SUB(CURDATE(), INTERVAL 3 DAY), DATE_ADD(CURDATE(), INTERVAL 7 DAY),
   DATE_SUB(NOW(), INTERVAL 3 DAY), 1, 'TXN-DE0006',
   '【演示F】任务状态机全套：DISPATCH/SIGN/REJECT/STOCKOUT/已完成回退');
SET @o6 = LAST_INSERT_ID();
INSERT INTO order_item (order_id, product_id, product_name, spec, price, quantity, subtotal)
VALUES (@o6, 2, '学生酸奶', '200ml/盒', 4.00, 1, 4.00);
INSERT INTO payment_record (order_id, order_no, transaction_id, amount, pay_type, status, pay_time, user_id, remark)
VALUES (@o6, 'DE0006-TASK', 'TXN-DE0006', 560.00, 1, 2, DATE_SUB(NOW(), INTERVAL 3 DAY), 1002, '演示支付成功');

-- T01：明天，待配送
INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity, status, remark)
VALUES
  ('DTDE0006A', DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1, @o6, 2, 2, 1, 1, '【F1】待配送→开始配送');
SET @t6a = LAST_INSERT_ID();

-- T02：今天，配送中
INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity, status, dispatch_by, dispatch_time, remark)
VALUES
  ('DTDE0006B', CURDATE(), 1, @o6, 2, 2, 1, 2, 'delivery', NOW(), '【F2】配送中→签收');
SET @t6b = LAST_INSERT_ID();

-- T03：今天，配送中
INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity, status, dispatch_by, dispatch_time, remark)
VALUES
  ('DTDE0006C', CURDATE(), 1, @o6, 2, 2, 1, 2, 'delivery', NOW(), '【F3】配送中→拒收');
SET @t6c = LAST_INSERT_ID();

-- T04：昨天，已完成
INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity, status, dispatch_by, dispatch_time, remark)
VALUES
  ('DTDE0006D', DATE_SUB(CURDATE(), INTERVAL 1 DAY), 1, @o6, 2, 2, 1, 3, 'delivery', DATE_SUB(NOW(), INTERVAL 1 DAY), '【F4】已完成→重复签收被拒');
SET @t6d = LAST_INSERT_ID();

-- T05：明天，待配送
INSERT INTO delivery_task
  (task_no, delivery_date, class_id, order_id, student_id, product_id, quantity, status, remark)
VALUES
  ('DTDE0006E', DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1, @o6, 2, 2, 1, 1, '【F5】待配送→缺货单期取消');
SET @t6e = LAST_INSERT_ID();

-- 签收记录
INSERT INTO delivery_record (task_id, student_id, product_id, quantity, sign_status, remark)
VALUES
  (@t6a, 2, 2, 1, 2, '未签收'),
  (@t6b, 2, 2, 1, 2, '未签收'),
  (@t6c, 2, 2, 1, 2, '未签收');

INSERT INTO delivery_record (task_id, student_id, product_id, quantity, sign_status, sign_time, sign_person, remark)
VALUES
  (@t6d, 2, 2, 1, 1, DATE_SUB(NOW(), INTERVAL 1 DAY), 'teacher', '已签收');
SET @r6d = LAST_INSERT_ID();

INSERT INTO delivery_record (task_id, student_id, product_id, quantity, sign_status, remark)
VALUES
  (@t6e, 2, 2, 1, 2, '未签收');

-- T04 已签收 → 自动生成的营养摄入（学生酸奶 200ml，按每 100ml 两倍折算）
INSERT INTO nutrition_intake (student_id, intake_date, product_id, quantity, delivery_record_id, energy, protein, fat, calcium)
VALUES
  (2, DATE_SUB(CURDATE(), INTERVAL 1 DAY), 2, 200, @r6d, 640.00, 5.60, 6.00, 190.00);

-- ============================================================
-- 完成。核对用 SQL：
--   SELECT order_no, status FROM order_info WHERE order_no LIKE 'DE00%' ORDER BY order_no;
--   SELECT task_no, delivery_date, status FROM delivery_task WHERE order_id = @o6;
-- ============================================================
