# AGENTS.md

本文件是本仓库中 AI 编码代理的项目级工作说明。它记录需要反复遵守、且不应靠猜测决定的项目事实与边界；详细需求和完整规则以 `docs/` 中的文档为准。

## 工作原则

- 不确定时不要编造约定、接口或业务规则。先阅读相关代码、配置、`README.md` 与 `docs/`，再作决定。
- 修改前搜索相似模块，沿用已存在的分层、命名和实现方式；不要为局部需求新建架构层或重构无关代码。
- 本项目仍有不少 Controller 和 Service 的占位实现（`TODO`、空列表或空对象）。它们是待实现的脚手架，不代表已经具备该行为；实现前须核对接口文档、数据模型和调用方。
- 功能开发遵循“先文档、后实现、再验证”。涉及接口、架构或重要业务行为的变动，先更新相应文档；较大变更记录到 `docs/变更记录/`。
- 不在代码、文档或提交中添加密码、JWT 密钥、Token 或其他真实凭据。不要随意改动 `application.yml` 的数据库、安全或生产配置。

## 项目概览

学生奶订购管理系统，采用前后端分离：

- 后端：Java 17、Spring Boot 2.7、Spring Security + JWT、MyBatis-Plus、MySQL 8。
- 前端：Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router、Axios、ECharts。
- `school-ui/` 是 Web 管理端；微信小程序为后续计划，不能假定其代码或接口已经完成。
- 后端本地端口为 `8090`；Vite 开发服务器为 `5173`，并将 `/api` 代理到后端。

先阅读：

- `README.md`：项目范围、启动方式和模块简介。
- `docs/基线文档/系统架构.md`：架构、模块和订单状态流转。
- `docs/基线文档/接口文档.md`：接口契约。
- `docs/研发规范/项目开发规范.md`：详细的开发、命名与业务规则。
- `docs/基线文档/部署手册.md`：环境和部署要求。

## 仓库结构与边界

```text
src/main/java/com/milk/order/
  common/       # ApiResponse、PageResult、基类、常量和枚举
  config/       # Spring、MyBatis-Plus、Jackson、安全配置
  security/     # JWT 解析和认证过滤器
  exception/    # BusinessException 与全局异常处理
  module/<name>/
    controller/ # REST 入口
    service/    # 业务接口与实现
    mapper/     # MyBatis-Plus 数据访问
    entity/     # 持久化实体；需要时含 dto/、vo/、job/
src/main/resources/
  application.yml
  sql/schema.sql, sql/data.sql
school-ui/src/
  api/          # 后端 API 调用封装
  components/   # 不依赖具体业务页面的可复用 UI
  router/       # 路由与前端登录守卫
  stores/       # Pinia 跨页面状态
  styles/       # 全局样式与设计变量
  utils/        # request、auth 等通用工具
  views/        # 按业务模块组织的页面
```

后端按业务模块组织，目前包括 `auth`、`user`、`clazz`、`product`、`order`、`delivery`、`nutrition`、`stats`、`subscription`。新模块须先确认它属于项目既定范围，不能仅因实现方便而创建。

## 后端规则

- 依赖方向必须保持为 `Controller -> Service -> Mapper -> MySQL`。Controller 只做请求/响应和参数绑定；业务规则、权限数据范围与跨表流程放在 Service；Mapper 只负责持久化访问。
- API 使用 `/api` 前缀、REST 风格 HTTP 方法，并返回 `ApiResponse<T>`（`code`、`message`、`data`）。分页沿用 `PageResult<T>` 与 `pageNum`/`pageSize` 参数，不另造响应格式。
- 请求参数应使用 DTO 和 `@Valid`；业务失败抛 `BusinessException`，由 `GlobalExceptionHandler` 统一转换为用户可理解的响应。不要在 Controller 中吞异常或直接抛泛用 `RuntimeException`。
- 涉及多表写入的新增、修改或删除，使用 `@Transactional` 保证原子性；事务中不要调用外部 HTTP/RPC 服务。
- 身份由 JWT 过滤器建立，角色以 `ROLE_<角色名>` 进入 Spring Security。前端路由或请求参数只能改善体验，不能替代后端授权与数据范围校验。
- 现有实体映射遵从 MyBatis-Plus；不要绕过 Mapper 直接拼接数据库访问，也不要手工修改库表来代替迁移/SQL 文档变更。

## 核心业务不变量

实现或修改下列领域前，必须阅读研发规范中对应章节并检查现有实体、枚举、服务和接口：

- 订单状态只能经 `OrderInfoService` 按既定状态机单向流转；支付、退订、配送和完成不能在 Controller 或其他服务中直接改 `OrderInfo.status`。
- 库存只能经 `InventoryService` 变更，并要保留 `inventory_record` 流水、检查库存不足与预警规则。
- 模拟支付必须在同一事务中写入支付记录、更新订单状态和支付时间，并避免重复支付。
- 营养摄入记录由配送签收生成，营养统计是聚合查询，不应手工篡改摄入记录。
- 续订任务是定时任务；生产续订不可被随意手工触发。
- 密码必须 BCrypt 存储；数据权限在 Service 层落实：家长仅看自己的孩子，班主任仅看本班，管理员可查看全部。

## 前端规则

- 页面放在 `views/` 的对应模块；通用、无业务依赖的 UI 放在 `components/`。业务专属子组件优先放在 `views/<module>/components/`，不要污染通用组件目录。
- 页面和组件不得直接使用 Axios。所有后端请求经 `api/<module>.ts`，其底层统一使用 `utils/request.ts` 的 `get`、`post`、`put`、`del`；不要新增第二套请求客户端或绕过现有 JWT/错误处理。
- `utils/request.ts` 已处理 `/api` 基地址、Bearer Token、业务错误提示及 401 清理登录态。修改认证流程时需要同时审查该文件、`utils/auth.ts`、`stores/user.ts`、路由守卫与后端安全配置。
- Pinia 仅管理跨页面共享状态（当前用户、角色、全局配置等）；页面局部状态保留在页面/组件内。API 调用集中在 `api/`，Store 只调用 API 层函数。
- 复用 Element Plus 与 `styles/variables.scss` 中的设计变量；全局样式放 `styles/`，组件样式使用 `scoped`，不要在单页组件里注入大量全局 CSS。
- 使用 TypeScript 明确数据类型。新增 API 先写参数/返回值类型，避免把新增代码建立在 `any` 上。

## 命名与规模

- Java 包名小写，业务模块使用单数名；Controller、Service、ServiceImpl、Mapper、Entity、DTO、VO 按现有后缀命名。
- Vue 页面和通用组件用 PascalCase（如 `OrderManage.vue`）；API 文件和方法用 camelCase；组合式函数使用 `useXxx`；常量使用 `UPPER_SNAKE_CASE`。
- 路由 path 使用小写连字符，路由 name 使用 PascalCase。
- 单一类/组件只承担一个明确职责。页面或类接近 500 行、或包含多块独立逻辑时，先检查现有模式，再在合适位置拆分；不要为满足行数机械拆分。

## 验证命令

按改动范围运行最小充分验证，并如实报告未执行的验证：

```powershell
# 后端（仓库根目录）
mvn clean compile
mvn test
mvn spring-boot:run

# 前端
cd school-ui
npm install
npm run build
npm run dev
```

修改数据库结构或初始数据前，先更新相关文档并审查 `src/main/resources/sql/schema.sql` 与 `data.sql`；数据库初始化与部署步骤见 `docs/基线文档/部署手册.md`。

## 完成标准

提交一个改动前，确认：需求范围已实现；分层和接口契约一致；核心业务规则与权限没有被绕过；相关文档已更新；并已运行适合该改动的编译、类型检查、构建或测试。完成说明应包含改动内容、验证结果与尚存的不确定性。
