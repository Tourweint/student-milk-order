-- ============================================================
-- 学生奶订购系统 - 数据库建表脚本
-- 数据库：MySQL 8.0+
-- 字符集：utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS student_milk_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE student_milk_order;

-- ============================================================
-- 1. 用户与权限模块
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名（登录账号）',
    password VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密）',
    real_name VARCHAR(50) COMMENT '真实姓名',
    phone VARCHAR(20) COMMENT '手机号',
    email VARCHAR(100) COMMENT '邮箱',
    avatar VARCHAR(255) COMMENT '头像URL',
    status TINYINT DEFAULT 1 COMMENT '账号状态：0-禁用，1-正常',
    student_id BIGINT COMMENT '关联学生ID（家长账号）',
    class_id BIGINT COMMENT '关联班级ID（班主任账号）',
    openid VARCHAR(64) COMMENT '微信openid（家长微信授权登录绑定）',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_openid (openid),
    KEY idx_student_id (student_id),
    KEY idx_class_id (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '角色ID',
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码（ADMIN/TEACHER/PARENT）',
    role_name VARCHAR(50) NOT NULL COMMENT '角色名称',
    description VARCHAR(255) COMMENT '角色描述',
    sort INT DEFAULT 0 COMMENT '排序',
    status TINYINT DEFAULT 1 COMMENT '状态：0-禁用，1-正常',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- 用户-角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_id (user_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联表';

-- ============================================================
-- 2. 班级与学生管理模块
-- ============================================================

-- 年级表
CREATE TABLE IF NOT EXISTS grade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '年级ID',
    grade_name VARCHAR(50) NOT NULL COMMENT '年级名称',
    grade_code VARCHAR(20) COMMENT '年级编码',
    sort INT DEFAULT 0 COMMENT '排序',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='年级表';

-- 班级表
CREATE TABLE IF NOT EXISTS class_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '班级ID',
    class_name VARCHAR(50) NOT NULL COMMENT '班级名称',
    grade_id BIGINT NOT NULL COMMENT '年级ID',
    teacher_id BIGINT COMMENT '班主任用户ID',
    student_count INT DEFAULT 0 COMMENT '班级人数',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_grade_id (grade_id),
    KEY idx_teacher_id (teacher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级表';

-- 学生表
CREATE TABLE IF NOT EXISTS student (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '学生ID',
    student_no VARCHAR(50) NOT NULL COMMENT '学号',
    student_name VARCHAR(50) NOT NULL COMMENT '学生姓名',
    gender TINYINT COMMENT '性别：0-女，1-男',
    class_id BIGINT NOT NULL COMMENT '班级ID',
    parent_id BIGINT COMMENT '家长用户ID',
    parent_name VARCHAR(50) COMMENT '家长姓名',
    parent_phone VARCHAR(20) COMMENT '家长电话',
    birth_date DATE COMMENT '出生日期',
    allergy_tags VARCHAR(255) COMMENT '过敏/禁忌标签（逗号分隔的受控编码：LACTOSE/NUTS/PEANUT/SOY/FLAVORING/EGG/OTHER，与 product.allergen_tags 同一套编码，供下单前软警示比对）',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_student_no (student_no),
    KEY idx_class_id (class_id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生表';

-- ============================================================
-- 3. 奶品管理模块
-- ============================================================

-- 奶品品类表
CREATE TABLE IF NOT EXISTS product_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '品类ID',
    category_name VARCHAR(50) NOT NULL COMMENT '品类名称',
    category_code VARCHAR(20) COMMENT '品类编码',
    sort INT DEFAULT 0 COMMENT '排序',
    status TINYINT DEFAULT 1 COMMENT '状态：0-下架，1-上架',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='奶品品类表';

-- 奶品表
CREATE TABLE IF NOT EXISTS product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '奶品ID',
    product_name VARCHAR(100) NOT NULL COMMENT '奶品名称',
    category_id BIGINT NOT NULL COMMENT '品类ID',
    spec VARCHAR(50) COMMENT '规格（如200ml/盒）',
    flavor VARCHAR(50) COMMENT '口味',
    price DECIMAL(10,2) NOT NULL COMMENT '单价（元）',
    cost_price DECIMAL(10,2) COMMENT '成本价（元）',
    image VARCHAR(255) COMMENT '图片URL',
    description TEXT COMMENT '描述',
    status TINYINT DEFAULT 1 COMMENT '状态：0-下架，1-上架',
    sort INT DEFAULT 0 COMMENT '排序',
    nutrition_id BIGINT COMMENT '营养成分ID',
    allergen_tags VARCHAR(255) COMMENT '过敏原标签（逗号分隔的受控编码：LACTOSE/NUTS/PEANUT/SOY/FLAVORING/EGG/OTHER，与学生 allergy_tags 同一套编码，仅供下单前软警示，不参与计价与流转）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_category_id (category_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='奶品表';

-- 奶品批次表（批次追溯钩子：仅用于「这批奶送给了哪些孩子」的召回反查，不参与任何业务流转）
-- 建模判断见 docs/论文/后续扩展方案.md §4.7 与 docs/设计方案/2026-09-21-保质期语义校正与批次追溯-设计方案.md
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

-- 套餐表
CREATE TABLE IF NOT EXISTS meal_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '套餐ID',
    package_name VARCHAR(100) NOT NULL COMMENT '套餐名称',
    package_type TINYINT NOT NULL COMMENT '套餐类型：1-按月套餐，2-按学期套餐',
    description TEXT COMMENT '套餐描述',
    original_price DECIMAL(10,2) COMMENT '套餐原价（元）',
    discount_price DECIMAL(10,2) NOT NULL COMMENT '套餐优惠价（元）',
    start_date DATE COMMENT '开始日期',
    end_date DATE COMMENT '结束日期',
    status TINYINT DEFAULT 1 COMMENT '状态：0-下架，1-上架',
    sort INT DEFAULT 0 COMMENT '排序',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐表';

-- 套餐明细表
CREATE TABLE IF NOT EXISTS meal_package_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    package_id BIGINT NOT NULL COMMENT '套餐ID',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    quantity INT NOT NULL DEFAULT 1 COMMENT '数量（每日配送瓶数）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_package_id (package_id),
    KEY idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐明细表';

-- 每日机动配额表（单日零散订购用，按品种设置；学期套餐为统一预约定制，不占配额）
CREATE TABLE IF NOT EXISTS daily_quota (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配额ID',
    quota_date DATE NOT NULL COMMENT '配额日期',
    product_id BIGINT NOT NULL COMMENT '奶品ID（按品种设置）',
    total_quota INT NOT NULL COMMENT '当日该品种机动总盒数（管理员设置）',
    used_quota INT NOT NULL DEFAULT 0 COMMENT '当日该品种已售盒数',
    batch_no VARCHAR(64) COMMENT '可选：当日该品种到货批次号（批次追溯钩子，仅作标注，不参与扣减/结转/台账口径）',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_date_product (quota_date, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日机动配额表';

-- 每日机动配额扣减台账（按订单×品种×池子日期记录扣减盒数，供退订精确回补）
CREATE TABLE IF NOT EXISTS daily_quota_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '台账ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    quota_date DATE NOT NULL COMMENT '被扣减的池子日期',
    boxes INT NOT NULL COMMENT '扣减盒数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_order_product_pool (order_id, product_id, quota_date),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日机动配额扣减台账';

-- ============================================================
-- 4. 订单核心模块
-- ============================================================

-- 订单表
CREATE TABLE IF NOT EXISTS order_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID',
    order_no VARCHAR(50) NOT NULL COMMENT '订单编号',
    student_id BIGINT NOT NULL COMMENT '学生ID',
    user_id BIGINT NOT NULL COMMENT '家长用户ID（下单人）',
    class_id BIGINT NOT NULL COMMENT '班级ID',
    package_id BIGINT COMMENT '套餐ID',
    order_type TINYINT NOT NULL COMMENT '订单类型：1-单日零散订购，2-学期套餐订购',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '订单状态：1-待支付，2-已支付，3-配送中，4-已完成，5-已退订',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '订单总金额（元）',
    pay_amount DECIMAL(10,2) COMMENT '实付金额（元）',
    discount_amount DECIMAL(10,2) DEFAULT 0 COMMENT '优惠金额（元）',
    contract_total_boxes INT COMMENT '合同总盒数（支付生成任务时快照，此后不变）：退款金额分母基准，见设计方案 R2',
    delivery_start_date DATE COMMENT '配送开始日期',
    delivery_end_date DATE COMMENT '配送结束日期',
    pay_time DATETIME COMMENT '支付时间',
    pay_type TINYINT COMMENT '支付方式：1-模拟支付',
    transaction_id VARCHAR(100) COMMENT '支付流水号',
    cancel_time DATETIME COMMENT '退订时间',
    cancel_reason VARCHAR(255) COMMENT '退订原因',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_student_id (student_id),
    KEY idx_user_id (user_id),
    KEY idx_class_id (class_id),
    KEY idx_status (status),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 订单明细表
CREATE TABLE IF NOT EXISTS order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '明细ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    product_name VARCHAR(100) COMMENT '奶品名称（快照）',
    spec VARCHAR(50) COMMENT '规格（快照）',
    price DECIMAL(10,2) COMMENT '单价（元，快照）',
    quantity INT NOT NULL DEFAULT 1 COMMENT '数量（每日瓶数）',
    subtotal DECIMAL(10,2) COMMENT '小计金额（元）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_order_id (order_id),
    KEY idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 支付记录表
CREATE TABLE IF NOT EXISTS payment_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '支付记录ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    order_no VARCHAR(50) NOT NULL COMMENT '订单编号',
    transaction_id VARCHAR(100) COMMENT '支付流水号',
    amount DECIMAL(10,2) NOT NULL COMMENT '支付金额（元）',
    pay_type TINYINT DEFAULT 1 COMMENT '支付方式：1-模拟支付',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '支付状态：1-待支付，2-支付成功，3-支付失败',
    pay_time DATETIME COMMENT '支付时间',
    user_id BIGINT COMMENT '支付用户ID',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_order_id (order_id),
    KEY idx_transaction_id (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付记录表';

-- 模拟微信侧支付单（扮演「微信支付平台」这一外部角色的持久化存储）
-- 设计说明：模拟器原先把预支付单与已扣款单放在内存里，重启即失效，且是**多实例部署下唯一的本地状态**——
-- 用户在实例 A 预下单、确认扣款时请求落到实例 B，就会"预支付单不存在"；
-- 更糟的是实例 B 的对账任务查不到扣款记录，可能把已付款订单当作超时订单取消。
-- 改为持久化后：状态由数据库共享，任意实例都能确认扣款与查单，重启不丢。
CREATE TABLE IF NOT EXISTS wechat_pay_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '模拟微信侧支付单ID',
    prepay_id VARCHAR(64) NOT NULL COMMENT '预支付凭证（模拟微信签发）',
    out_trade_no VARCHAR(64) NOT NULL COMMENT '商户订单号',
    order_id BIGINT COMMENT '商户订单ID（便于排查）',
    amount DECIMAL(10,2) NOT NULL COMMENT '金额（元）',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1-已签发预支付（待用户确认扣款），2-已扣款，3-已作废（被新的预支付替换）',
    transaction_id VARCHAR(64) COMMENT '模拟微信支付流水号（扣款后生成）',
    pay_time DATETIME COMMENT '扣款时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_prepay_id (prepay_id),
    KEY idx_out_trade_no (out_trade_no),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟微信侧支付单（持久化，替代内存态以支持多实例）';

-- ============================================================
-- 5. 配送模块
-- ============================================================

-- 配送任务表
CREATE TABLE IF NOT EXISTS delivery_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '任务ID',
    task_no VARCHAR(50) NOT NULL COMMENT '任务编号',
    delivery_date DATE NOT NULL COMMENT '配送日期',
    class_id BIGINT NOT NULL COMMENT '班级ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    student_id BIGINT NOT NULL COMMENT '学生ID',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    quantity INT NOT NULL DEFAULT 1 COMMENT '配送数量',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '任务状态：1-待配送，2-配送中，3-已完成，4-已取消',
    delivery_person_id BIGINT COMMENT '配送人ID',
    dispatch_by VARCHAR(50) COMMENT '派送操作人（用户名，开始配送时记录）',
    dispatch_time DATETIME COMMENT '派送时间（开始配送时记录）',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_task_no (task_no),
    UNIQUE KEY uk_order_product_date (order_id, product_id, delivery_date),
    KEY idx_delivery_date (delivery_date),
    KEY idx_class_id (class_id),
    KEY idx_status (status),
    -- 家长端「剩余待配送盒数」按 student_id + status 过滤，避免扫 idx_status 大集合
    KEY idx_student_status (student_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送任务表';

-- 配送记录表
CREATE TABLE IF NOT EXISTS delivery_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    task_id BIGINT NOT NULL COMMENT '配送任务ID',
    student_id BIGINT NOT NULL COMMENT '学生ID',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    quantity INT NOT NULL DEFAULT 1 COMMENT '配送数量',
    sign_status TINYINT COMMENT '签收状态：1-已签收，2-未签收，3-拒收',
    sign_time DATETIME COMMENT '签收时间',
    sign_person VARCHAR(50) COMMENT '签收人',
    remark VARCHAR(255) COMMENT '备注',
    reject_reason_code VARCHAR(32) COMMENT '拒收原因分类：DAMAGED/SOUR/WRONG_PRODUCT/SHORTAGE/OTHER（仅真拒收写入，退订/缺货取消不写）',
    reject_reason_detail VARCHAR(255) COMMENT '拒收详细描述（班主任填写，可选）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_task_id (task_id),
    KEY idx_student_id (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送记录表';

-- 拒收补送补偿台账
-- 一个被拒收任务只允许产生一次补偿（uk_source_task 仲裁）；
-- target_task_id 指向补送落账的目标任务（套餐订单合并到次日任务 / 零散订单新建的任务），
-- 摊平算法据此还原「该任务基础量 = 1 + 补送量」。
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

-- 当日未送达申报表（奶站申报：任务已标记"已送出"，但物理上没有送到）
-- 解决的问题：自动签收兜底按「配送日期 < 今天 + 任务配送中 + 记录未签收」判定"超时未签收"，
--   把**信息态**（已点已送出）当成了**物理态**（奶已到校）。奶站实际没送到时，兜底会签出一条
--   虚假签收并生成营养摄入。本表把该任务**排除出自动签收候选集**。
-- 边界（刻意的）：只做标记与待办，**不新增状态、不自动改任何状态**——任务保持"配送中"，
--   由人工经既有出口处置（签收 / 拒收 / 取消），处置后任务自然离开"配送中"。
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

-- 家长端「当日豁免」次数台账（学生 × 自然月一行：计数是**资源**，必须行锁读 + 条件更新）
-- 语义：家长当天临时不要这份奶（病假/外出），取消该学生**当天尚未送出**的待配送任务。
-- 上限按学生×自然月计（sys_config `delivery.parent.exemption.monthly-limit`，默认 3 次），
-- 取消仍走统一迁移出口（留痕），配额按台账回补原池（与缺货取消同一口径）。
-- 为什么用"一行一个计数器"而不是"数明细行"：次数上限是并发敏感的资源，
-- COUNT(*) 判定在并发下会超限（与配额池同类的读-改-写问题）。
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

-- 配送例外表（周末停送与调休例外，管理员手动维护）
-- 只描述「与日历默认规则不同」的日期，不推算官方节假日（官方调休每年发布、各地不同）：
--   type=1 停送：默认要送但不送 → 该日任务并入前一个有效配送日；
--   type=2 补送/补课：默认不送但要送（周末调休上课）→ 该日保留任务、正常配送。
-- 日期唯一；配合 sys_config 的 delivery.weekend.stop 开关生效。
CREATE TABLE IF NOT EXISTS delivery_exception (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '例外ID',
    exception_date DATE NOT NULL COMMENT '例外日期',
    type TINYINT NOT NULL COMMENT '类型：1-停送（默认要送但不送），2-补送（默认不送但要送）',
    remark VARCHAR(255) COMMENT '备注（如"五一调休""6/13 补课"）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_exception_date (exception_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送例外表（停送日/补课日，管理员维护）';

-- ============================================================
-- 6. 营养统计模块
-- ============================================================

-- 营养成分表
CREATE TABLE IF NOT EXISTS nutrition_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '营养成分ID',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    energy DECIMAL(10,2) COMMENT '能量（千焦/100ml）',
    protein DECIMAL(10,2) COMMENT '蛋白质（克/100ml）',
    fat DECIMAL(10,2) COMMENT '脂肪（克/100ml）',
    carbohydrate DECIMAL(10,2) COMMENT '碳水化合物（克/100ml）',
    calcium DECIMAL(10,2) COMMENT '钙（毫克/100ml）',
    sodium DECIMAL(10,2) COMMENT '钠（毫克/100ml）',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='营养成分表';

-- 营养摄入记录表
CREATE TABLE IF NOT EXISTS nutrition_intake (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    student_id BIGINT NOT NULL COMMENT '学生ID',
    intake_date DATE NOT NULL COMMENT '摄入日期',
    product_id BIGINT NOT NULL COMMENT '奶品ID',
    quantity INT NOT NULL COMMENT '摄入数量（ml）',
    energy DECIMAL(10,2) COMMENT '能量摄入（千焦）',
    protein DECIMAL(10,2) COMMENT '蛋白质摄入（克）',
    fat DECIMAL(10,2) COMMENT '脂肪摄入（克）',
    calcium DECIMAL(10,2) COMMENT '钙摄入（毫克）',
    delivery_record_id BIGINT COMMENT '关联配送记录ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    -- 一个配送记录最多生成一条摄入记录：签收正常路径与体检补写路径都由数据库仲裁，避免重复补写
    UNIQUE KEY uk_delivery_record (delivery_record_id),
    KEY idx_student_id (student_id),
    KEY idx_intake_date (intake_date),
    KEY idx_product_id (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='营养摄入记录表';

-- ============================================================
-- 9. 系统管理模块
-- ============================================================

-- 系统参数配置表（业务侧带短缓存读取，管理端在线修改即刻生效）
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

-- 状态迁移规则表（管理端可在线配置：某场景某动作从某状态迁移是否允许；白名单语义，未配置默认禁止）
CREATE TABLE IF NOT EXISTS state_transition_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '规则ID',
    scene VARCHAR(30) NOT NULL COMMENT '状态机场景：ORDER-订单，DELIVERY_TASK-配送任务，REFUND-退款单（DELIVERY_RECORD 为任务下挂子状态机，未纳入本表）',
    action VARCHAR(30) NOT NULL COMMENT '动作编码：PAY/CANCEL/DELIVER/AUTO_COMPLETE/COMPLETE/DISPATCH/TASK_CANCEL/SIGN/REJECT/STOCKOUT_CANCEL/AUDIT/EXECUTE',
    from_status TINYINT NOT NULL COMMENT '来源状态码',
    allowed TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许迁移：1-允许，0-禁止',
    description VARCHAR(255) COMMENT '规则说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_scene_action_from (scene, action, from_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态迁移规则表（管理端可配置）';

-- 退款单（退款域父过程）：一单一行 —— 一张退款单即一条资金记录，不另建退款明细表。
-- 状态迁移经 state_transition_rule 的 REFUND 场景统一出口（AUDIT/REJECT/EXECUTE），不手写状态更新。
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
    -- 生成列必须放在 status/deleted 之后（生成列表达式引用它们）：
    -- 进行中(1/2)且未逻辑删除时取 order_id，否则为 NULL —— NULL 不参与唯一键比较，
    -- 于是 uk_refund_active 精确表达「同一订单最多一张进行中退款单」，且终态/删除后自动释放该键。
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

-- 业务过程迁移台账（过程层可观测基础）：每次状态迁移落一行，成功与 CAS 冲突都记录；
-- 支撑“过程回放 / 问题回溯 / 父子状态聚合对账 / 实验取证”
CREATE TABLE IF NOT EXISTS process_transition_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '台账ID',
    scene VARCHAR(30) NOT NULL COMMENT '状态机场景：ORDER/DELIVERY_TASK/DELIVERY_RECORD',
    action VARCHAR(30) NOT NULL COMMENT '动作编码：PAY/CANCEL/DELIVER/COMPLETE/DISPATCH/SIGN/REJECT...',
    entity_type VARCHAR(50) COMMENT '迁移主体表名，如 order_info',
    entity_id BIGINT COMMENT '迁移主体主键',
    biz_no VARCHAR(64) COMMENT '业务单号（订单号/任务号）',
    from_status INT COMMENT '迁移前状态',
    to_status INT COMMENT '迁移后状态',
    result TINYINT NOT NULL COMMENT '迁移结果：1-已生效，0-CAS 冲突未生效',
    operator_name VARCHAR(50) COMMENT '操作人；定时任务/对账补偿记为 system',
    remark VARCHAR(255) COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    KEY idx_scene_action (scene, action),
    KEY idx_entity (entity_type, entity_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务过程迁移台账';

-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
    user_id BIGINT COMMENT '操作用户ID',
    username VARCHAR(50) COMMENT '操作用户名',
    operation VARCHAR(100) COMMENT '操作描述',
    method VARCHAR(200) COMMENT '请求方法（类名.方法名）',
    request_url VARCHAR(500) COMMENT '请求URL',
    request_method VARCHAR(10) COMMENT 'HTTP方法（GET/POST/PUT/DELETE）',
    request_params TEXT COMMENT '请求参数（JSON）',
    ip VARCHAR(50) COMMENT '操作IP',
    cost_time BIGINT COMMENT '耗时（毫秒）',
    status TINYINT DEFAULT 1 COMMENT '操作状态：0-失败，1-成功',
    error_msg TEXT COMMENT '异常信息',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    KEY idx_user_id (user_id),
    KEY idx_create_time (create_time),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- ============================================================
-- 10. 过程层扩展：补偿规则 / 不变量体检 / 实时自愈
-- ============================================================

-- 过程补偿规则表：把「父过程状态 + 子过程条件 → 补偿动作」从代码硬编码下沉为可配置规则。
-- 与 state_transition_rule（迁移是否允许）分工：本表回答“发现漂移时该补偿成什么状态”。
-- 三层分离：探测器按 parent_status 取候选 → 本表决策动作 → 统一迁移出口执行。
CREATE TABLE IF NOT EXISTS process_reconcile_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '规则ID',
    name VARCHAR(64) NOT NULL COMMENT '规则名（管理端展示）',
    parent_scene VARCHAR(30) NOT NULL COMMENT '父过程场景，如 ORDER',
    parent_status INT NOT NULL COMMENT '父过程需满足的状态（候选筛选条件）',
    child_condition VARCHAR(40) NOT NULL COMMENT '子过程条件编码：HAS_DISPATCHING_TASK/ALL_TASKS_TERMINAL/ALL_TASKS_CANCELLED/NONE',
    action VARCHAR(30) NOT NULL COMMENT '补偿动作（复用 StateTransitions 动作码）',
    target_status INT NOT NULL COMMENT '补偿后的父过程状态',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1-启用，0-停用',
    description VARCHAR(255) COMMENT '规则说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_reconcile (parent_scene, parent_status, child_condition, action),
    KEY idx_scene_enabled (parent_scene, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='过程补偿规则表（漂移检测/决策/执行中的决策层）';

-- 过程不变量体检记录：把“不变量只在被验证时才成立”从一次性实验升级为运行期持续验证。
-- 每轮体检重新求值：仍违规的更新明细，不再违规的自动闭环（status 置 1）。
CREATE TABLE IF NOT EXISTS process_invariant_violation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    invariant_code VARCHAR(40) NOT NULL COMMENT '不变量编码，如 INV_QUOTA_LEDGER',
    severity VARCHAR(16) NOT NULL COMMENT '严重度：AUTO_REPAIR-可自动修复，ALERT_ONLY-仅告警需人工',
    entity_type VARCHAR(50) NOT NULL COMMENT '违规主体表名',
    entity_id BIGINT NOT NULL COMMENT '违规主体主键',
    biz_no VARCHAR(64) COMMENT '业务单号（便于人工定位）',
    detail VARCHAR(500) COMMENT '违规明细（含期望值与实际值）',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-未闭环，1-已闭环（修复成功或复检通过），2-人工忽略',
    reopen_count INT NOT NULL DEFAULT 0 COMMENT '重复漂移次数：已闭环后再次被检出则累加（自愈有效性度量）',
    repair_action VARCHAR(64) COMMENT '修复动作 / 闭环原因 / 最近一次修复失败原因',
    detected_time DATETIME NOT NULL COMMENT '最近一次检出时间',
    handled_time DATETIME COMMENT '闭环时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    -- 每个（不变量 × 主体）只保留一行“当前一致性状态”：闭合后再次漂移则重新打开并累加 reopen_count，
    -- 避免“同一主体反复漂移”在表里堆出多行而无法一眼看出当前是否一致
    UNIQUE KEY uk_violation (invariant_code, entity_type, entity_id),
    KEY idx_code_status (invariant_code, status),
    KEY idx_detected_time (detected_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='过程不变量体检记录';

-- 过程自愈待办（实时通道）：子过程状态变更的同一事务内落一条待办，
-- 由秒级消费者立即驱动父过程聚合；重复消费幂等。与定时兜底构成双通道。
CREATE TABLE IF NOT EXISTS process_pending_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '待办ID',
    scene VARCHAR(30) NOT NULL COMMENT '待补偿的父过程场景，如 ORDER',
    entity_type VARCHAR(50) NOT NULL COMMENT '父过程表名',
    entity_id BIGINT NOT NULL COMMENT '父过程主键',
    biz_no VARCHAR(64) COMMENT '业务单号',
    trigger_action VARCHAR(30) NOT NULL COMMENT '触发动作（子过程终态动作），用于去重',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待处理，1-已处理，2-已放弃（超重试上限，转兜底通道）',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_time DATETIME(3) COMMENT '下次可处理时间（指数退避）',
    last_error VARCHAR(255) COMMENT '最近一次失败原因',
    -- 毫秒精度：实时通道的「入队 → 处理完成」耗时要能度量到毫秒，
    -- 秒精度下所有样本只会落在 0ms / 1000ms 两档，度量失去意义（时间列精度也是可观测性的一部分）
    create_time DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_pending (scene, entity_id, trigger_action, status),
    KEY idx_status_next (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='过程自愈待办（实时通道）';

-- ============================================================
-- 11. 供给侧：仓库余量台账（warehouse_ledger）
-- ============================================================
-- 履约链起点是"奶企 → 学校配送站（收货）→ 公共领取点 → 班级/学生"，
-- 而配额池建模的是"供货计划"，与物理世界之间原本没有对账通道。本表补上这条通道：
--   W(品种) = ΣIN + ΣIN_BACK + ΣINIT + ΣADJ(带符号) − ΣOUT      —— 任何时刻 W ≥ 0
-- 语义边界（重要）：
--   ① 这不是库存/ERP：单表一条进出账，只回答"还有多少盒可卖"，不做效期、先进先出、盘点、库位；
--   ② 台账只增不改（与 process_transition_log 同一原则）：记错走反向 ADJ 冲销，两条留痕；
--   ③ 出库时点 = 「今日已送出」(batch-start)，不是签收——物理移动发生在仓库→领取点；
--   ④ OUT 的 biz_date 取 task.delivery_date（业务日账），实际送出时刻看 delivery_task.dispatch_time。
-- 设计依据：docs/设计方案/2026-09-21-仓库余量与出库边界-设计方案.md（口径以该文 §11 评审修订为准）
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
