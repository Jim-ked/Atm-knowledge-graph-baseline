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

工作簿固定三张 Sheet：

- 实体映射：确定源记录如何形成某类实体及其稳定业务主键；
- 属性映射：确定源字段如何写入本体数据属性；
- 关系映射：确定源记录中已有定位信息如何形成对象属性关系。

本体新增术语可以通过 refresh 追加 `[待映射]` 行，但 `[待映射]` 不代表已经生效，仍需人工填写并重新验证。

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
- JDBC polling：按配置 scope 周期发现 `watermark > 当前值` 的 UPSERT 变化。

`fullRebuild` 是显式人工危险操作，服务启动不会因 `initialFullImport=true` 自动执行清图。

当前 polling watermark 只保存在进程内；重启后从 `sync.yaml` 的 `initialWatermark` 重新开始。timestamp polling 不自动发现 hard DELETE，也未解决相同时间戳晚到记录。

事件重复抑制只使用有界进程内 recent-event cache，不提供 exactly-once 保证。

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

当前模板只允许现有 `QuerySpec` 的受控子集，不接受 raw Cypher。

`queries/change-query-rules.yaml` 表达 `GraphNodeDTO.kind -> queryId`。`GraphChangeAssociationProjector` 已能执行“anchor ENTITY -> rules -> NAMED -> 最多一次 continuation”的轻量关联查询，但当前尚未装配进 `KgServiceMain/SyncRuntime`，也没有 outward sink。

因此当前可以验证关联查询组件，但不能宣称变化后会由正式服务自动向外推送关联结果。

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

并非所有 YAML 都是当前运行时动态配置入口：

- `sources.yaml`：正式同步装配直接读取；
- `sync.yaml`：正式同步装配直接读取；
- `api.yaml`：服务直接读取；
- `query-templates.yaml`：服务直接读取；
- `project.yaml`：当前主要是项目/路径索引，关键值仍有 Java 固定值；
- `neo4j.yaml`：当前主要记录固定环境变量名称，连接实际由 `ATMKG_NEO4J_*` 读取；
- `query.yaml`：当前是查询语义基线声明，不是运行时开关；
- `ontology.yaml`：当前是本体/refresh 基线说明，服务实际直接读取固定 TTL 路径。

修改配置前应以 `tools/START_HERE.txt` 和当前代码注释为准，不能根据文件名猜测动态生效范围。

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
- watermark 持久化；
- 空间拓扑派生；
- 多来源关系 contribution；
- association outward sink；
- 运行时多版本本体。
