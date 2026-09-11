# 学生奶订购系统

> Student Milk Order Management System

基于 Vue3 + Spring Boot + MySQL 的前后端分离学生奶订购管理系统，覆盖 Web 管理端与微信小程序移动端。

## 项目简介

本系统针对中小学校园学生奶订购业务，实现从选套餐、下单、支付、配送到营养统计的全链路管理。系统采用面向对象分析与设计方法，以 UML 作为建模语言，基于前后端分离架构实现。

## 技术栈

### 后端
- Java 17 + Spring Boot 2.7.18
- Spring Security + JWT（认证授权）
- MyBatis-Plus 3.5.3（数据访问）
- MySQL 8（数据库）
- Apache POI（Excel 导入导出）
- Spring Scheduler（定时任务）

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
| 用户与权限 | 登录注册、RBAC 三角色（管理员/班主任/家长） |
| 班级与学生 | 年级、班级、学生管理、Excel 批量导入 |
| 奶品管理 | 品类、奶品、套餐、库存、库存预警 |
| 订单核心 | 选套餐下单、模拟支付、订单状态流转、退订 |
| 配送管理 | 配送任务生成、配送记录、签收 |
| 营养统计 | 营养成分维护、摄入统计、营养报表 |
| 数据可视化 | 仪表盘、订单趋势、品类占比、班级排行、覆盖率 |
| 月度自动续订 | 续订计划、定时自动生成订单、续订提醒 |

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
│   │   ├── common/         # 通用（统一返回、分页、基类、常量、枚举）
│   │   ├── config/         # 配置类
│   │   ├── security/       # 认证授权（JWT、过滤器）
│   │   ├── exception/      # 全局异常处理
│   │   ├── util/           # 工具类
│   │   └── module/         # 业务模块
│   │       ├── auth/       # 认证模块
│   │       ├── user/       # 用户与权限模块
│   │       ├── clazz/      # 班级与学生模块
│   │       ├── product/    # 奶品管理模块
│   │       ├── order/      # 订单核心模块
│   │       ├── delivery/   # 配送管理模块
│   │       ├── nutrition/  # 营养统计模块
│   │       ├── stats/      # 数据可视化模块
│   │       └── subscription/ # 自动续订模块
│   ├── main/resources/
│   │   ├── application.yml
│   │   └── sql/            # 数据库脚本
│   │       ├── schema.sql  # 建表脚本
│   │       └── data.sql    # 初始数据
│   └── test/               # 单元测试
├── docs/                   # 项目文档
│   ├── 基线文档/           # 系统架构、接口文档、部署手册
│   ├── 研发规范/           # 文档驱动开发规范
│   └── 变更记录/           # 大改动变更日志
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

## 文档

- [系统架构](docs/基线文档/系统架构.md)
- [接口文档](docs/基线文档/接口文档.md)
- [部署手册](docs/基线文档/部署手册.md)
- [项目开发规范](docs/研发规范/项目开发规范.md)

## 小程序接入说明

微信小程序端后续开发，直接调用后端 RESTful API：
- API 基地址：`/api`
- 认证：JWT Token，请求头 `Authorization: Bearer {token}`
- 家长端接口已在后端预留，详见 [接口文档](docs/基线文档/接口文档.md)

## 开发规范

本项目遵循文档驱动开发规范，详见 [文档驱动开发规范](docs/研发规范/文档驱动开发规范.md)。

核心原则：先文档、后实现、再校验。大改动需写入 `docs/变更记录/`。
