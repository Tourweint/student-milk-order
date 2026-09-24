# 学生奶订购系统

> Student Milk Order Management System

基于 Vue3 + Spring Boot + MySQL 的前后端分离学生奶订购管理系统，覆盖 Web 管理端与微信小程序移动端。

## 项目简介

本系统针对中小学校园学生奶订购业务，实现从选套餐、下单、支付、配送到营养统计的全链路管理。系统采用面向对象分析与设计方法，以 UML 作为建模语言，基于前后端分离架构实现。

**研究定位**：本项目的核心研究对象不是「一个订奶系统」，而是**「周期性业务履约中的状态管理与异常恢复」**——
一个业务对象进入系统后会经历长期、多阶段、异步、可失败的执行过程，系统需要让这个过程在并发、重复、
超时和局部失败下仍然可控；校园乳品配送是验证场景。抽象模型、机制设计与量化验证见
[业务过程与可靠执行模型](docs/基线文档/业务过程与可靠执行模型.md) 与 [实验设计与结果](docs/实验/实验设计与结果.md)。

## 技术栈

### 后端
- Java 17 + Spring Boot 2.7.18
- Spring Security + JWT（认证授权）
- MyBatis-Plus 3.5.3（数据访问）
- MySQL 8 / MariaDB 10.6+（数据库，事务隔离级别统一 `READ_COMMITTED`）
- Apache POI（Excel 导入导出）
- Spring Scheduler（定时任务：超时兜底、支付对账、过程聚合对账、自动签收）

### 前端（Web 管理端）
- Vue 3 + TypeScript + Vite
- Element Plus（UI 组件库）
- ECharts（数据可视化）
- Pinia（状态管理）
- Vue Router（路由）
- Axios（HTTP 客户端）

### 移动端（后续开发）
- 微信小程序，共用后端 RESTful API

## 功能模块

| 模块 | 说明 |
|------|------|
| 用户与权限 | 登录注册、RBAC 四角色（管理员/班主任/配送站/家长）+ 数据范围校验 |
| 班级与学生 | 年级、班级、学生管理、Excel 批量导入 |
| 奶品管理 | 品类、奶品、学期套餐（固定明细）、每日机动配额（含保质期结转与先过期先出） |
| 订单核心 | 下单、模拟支付（微信模拟链路 + 同步模拟支付）、状态机流转、退订与配额精确回补 |
| 配送管理 | 整期配送任务展开、配送站"今日已送出"、签收/拒收、自动签收兜底、配送日调整（平移 / 日历重排（周末停送与调休例外）/ 期末摊平） |
| 营养统计 | 营养成分维护、签收自动生成摄入记录、摄入统计与报表 |
| 数据可视化 | 仪表盘、订单趋势、品类占比、班级排行、覆盖率 |
| 系统管理 | 系统参数在线配置、状态迁移规则在线配置、过程迁移台账、操作日志 |
| 过程层 / 可靠性层 | 状态迁移统一出口（规则校验 + CAS 条件更新 + 迁移留痕）、幂等守卫、对账补偿机制 |

## 项目结构

```
student-milk-order/
├── school-ui/              # 前端 Web 管理端
│   ├── src/
│   │   ├── api/            # 接口封装（按模块分文件）
│   │   ├── components/     # 通用组件
│   │   ├── router/         # 路由配置
│   │   ├── stores/         # Pinia 状态管理
│   │   ├── styles/         # 全局样式
│   │   ├── utils/          # 工具函数
│   │   ├── views/          # 页面
│   │   ├── App.vue
│   │   └── main.ts
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts
│   └── tsconfig.json
├── src/                    # 后端
│   ├── main/java/com/milk/order/
│   │   ├── common/         # 通用（统一返回、分页、基类、常量、枚举、工具）
│   │   ├── config/         # 配置类
│   │   ├── security/       # 认证授权（JWT、过滤器）
│   │   ├── exception/      # 全局异常处理
│   │   ├── process/        # 过程层：状态迁移统一出口、迁移规格、迁移台账
│   │   ├── reliability/    # 可靠性层：幂等守卫等可靠执行原语
│   │   └── module/         # 业务模块
│   │       ├── auth/       # 认证模块
│   │       ├── user/       # 用户与权限模块
│   │       ├── clazz/      # 班级与学生模块
│   │       ├── product/    # 奶品管理与每日机动配额模块
│   │       ├── order/      # 订单核心模块（含支付对账、超时取消、过程聚合对账 Job）
│   │       ├── delivery/   # 配送管理模块（含自动签收兜底 Job）
│   │       ├── nutrition/  # 营养统计模块
│   │       ├── stats/      # 数据可视化模块
│   │       └── system/     # 系统管理（参数配置、状态迁移规则、迁移台账）
│   ├── main/resources/
│   │   ├── application.yml
│   │   └── sql/            # 数据库脚本
│   │       ├── schema.sql  # 建表脚本
│   │       └── data.sql    # 初始数据与状态迁则种子
│   └── test/
│       ├── java/com/milk/order/experiment/  # 14 组并发/幂等/异常恢复实验
│       ├── java/com/milk/order/contract/    # 状态机契约矩阵测试
│       └── resources/application-test.yml   # 实验专用配置（独立实验库、关闭定时任务）
├── docs/                   # 项目文档
│   ├── 基线文档/           # 长期基线：业务过程与可靠执行模型、可靠性设计、系统架构、接口文档、支付链路、部署手册
│   ├── 设计方案/           # 设计期过程文档（带日期，留档：设计取舍与备选，实现后下沉到上述基线文档）
│   ├── 实验/               # 实验设计与结果、实验环境与运行说明
│   ├── 论文/               # 选题与章节结构
│   ├── 工程化亮点/         # 评审展示材料
│   ├── 研发规范/           # 文档驱动开发规范
│   └── 变更记录/           # 大改动变更日志（落地期过程文档）
├── pom.xml
└── README.md
```

## 快速开始

### 1. 数据库初始化

```bash
# 创建数据库并执行脚本
mysql -u root -p < src/main/resources/sql/schema.sql
mysql -u root -p student_milk_order < src/main/resources/sql/data.sql
```

默认管理员：`admin / 123456`

> `schema.sql` 已包含 `CREATE DATABASE student_milk_order` 与 `USE`，可用 `mysql -u root -p < schema.sql` 一次执行。
> 跑实验另需独立的 `student_milk_order_test` 库，准备步骤见 [实验环境与运行说明](docs/实验/实验环境与运行说明.md)。

### 2. 后端启动

```bash
# 修改 src/main/resources/application.yml 中的数据库配置
mvn clean compile
mvn spring-boot:run
```

后端端口：`8090`

### 3. 前端启动

```bash
cd school-ui
npm install
npm run dev
```

前端端口：`5173`

### 4. 运行实验（可选，验证可靠性机制）

```bash
mvn test
```

实验会连接**独立实验库** `student_milk_order_test`（不触碰开发库），并关闭全部定时任务以保证结论可归因。
首次运行前需按 [实验环境与运行说明](docs/实验/实验环境与运行说明.md) 准备实验库；
实验数据归档在 `target/experiment-reports/`。

## 文档

核心（建议先读）：

- [业务过程与可靠执行模型](docs/基线文档/业务过程与可靠执行模型.md) — 问题定义、四层模型、过程层与父子状态机、不变量清单
- [可靠性设计](docs/基线文档/可靠性设计.md) — 幂等 / CAS / 行锁 / 台账 / 对账补偿的机制与判定口径
- [实验设计与结果](docs/实验/实验设计与结果.md) — 14 组实验 + 契约矩阵 + 补充用例（共 38 个用例）的实测数据与暴露的 7 个缺陷
- [选题与章节结构](docs/论文/选题与章节结构.md) — 论文题目层次、章节结构、图表清单、答辩问答要点

其他：

- [管理员操作手册](docs/使用说明/管理员操作手册.md) — **使用侧文档**：日常五步操作、开学准备、异常处置与常见问题（第一次上手看这份）
- [系统架构](docs/基线文档/系统架构.md)
- [接口文档](docs/基线文档/接口文档.md)
- [支付链路](docs/基线文档/支付链路.md)
- [核心链路图](docs/基线文档/核心链路图.md)
- [部署手册](docs/基线文档/部署手册.md)
- [实验环境与运行说明](docs/实验/实验环境与运行说明.md)
- [工程化难点与亮点](docs/工程化亮点/工程化难点与亮点.md)
- [项目开发规范](docs/研发规范/项目开发规范.md)
- [设计方案：周末停送与调休例外（配送日历重排）](docs/设计方案/2026-09-20-周末停送与调休例外-设计方案.md) — 设计取舍留档示例
- [完整文档目录](docs/README.md)

## 小程序接入说明

微信小程序端直接调用后端 RESTful API：
- API 基地址：`/api`
- 认证：JWT Token，请求头 `Authorization: Bearer {token}`
- 登录：支持账号密码登录（`/auth/login`）与微信授权登录（`/auth/wx-login` → 未绑定走 `/auth/wx-bind`）
- 数据权限：家长角色自动限定为自己绑定的学生，班主任限定本班，越权返回 403
- 接口与微信登录流程详见 [接口文档](docs/基线文档/接口文档.md)

### 小程序目录（miniprogram/）

```
miniprogram/
├── app.js / app.json / app.wxss   全局入口与配置（tabBar：首页/我的）
├── config/index.js                全局配置：BASE_URL、USE_MOCK_WX
├── utils/
│   ├── request.js                 wx.request 封装（token 注入、401 处理、query 拼接）
│   └── auth.js                    登录态管理（token/用户信息存取）
├── api/                           接口模块：auth/product/order/nutrition/delivery/student
└── pages/
    ├── login/                     登录绑定页（wx.login → wx-login → wx-bind）
    ├── index/                     首页（套餐 + 奶品列表）
    ├── product/                   奶品详情（立即订购）
    ├── order-create/              下单页（奶品直购 / 套餐订购 + 配送日期 + 模拟支付）
    ├── order-list/                我的订单（状态筛选、去支付、退订、进详情）
    ├── order-detail/              订单详情（明细、支付信息、退订、去支付/退订操作）
    ├── nutrition/                 营养统计（近7/30天汇总 + 每日摄入 + 摄入记录）
    ├── delivery/                  配送记录（配送日期、奶品、签收状态）
    └── mine/                      我的（用户信息、功能入口、退出）
```

> 说明：后端统一分页结构字段为 `list`（`PageResult.total/pageNum/pageSize/list`），前端分页读取一律用 `res.list`。

### 开发者工具导入

1. 微信开发者工具 → 导入项目 → 选择目录 `miniprogram/`
2. AppID：使用测试号或注册的小程序 AppID（本地联调不校验域名）
3. 后端启动后（`mvn spring-boot:run`），首页即可请求本机 `http://localhost:8090/api`
4. 本地联调默认走微信 mock 模式（`config/index.js` 的 `USE_MOCK_WX=true`，后端 `wechat.mock-enabled=true`），无需真实小程序凭据

## 开发规范

本项目遵循文档驱动开发规范，详见 [项目开发规范](docs/研发规范/项目开发规范.md)。

核心原则：先文档、后实现、再校验。大改动需写入 `docs/变更记录/`。
