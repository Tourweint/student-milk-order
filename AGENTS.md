# AGENTS.md

本文件是本仓库中 AI 编码代理的项目级工作说明。它记录需要反复遵守、且不应靠猜测决定的项目事实与边界；详细需求和完整规则以 `docs/` 中的文档为准。

## 工作原则

- 不确定时不要编造约定、接口或业务规则。先阅读相关代码、配置、`README.md` 与 `docs/`，再作决定。
- 修改前搜索相似模块，沿用已存在的分层、命名和实现方式；不要为局部需求新建架构层或重构无关代码。
- 当前代码库**没有** `TODO` / 整方法占位的实现（2026-09-19 全量审计确认）。2026-09-19 清理时已处理全部「已实现却未接通」项：续订模块（业务不需要）整体删除、营养看板接入数据看板、未使用的 API 包装删除。**新增能力前先确认业务是否需要；不要擅自恢复已删除的业务模块**（如需恢复，见 git 历史与 `docs/变更记录/`）。
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
- `docs/基线文档/业务过程与可靠执行模型.md`：**核心抽象**——问题定义、四层模型、过程层与可靠性层的代码落点、不变量清单。
- `docs/基线文档/可靠性设计.md`：幂等、CAS、行锁、台账、对账补偿的机制细节与判定口径。
- `docs/基线文档/接口文档.md`：接口契约。
- `docs/研发规范/项目开发规范.md`：详细的开发、命名与业务规则。
- `docs/基线文档/部署手册.md`：环境和部署要求。
- `docs/实验/`：验证层的实验设计与实测数据（改动可靠性机制后应重跑）。

## 仓库结构与边界

```text
src/main/java/com/milk/order/
  common/       # ApiResponse、PageResult、基类、常量和枚举
  config/       # Spring、MyBatis-Plus、Jackson、安全配置
  security/     # JWT 解析和认证过滤器
  exception/    # BusinessException 与全局异常处理
  process/      # 过程层：状态迁移统一出口与迁移台账；reconcile/ 补偿规则引擎（探测-决策-执行）、
                #   pending/ 实时自愈待办、invariant/ 跨表不变量体检、job/ 实时消费与体检调度
                #   （横切，不依赖业务 Service；业务侧实现 ParentProcessProbe/ParentProcessAggregator/ProcessInvariant）
  reliability/  # 可靠性层：幂等守卫等跨模块可靠执行原语
  module/<name>/
    controller/ # REST 入口
    service/    # 业务接口与实现
    mapper/     # MyBatis-Plus 数据访问
    entity/     # 持久化实体；需要时含 dto/、vo/、job/
src/main/resources/
  application.yml
  sql/schema.sql, sql/data.sql
src/test/java/com/milk/order/experiment/   # 并发/幂等/异常恢复实验（实验一~十三）
src/test/java/com/milk/order/contract/     # 状态机契约矩阵测试（规则覆盖/安全禁止/台账可回放）
src/test/resources/application-test.yml    # 实验专用配置（独立实验库、关闭定时任务）
school-ui/src/
  api/          # 后端 API 调用封装
  components/   # 不依赖具体业务页面的可复用 UI
  router/       # 路由与前端登录守卫
  stores/       # Pinia 跨页面状态
  styles/       # 全局样式与设计变量
  utils/        # request、auth 等通用工具
  views/        # 按业务模块组织的页面
```

后端按业务模块组织，目前包括 `auth`、`user`、`clazz`、`product`、`order`、`delivery`、`nutrition`、`stats`、`system`。新模块须先确认它属于项目既定范围，不能仅因实现方便而创建。

`process/` 与 `reliability/` 是**横切机制层**，不是业务模块，禁止在其中反向依赖任何业务 Service。

## 后端规则

- 依赖方向必须保持为 `Controller -> Service -> Mapper -> MySQL`。Controller 只做请求/响应和参数绑定；业务规则、权限数据范围与跨表流程放在 Service；Mapper 只负责持久化访问。
- 涉及状态迁移时，Service 通过过程层执行器完成，不要手写「规则判断 + 条件更新 + 抛异常」三件套：用户直接发起的操作用 `require`（失败抛业务异常），批量流转/定时兜底/对账补偿用 `attempt`（返回 false 即跳过，天然幂等）。
- API 使用 `/api` 前缀、REST 风格 HTTP 方法，并返回 `ApiResponse<T>`（`code`、`message`、`data`）。分页沿用 `PageResult<T>` 与 `pageNum`/`pageSize` 参数，不另造响应格式。
- 请求参数应使用 DTO 和 `@Valid`；业务失败抛 `BusinessException`，由 `GlobalExceptionHandler` 统一转换为用户可理解的响应。不要在 Controller 中吞异常或直接抛泛用 `RuntimeException`。
- 涉及多表写入的新增、修改或删除，使用 `@Transactional` 保证原子性；事务中不要调用外部 HTTP/RPC 服务。
- 身份由 JWT 过滤器建立，角色以 `ROLE_<角色名>` 进入 Spring Security。前端路由或请求参数只能改善体验，不能替代后端授权与数据范围校验。
- 现有实体映射遵从 MyBatis-Plus；不要绕过 Mapper 直接拼接数据库访问，也不要手工修改库表来代替迁移/SQL 文档变更。
- 过程迁移台账 `process_transition_log` 是「已提交业务事实」的记录，**只读**：只允许过程层新增与查询，禁止业务代码修改或删除；管理端查询接口为 `GET /api/system/transition-log/list`（仅管理员，页面在「系统管理 → 过程迁移台账」）。

## 核心业务不变量

实现或修改下列领域前，必须阅读 `docs/基线文档/业务过程与可靠执行模型.md` 的「不变量清单」、
`docs/基线文档/可靠性设计.md` 与研发规范中对应章节，并检查现有实体、枚举、服务和接口：

- **状态迁移**：订单、配送任务（以及配送记录子状态机）的 `status` 变更只能经
  `ProcessTransitionExecutor`（`com.milk.order.process`）执行，且必须是以原状态为条件的 CAS 更新；
  禁止读状态后无条件下全量更新。支付、退订、配送和完成不能在 Controller 中直接改状态。
- **规则种子**：状态迁移是否允许由 `state_transition_rule` 白名单驱动；新增状态或动作必须同步补
  `src/main/resources/sql/data.sql` 的规则种子，否则该迁移会被拒绝。
- **父子状态**：父订单状态只能由子过程聚合决定（`markDeliveringIfPaid` / `completeOrderIfAllTasksDone`），
  不得由某个子任务直接改写。子过程状态变更时必须在**同一事务内**
  `ProcessPendingTaskService.enqueue` 一条父过程自愈待办（实时通道）；漂移的探测与补偿由
  `ProcessReconcileEngine` + `ProcessReconcileCoordinator` 按 `process_reconcile_rule` 规则表执行，
  补偿动作只能经 `ParentProcessAggregator` 落回统一迁移出口——**不要新增硬编码的补偿 `if`**。
- **补偿规则**：`process_reconcile_rule` 决定「什么漂移补偿成什么状态」，可在线启停；启用一条新规则前
  必须确认 `state_transition_rule` 放开了对应迁移（否则补偿会被闸门拒绝）。新规则默认设为停用。
- **不变量体检**：新增跨表约束时登记为 `ProcessInvariant`（探测只读、修复必须走业务出口）；
  涉资金、终态历史或需业务判断的一律 `ALERT_ONLY`，不得自动改，也不要"为了把数字补整齐"去补造历史。
  判定口径要考虑「系统本来就在处理中」的中间态，并确认修复不会与已有不变量冲突。
  处置等级可在每条违规上覆写（`InvariantViolation.severity`），不变量声明值只是默认；
  **修复会改变其它检查项判定依据的，必须用 `dependsOn()` 声明依赖**（当前：网格补全 → 父聚合 → 组合守卫），
  不要依赖编码字典序碰巧排对；存在未被任何不变量覆盖的"组合约束"时，补一条组合守卫。
- **顺序**：同一动作同时涉及状态迁移与副作用（扣资源、写流水、展开任务）时，必须先 CAS 抢占状态，
  落败方立即中止。顺序颠倒会产生重复副作用并互撞业务唯一键。
- **资源变更**：只能经 `DailyQuotaService` —— 扣减必须「`selectForUpdate` 行锁读 + `used_quota + n <= total_quota`
  条件更新 + 写 `daily_quota_usage` 台账」，回补必须按台账回到原池子。
  （历史说明：`InventoryService` / `inventory_record` 已于 2026-09-12 随订购模式重构删除，不再存在。）
- **幂等**：需要「同一业务键只存在一行」的记录必须建唯一约束，应用层用 `IdempotencyGuard` 把唯一键冲突
  翻译为「已存在」；禁止用「先 `selectCount` 再 `insert`」实现幂等。
- **支付**：模拟支付必须在同一事务内完成「状态抢占 + 配额扣减 + 支付流水 + 任务展开」，并保证重复回调只落账一次。
- **隔离级别**：数据源统一 `READ_COMMITTED`；不要在写路径改回 `REPEATABLE READ`
  （MariaDB 与 MySQL 在 RR 下对「快照建立后被并发修改的行」行为不一致）。
- 营养摄入记录由配送签收生成，营养统计是聚合查询，不应手工篡改摄入记录。
- **配送日平移 / 拒收补送**：平移**不得更新 `deliveryEndDate`**（改 end 会让网格不变量把空档日补造任务）；
  原任务作废走 CAS（幂等闸门），合并/加量的条件更新影响 0 行必须抛异常回滚，不能静默跳过。
  补送按订单类型分叉（套餐合并 `quantity`、零散新建 + 追加配额），
  且 `completeOrderIfAllTasksDone` **必须放在补送落库之后**。机制细节见 `docs/基线文档/可靠性设计.md` §十三。
- **周末停送 / 调休例外（配送日历重排）**：`delivery_exception` 是**唯一权威**，系统**不做**官方节假日自动推算；
  `delivery.weekend.stop=false`（默认）时重排**必须拒绝执行**，不得改动现状行为。重排与平移共用同一套落账骨架
  （`relocateTask`：CAS 作废 → 合并/新建 → 同步签收记录 → 父过程自愈待办），**不要再写第二套落账逻辑**；
  同样**不得更新 `deliveryEndDate`**，盒数与任务数必须守恒。顺序硬约束：**先例外维护 → 再日历重排 → 最后期末摊平**；
  单日合并上限 3 盒，超限继续向前找未满的有效工作日。方案与实测见 `docs/基线文档/周末停送与调休例外-方案.md` §11。
- 订单头与明细等「一次业务动作写入多张表」的场景，事务边界必须落在**外部调用入口**（public 方法且经
  Spring 代理），不要依赖同类内部直调的 `@Transactional`（自调用不经过代理，注解形同虚设）。
- 密码必须 BCrypt 存储；数据权限在 Service 层落实：家长仅看自己的孩子，班主任仅看本班，管理员可查看全部。
- **多实例（必须遵守）**：系统设计上支持多实例，正确性来源是**幂等 + 唯一键 + 条件更新**，不是分布式协调。
  因此：① 组件里**不要放实例本地状态**（内存 Map、静态计数器、每实例缓存）——支付状态、预支付单这类
  跨请求共享的数据必须落库（见 `wechat_pay_order`）；② **编号生成必须跨实例唯一**
  （任务号由业务键确定性推导、订单号/流水号带 `InstanceIdentity.TAG`），禁止只用"时间戳 + JVM 内自增"；
  ③ 新增定时任务必须幂等（逐条 CAS / 唯一键仲裁 / 幂等跳过），本项目**不选主**，
  重复执行只允许"浪费算力"不允许产生第二个效果；④ 多实例部署要求校时（NTP），
  `wxpay.mock.notify-url` 需指向负载均衡地址。审计表与未覆盖项见 `docs/基线文档/可靠性设计.md` 第十节。
- 体检违规记录等"每个 X 一行"的表，写入必须依赖唯一键 + `IdempotencyGuard`，不要"先查再插"（多实例下会重复插）。

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
mvn test                       # 含 13 组并发/幂等/异常恢复/多实例/混沌/长稳/性能/平移补送实验 + 契约矩阵测试（需先准备实验库）
mvn spring-boot:run

# 前端
cd school-ui
npm install
npm run build
npm run dev
```

- `mvn test` 会连接**独立实验库** `student_milk_order_test` 并关闭全部定时任务；首次运行前需按
  `docs/实验/实验环境与运行说明.md` 第 3 节准备实验库（结构与种子必须与开发库同步，否则实验会失败）。
- 实验数据归档在 `target/experiment-reports/`；改动可靠性机制（幂等、CAS、行锁、台账、对账补偿、
  双通道、不变量、多实例相关代码）后应重跑并同步文档。
- **混沌注入器只存在于测试域**（`src/test/java/com/milk/order/chaos/`），默认撤防；
  不要在生产代码里添加任何混沌/注入开关。新增注入点时注意两点：
  ① 注入点必须在**事务内部**（否则"先执行后抛"会变成"已提交但调用方看到失败"的模糊失败）；
  ② 切点写在**实现包**上（`service..*.method`），Spring Boot 默认 CGLIB 代理下写接口名匹配不到。
- 修改数据库结构或初始数据前，先更新相关文档并审查 `src/main/resources/sql/schema.sql` 与 `data.sql`；
  若新增表/唯一键，实验库必须同步重建（见上条）。数据库初始化与部署步骤见 `docs/基线文档/部署手册.md`。

## 完成标准

提交一个改动前，确认：需求范围已实现；分层和接口契约一致；核心业务规则与权限没有被绕过；相关文档已更新；并已运行适合该改动的编译、类型检查、构建或测试。完成说明应包含改动内容、验证结果与尚存的不确定性。
