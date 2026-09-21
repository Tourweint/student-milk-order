# UML 图（补充交付）

> 对应任务清单第 8 项（用例图）、第 12 项（类图、部署图）
> 首版绘制：2026-09-15 ｜ **2026-09-21 起工具链切换：PlantUML(Java 渲染) → draw.io（`*.drawio` 源文件）**

## 图件清单

| 图 | 当前文件 | 说明 |
|---|---|---|
| 用例图 | `学生奶订购系统-用例图.drawio`（**drawio 版已完成**；旧 `用例图.puml/png` 留档对照） | 4 类参与者 × 33 个用例，覆盖认证、班级学生、奶品配额、订单支付、配送、营养、可视化、系统管理 9 个模块，与 `docs/基线文档/接口文档.md` 逐条对应 |
| 类图 | `学生奶订购系统-类图.drawio`（**drawio 版已完成**；旧 `类图.puml/png` 留档对照） | 23 个实体类（22 个 `module/**/entity` 实体 + 1 个过程层台账实体 `ProcessTransitionLog`）按 6 个领域包组织，字段取自代码实体，标注主要关联关系 |
| 部署图 | `学生奶订购系统-部署图.drawio`（**drawio 版已完成**；旧 `部署图.puml/png` 留档对照） | 家长小程序 / 管理端浏览器 → Nginx(80) → Spring Boot(8090) → MySQL(3306)，含 JWT 过滤器、分层、定时任务、微信支付模拟链路，另补过程层/可靠性层横切标注 |

## 修改方式（draw.io，2026-09-21 起的统一做法）

1. **看图**：`*.drawio` 双击用 draw.io Desktop / VS Code 插件打开，或到 <https://app.diagrams.net> 打开；AI 会话里可用 drawio MCP 的 `open_drawio_xml` 直接在浏览器预览。
2. **改图**：直接编辑 `*.drawio`（mxGraph XML，纯文本可 diff）。新增图件一律产出 `*.drawio`，不再新增 PlantUML 源；需要 PNG/导出时在 draw.io 里「文件 → 导出」。
3. **风格约定**（新图必须遵守，迁移旧图时对齐）：
   - 页面 1600×900 横向画布，左上角标题 + 一行副标题（口径来源 / 与哪份文档一致 / 日期）；
   - 容器分组用圆角容器（`container=1`）：外部系统/终端灰、本系统蓝（`#2F5496` 系）、数据绿（`#82B366` 系）、外部服务橙（`#D79B00` 系）；横切机制层用虚线容器；
   - 连线统一 `edgeStyle=orthogonalEdgeStyle;rounded=1`，主干 `#556580`，联动/异步虚线 `#8EAADB`，线上写关键协议或语义；
   - 中文标签直接写，不要用代码符号替代业务含义；一个图只讲一件事（拓扑归拓扑、状态机归状态机）。
4. **AI 协作**：主会话可调用 drawio MCP 工具——`drawio__open_drawio_xml`（生成/预览）、`drawio__search_shapes`（查官方样式库）、`drawio__list_pages` / `drawio__get_page` / `drawio__set_page`（多页读写）。生成前先用 `search_shapes` 找官方形状，不要手造 `shape=...` 样式。

## 迁移状态与遗留

- [x] 部署图 → drawio（2026-09-21，`学生奶订购系统-部署图.drawio`）
- [x] 用例图 → drawio（2026-09-21，`学生奶订购系统-用例图.drawio`）
- [x] 类图 → drawio（2026-09-21，`学生奶订购系统-类图.drawio`）
- PlantUML 历史：`.puml` 源文件已全部迁移，暂留档对照；确认新版无问题后可删除。`.png` 仍为 2026-09-15 旧渲染（**不再用 Java 重新渲染**）。
- 时序图、状态机：仍含于 `docs/基线文档/核心链路图.md`（Mermaid，GitHub 可直接渲染，**保持 Mermaid 不迁**）。

## 与既有文档的关系

- **角色与权限**：4 角色（ADMIN/TEACHER/DELIVERY/PARENT）以 `docs/基线文档/接口文档.md`「数据权限」「接口级角色控制」两表为准。
- **部署拓扑**：与 `docs/基线文档/系统架构.md` 一致。
- 工具链切换的决策与取舍见 `docs/变更记录/2026-09-21-绘图工具切换drawio.md`。
