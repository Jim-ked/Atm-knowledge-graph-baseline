# 航管知识图谱

本仓库实现一个独立、低耦合的知识图谱构建、持续更新与查询能力模块。结构化业务数据经 `SourceAdapter` 读取为 `SourceRecord`，再依据当前唯一正式本体 `ontology/atm_knowledge_graph.ttl` 与人工维护的 `mapping/字段映射.xlsx` 生成稳定实体、属性和关系，写入 Neo4j；查询结果统一通过技术栈无关的 `GraphDTO` / HTTP API 输出。

当前代码已经不是早期 Phase 1 原型。现阶段已形成可运行基线：Excel/JDBC 数据源接入、人工语义映射、稳定 UID、Neo4j 投影、全量/单条/补偿同步、JDBC polling、实体/一跳/K 跳/路径查询、外置 named query、变化关联投影组件、G6 Viewer，以及人工检查工具链均已实现。空间拓扑等派生关系计算只保留轻量扩展边界，尚未实现。

## 当前主链

```text
结构化源数据
  -> SourceAdapter
  -> SourceRecord
  -> 当前 TTL + 字段映射.xlsx
  -> MappingEngine
  -> GraphEntity / GraphRelationship
  -> GraphStore
  -> Neo4j
  -> QueryService
  -> GraphDTO
  -> HTTP API / G6 Viewer / 外部调用端
```

变化同步主链：

```text
源记录变化
  -> ChangeEvent
  -> SyncService 回读权威源记录
  -> MappingEngine
  -> GraphStore replaceProjection
  -> GraphChangeNotice
  -> GraphChangeProcessor
  -> NeighborhoodProjector
  -> AssociationProjector
  -> GraphChangeProjectionResult
  -> 控制台摘要
```

正式 `KgServiceMain` 已加载 `queries/change-query-rules.yaml`：UPSERT 写图成功后执行 Neighborhood 与 Association 查询，并输出一行 `[CHANGE]` 摘要。该输出只用于人工观察，不是 durable sink、审计日志或业务消息推送；`tools/sync.cmd` 仍是直接人工同步工具，不走这条 GraphChange 输出链。

## 当前主要能力

- 唯一正式本体：`ontology/atm_knowledge_graph.ttl`；历史版本由 Git 保存，不提供运行时多版本切换。
- 人工语义映射：`mapping/字段映射.xlsx` 的实体映射、属性映射、关系映射是三张运行 Sheet；本体参考仅供人工查阅。
- Excel 数据源：支持 `row`、`group_first`、`adjacent_next` 三种通用记录组装模式。
- JDBC 数据源：保持通用 JDBC Adapter，当前正式源数据库为 Oracle，runtime driver 为 `ojdbc17`；通过标准 JDBC 读取受控表/视图，支持稳定业务键和可选时间水位字段。
- Neo4j 写入：稳定 `kg_uid`，不向外暴露 Neo4j 内部 ID；支持实体 contribution 合并，避免多来源同实体相互覆盖。
- 同步：单条 resync、单入口 fullSync、全项目 fullRebuild、补偿扫描、可选 JDBC polling。
- 查询：实体、完整一跳、完整 K 跳诱导子图、路径、外置 named query。
- API：轻量 HTTP 服务；受控查询输出 GraphDTO，独立只读 Cypher 输出 table rows 与 GraphDTO graph 组成的 CypherResultDTO；Viewer 静态挂载在 `/viewer/`。
- Viewer：正式实现仅保留 G6，已完成 Graph/Table/Raw 结果视图和业务键 lookup；Cypher scalar/table、Cypher graph、schema 中文 label 已通过真实联调。
- 人工工具：source preview、source→mapping preview、同步控制、Review CLI、Neo4j 控制脚本。

## 当前明确边界

以下能力当前尚未完成，不是增加一个 YAML 配置即可启用：

- JDBC hard DELETE 自动发现；
- polling exactly-once，以及超出 lookback 窗口的极晚记录完整保障；
- `RouteSegment ↔ Airspace` 空间拓扑及高度重叠计算；
- 派生关系的正式持久化、ownership 与 reconcile；
- GraphChange 的 durable outward sink / 业务消息协议；
- 多来源关系 contribution；
- 最终业务界面与消息传输方式。

查询层只读取图中已经存在的事实，不在 `QueryService` 中临时计算空间拓扑或制造业务关系。

## 开发入口

第一次接手、接数据、修改映射、开启 polling、同步和排障，请先看：

- `tools/START_HERE.txt`：任务驱动的详细开发/操作手册；
- `review/MANUAL.txt`：人工查询与 Review CLI；
- `mapping/README.md`：字段映射工作簿规则；
- `queries/README.md`：named query 与变化关联配置；
- `docs/PROJECT_BASELINE.md`：当前架构与边界；
- `docs/VALIDATION_STATUS.md`：当前验证状态；
- `docs/API_CONTRACT.md`：HTTP / GraphDTO 对外契约。

日常启动的最短路径：

```text
1. copy tools\env.cmd.example tools\env.cmd 并填写私有 JDK、Neo4j 四变量
2. tools\check.cmd
3. 启动 Neo4j
4. 正式离线运行：tools\runtime.cmd start
5. 访问 /api/v1/health，再打开 /viewer/
```

开发调试仍使用 `tools\service.cmd`；它会调用 Maven compile/exec。正式离线运行使用已构建的
`target\atm-knowledge-graph-1.0-SNAPSHOT.jar`、`target\lib` 和 `viewer\dist`，不依赖 Maven/npm。
具体命令、环境变量、数据源配置和危险操作说明不要从本 README 猜测，统一以 `tools/START_HERE.txt` 和代码当前注释为准。

## 依赖与环境

Java 编译目标为 JDK 17。Neo4j 当前为 5.26 LTS 路线；正式目标环境仍考虑银河麒麟 V4.0.2 x86_64，系统 Java 8 不替换，优先使用项目私有 JDK 17。源码仓库不包含 JDK、Neo4j、Maven 仓、Protégé、部署介质或其他大型第三方分发包。

Viewer 仅在修改/重建前端时需要 Node/npm；正式 Viewer 依赖为 G6 + d3-force，不再维护 Sigma/Cytoscape 运行实现。
