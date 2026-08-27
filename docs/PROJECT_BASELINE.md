# 项目执行基线

## 1. 定位

本项目不是知识图谱管理平台，也不绑定最终业务前端。目标是形成一个可以独立嵌入其他系统的“结构化数据 -> 知识图谱 -> 查询/API”能力模块，并保留后续派生计算扩展点。

源业务数据是权威事实，Neo4j 是可重建的图投影。普通业务字段、类和关系变化优先通过当前本体与人工映射吸收，Java 通用主链不硬编码机场、航路、航班等具体字段语义。

## 2. 固定主链

```text
SourceAdapter
  -> SourceRecord
  -> MappingEngine
  -> GraphEntity / GraphRelationship
  -> GraphStore
  -> Neo4j

TriggerAdapter
  -> ChangeEvent
  -> SyncService
  -> 回读 SourceAdapter 权威记录
  -> MappingEngine
  -> GraphStore
  -> GraphChangeNotice
  -> GraphChangeProcessor
  -> Neighborhood / Association
  -> GraphChangeProjectionResult
  -> 控制台摘要

QueryService
  -> GraphDTO
  -> HTTP API / Viewer / 外部调用端
```

`ChangeEvent` 只说明“哪个源记录发生变化”，不能把事件载荷当权威业务数据。增量处理必须回读当前源记录后重新映射。

## 3. 语义输入

当前唯一正式本体为：

```text
ontology/atm_knowledge_graph.ttl
```

运行时不提供多版本本体切换，历史由 Git 保存。

人工语义映射唯一入口为：

```text
mapping/字段映射.xlsx
```

工作簿固定三张运行 Sheet，并有一张不参与加载的辅助 Sheet：

- 实体映射：确定源记录如何形成某类实体及其稳定业务主键；
- 属性映射：确定源字段如何写入本体数据属性；
- 关系映射：确定源记录中已有定位信息如何形成对象属性关系。
- 本体参考：由唯一正式 TTL 重建，供人工查阅 Class/Property、label、domain/range 和完整 IRI。

refresh 只重建“本体参考”，不会向三张正式 Mapping Sheet 追加行或覆盖人工配置。运行时只支持当前表头。

## 4. 数据源边界

`SourceAdapter` 只负责物理读取，不决定本体语义。

当前正式支持：

- Excel：`row`、`group_first`、`adjacent_next`；
- JDBC：受控表/视图、稳定 `keyFields`、可选 `watermarkField`。

`sources.yaml` 描述“去哪里读、怎样组成 SourceRecord”；本体类、属性、关系 IRI 不应写入该配置。

当前正式关系型源数据库为 Oracle，runtime driver 为 `ojdbc17`；`JdbcSourceAdapter` 本身仍以标准 `java.sql` 实现，不是 Oracle 专用 Adapter。Oracle 只允许出现在 `infra/source/JDBC` 配置和 runtime 叶子依赖。新增普通表/视图不应复制一套新的 Java adapter；以后更换其他 JDBC 数据库时，原则上只替换 driver、URL、源对象配置和对应 runtime driver，只有 JDBC 通用读取机制本身出现真实缺口时才扩展代码。

## 5. 稳定身份与多来源实体

外部图身份使用稳定 `kg_uid`，不使用 Neo4j internal id / elementId。

实体 UID 由“类语义 + 稳定业务键”确定，禁止使用文件名、目录、Sheet 名、数据库表名等物理定位作为实体身份。

同一实体可以由多个 source record / sourceObject 提供属性。Neo4j 内部通过 `KGEntityContribution` 保存来源贡献，再确定性重建 canonical `KGEntity`。`KGEntityContribution` 是内部技术状态，不得进入 QueryService、GraphDTO、Viewer 或业务节点统计。

当多个来源对同一 canonical 属性给出不同值时，当前策略是显式冲突并回滚，而不是“最后写入覆盖”。多来源关系 ownership 尚未实现。

## 6. 同步

当前支持：

- `resync`：按 sourceId/sourceObject/sourceKey 回读一条权威记录并替换其当前投影；
- `fullSync`：同步单个 sourceObject，不清空整个项目图；
- `fullRebuild`：先清当前项目图，再按全部正式 sourceObject 重建；
- `compensateSince`：从指定时间做变更补偿扫描；
- JDBC polling：按配置 scope 周期发现 `watermark > effectiveSince` 的 UPSERT 变化；
  `effectiveSince = checkpoint - lookback`。

`fullRebuild` 是显式人工危险操作，服务启动不会因 `initialFullImport=true` 自动执行清图。

polling checkpoint 持久化在 `runtime/state/polling-checkpoints.json`。启动时已有有效 checkpoint
优先于 `sync.yaml` 的 `initialWatermark`；initialWatermark 只用于该 scope 首次运行。整轮记录和
ChangeEvent consumer 成功后，以本轮最大 `SourceRecord.sourceTimestamp` 原子保存并推进；空扫描或任一
读取、同步、写 checkpoint 失败都不推进。JSON 损坏会明确启动/读取失败，不会静默回退。

polling 采用 at-least-once：lookback 会重复扫描部分记录，由 stable sourceKey、stable UID 和
replaceProjection 吸收，不提供 exactly-once。有限 lookback 只能降低同时间戳/短时晚到漏读风险；
timestamp polling 仍不自动发现 hard DELETE，也不能保证窗口以外的极晚记录。

事件重复抑制只使用有界进程内 recent-event cache，不提供 exactly-once 保证。

上游明确提供 `ChangeEvent.DELETE + SourceRef` 时，`GraphStore.deleteProjection` 会在同一个 Neo4j write
transaction 中先取得该 SourceRef 的 `GraphProjectionSnapshot` UID 摘要，再把它的当前投影替换为空。
摘要中的 entityUids 表示原 contribution，即使其他来源仍使 canonical 实体继续存在也会保留；
relationshipUids 只包含该 SourceRef 直接拥有的关系，anchorEntityUids 还包含这些关系的两端。
删除成功后 `GraphChangeNotice.DELETE` 携带该摘要；重复 DELETE 或原本无投影时返回空摘要。

这不表示 JDBC polling 已能发现 hard DELETE。GraphStore 提交与 notice listener 也不是持久化 outbox；
当前只保证成功返回的 before-state 与删除事务一致，不保证 notice exactly-once 或绝不丢失。

正式服务的 GraphChange Projector 或结果 Consumer 失败会继续向上传播；polling 因而不会为该失败批次推进
checkpoint，下轮允许再次回读、replaceProjection 和处理 GraphChange，保持 at-least-once。人工维护入口
`tools/sync.cmd` 使用不带 listener 的旧装配重载，不执行 GraphChange，这是为了避免人工 fullSync 自动产生大量
关联查询的有意边界。

## 7. 查询与 GraphDTO

查询主能力包括：

- ENTITY；
- 完整一跳 NEIGHBORS；
- 完整 K_HOP；
- PATH；
- 外置 NAMED query。

完整 K 跳在约束深度内返回到达节点，并返回这些节点之间原图中存在的完整关系诱导子图。不得随机抽样、top-N 或静默 LIMIT；超过服务上限时显式失败。

GraphDTO 只暴露稳定业务 UID、节点 labels/kind/caption/properties 与关系 source/target/type/properties，不泄漏 Neo4j internal id，也不加入 Viewer 专用字段。

## 8. named query 与变化关联

生产查询模板位于：

```text
queries/query-templates.yaml
```

正式 QueryService 模板只允许现有 `QuerySpec` 的受控子集，不接受 raw Cypher。Viewer 另有独立 `/api/v1/graph/cypher` 只读入口，由 `ReadOnlyCypherExecutor` 通过 Neo4j `EXPLAIN`/`QueryType.READ_ONLY` 判定后返回同一 GraphDTO；它不改变 QueryService/QuerySpec 契约。

`queries/change-query-rules.yaml` 表达 `GraphNodeDTO.kind -> queryId`，并由正式 `KgServiceMain` 在启动时严格加载。
文件缺失、重复 kind 或结构非法会使服务启动失败；当前 Registry 没有枚举/contains 接口，因此规则中的未知
queryId 仍在首次执行该规则时由 QueryService 明确失败，不为此扩大 Registry API。

UPSERT 写图成功后，正式进程内链会依次执行 Neighborhood 和“anchor ENTITY -> rules -> NAMED -> 最多一次
continuation”的 Association 查询，再把统一结果交给控制台摘要 Consumer。DELETE notice 继续保留实体、关系和
anchor 的 before-state UID，但现有 Neighborhood 返回 `SKIPPED_DELETE`，Association 返回空列表，不做删除关联
影响推导。当前没有 durable sink、outbox 或业务消息协议，不能把控制台摘要描述为可靠自动推送。

## 9. 派生关系边界

查询只能沿图中已有事实传导。例如已有明确图关系时，可以查询：

```text
RouteSegment
  <- hasSegment - Route / ScheduledFlightRoute / PlannedFlightRoute
PlannedFlightRoute
  <- hasPlannedRoute - Flight
```

当前缺失的关键事实包括 `RouteSegment ↔ Airspace` 空间拓扑关系。该关系未来应由独立派生计算插件根据真实几何、候选过滤、二维/三维拓扑等计算后写回图中，不能由 QueryService 临时猜测。

当前仅保留很薄的 `DerivedRelationPlugin` 扩展边界，不包含空间算法、持久化、ownership 或 reconcile。

## 10. API 与 Viewer

HTTP 服务由轻量 `KgApiServer` 提供，配置位于 `config/api.yaml`。当前 HTTP 开放实体、一跳、K 跳、路径和统一查询接口；NAMED query 尚未直接开放 HTTP。

正式 Viewer 仅保留 G6。Viewer 是独立验证客户端，不是业务系统，也不能反向决定 GraphDTO、QueryService 或数据模型。

## 11. 配置事实

`config/` 当前只保留正式运行时会直接读取的 YAML：

- `sources.yaml`：正式同步装配直接读取；
- `sync.yaml`：正式同步装配直接读取；
- `api.yaml`：服务直接读取；

`queries/query-templates.yaml` 也由服务直接读取，但位于 queries 目录，不属于 `config/`。
`queries/change-query-rules.yaml` 同样由正式服务直接读取，用于 GraphChange Association 规则，也不属于
`config/`。
正式 projectId 和 UID namespace 集中在 `src/main/java/org/atmkg/core/ProjectConstants.java`，两者都不是
普通运行参数。`IDENTITY_NAMESPACE` 与本体 IRI 当前文本可能相同，但用途不同；本体 IRI 仍只从正式
TTL/Mapping 获取，不通过该常量生成。Neo4j 环境变量、唯一正式 TTL 和查询完整性原则见
`docs/固定项与运行配置说明.md`。

修改配置前应以 `tools/START_HERE.txt` 和当前代码注释为准。

## 12. 部署与依赖边界

正式目标环境考虑银河麒麟 V4.0.2 x86_64（glibc 2.23）。系统 Java 8 不替换，优先使用项目私有 JDK 17 + Neo4j 5.26 LTS。

源码、开发资源与部署介质分离。仓库不包含 JDK、Neo4j、Maven 仓、Protégé、离线安装包或部署镜像。

Java 是正式语义核心。Python 仅允许用于外围准备、实验或测试数据生成，不形成第二套 Mapping/UID/关系语义。

## 13. 当前非目标/未完成项

- 知识图谱管理平台；
- 最终业务 UI 技术栈；
- Kafka/Debezium/完整 CDC 平台；
- GDS/n10s/LLM 作为基础闭环硬依赖；
- JDBC hard DELETE 自动发现；
- 空间拓扑派生；
- 多来源关系 contribution；
- GraphChange durable outward sink / 业务消息协议；
- 运行时多版本本体。
