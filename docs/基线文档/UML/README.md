# UML 图（补充交付）

> 对应任务清单第 8 项（用例图）、第 12 项（类图、部署图）
> 绘制日期：2026-09-15 ｜ 工具：PlantUML 1.2024.8（`*.puml` 为可编辑源文件，`*.png` 为渲染图）

## 图件清单

| 图 | 文件 | 说明 |
|---|---|---|
| 用例图 | `用例图.puml` / `用例图.png` | 4 类参与者 × 34 个用例，覆盖认证、班级学生、奶品配额、订单支付、配送、营养、可视化、续订、系统管理 10 个模块，与 `docs/基线文档/接口文档.md` 逐条对应 |
| 类图 | `类图.puml` / `类图.png` | 21 个实体类（`src/main/java/com/milk/order/module/**/entity/*.java`）按 7 个领域包组织，字段取自代码实体，标注主要关联关系 |
| 部署图 | `部署图.puml` / `部署图.png` | 家长小程序 / 管理端浏览器 → Nginx(80) → Spring Boot(8090) → MySQL(3306)，含 JWT 过滤器、分层、定时任务、微信支付模拟链路 |

## 与既有文档的关系

- **时序图、状态机**：已含于 `docs/基线文档/核心链路图.md`（下单→配送时序、订单状态机 1→2→3→4/5、配送任务状态机 1→2→3/4），本目录不再重复。
- **角色与权限**：4 角色（ADMIN/TEACHER/DELIVERY/PARENT）以 `docs/基线文档/接口文档.md`「数据权限」「接口级角色控制」两表为准。
- **部署拓扑**：与 `docs/基线文档/系统架构.md` 一致。

## 修改方式

改 `*.puml` 后重新渲染（Windows 下需 Java）：

```bash
java -jar plantuml.jar -charset UTF-8 用例图.puml 类图.puml 部署图.puml
```

`plantuml.jar` 可自 GitHub release（plantuml/plantuml v1.2024.8）或 Maven 中央仓库获取。
