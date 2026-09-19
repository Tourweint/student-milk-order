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
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_category_id (category_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='奶品表';

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
    KEY idx_status (status)
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
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    KEY idx_task_id (task_id),
    KEY idx_student_id (student_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送记录表';

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
    scene VARCHAR(30) NOT NULL COMMENT '状态机场景：ORDER-订单，DELIVERY_TASK-配送任务',
    action VARCHAR(30) NOT NULL COMMENT '动作编码：PAY/CANCEL/DELIVER/AUTO_COMPLETE/COMPLETE/DISPATCH/TASK_CANCEL/SIGN/REJECT/STOCKOUT_CANCEL',
    from_status TINYINT NOT NULL COMMENT '来源状态码',
    allowed TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许迁移：1-允许，0-禁止',
    description VARCHAR(255) COMMENT '规则说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_scene_action_from (scene, action, from_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态迁移规则表（管理端可配置）';

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
    next_retry_time DATETIME COMMENT '下次可处理时间（指数退避）',
    last_error VARCHAR(255) COMMENT '最近一次失败原因',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT DEFAULT 0 COMMENT '逻辑删除',
    UNIQUE KEY uk_pending (scene, entity_id, trigger_action, status),
    KEY idx_status_next (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='过程自愈待办（实时通道）';
