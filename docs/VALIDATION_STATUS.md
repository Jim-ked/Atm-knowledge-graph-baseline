# 当前验证状态

本文件记录当前代码基线的验证状态，不再代表早期 Phase 1/2 的阶段性限制。历史 Gate 文档仍保留在 `docs/PHASE1_IMPLEMENTATION.md`、`docs/CODEX_PHASE2_GATE.md`，但应作为历史记录阅读。

## 1. 当前回归

截至当前基线，已报告并通过：

- Maven：176 tests，0 failures，0 errors，4 skipped，BUILD SUCCESS；
- Viewer：39/39 tests passed；
- Viewer build：成功；
- G6 正式浏览器 Gate：默认 G6、展开/收起、pin/unpin、标签模式通过，浏览器 console error 为 0；
- `git diff --check`：通过。

## 2. 已完成的真实 Neo4j 验证

开发过程中已经完成 Neo4j 5.26 Community 的真实实例 Gate，覆盖：

- schema / constraint / index 建立；
- fullRebuild 与重复执行幂等；
- 缺失关系端点显式失败；
- 实体、一跳、K-hop 完整诱导子图、路径查询；
- 属性修改、关系改挂、新增、删除的投影更新；
- API health/entity/entity-lookup/one-hop/k-hop/path/named/cypher/schema 与错误模型；
- 稳定 `kg_uid`，不暴露 Neo4j internal id / elementId；
- 多来源实体 contribution 的 canonical 重建与冲突回滚。
- deleteProjection 在同一 Neo4j write transaction 内取得 UID before-state 并删除投影；
- 删除一个实体 contribution 后 canonical 仍可由其他来源保留；
- 关系-only 投影删除时返回关系 UID 和两个端点 anchor，重复 DELETE 返回空摘要。

早期文档中“Neo4j Gate 尚未执行”“当前没有 Maven/Neo4j”已经失效。

## 3. 当前 Source / Mapping 验证

已实现并通过的主要能力包括：

- Excel 通用读取；
- `row` / `group_first` / `adjacent_next`；
- 多文件、Sheet、分组与排序；
- `SourceRecord.sourceKey` 稳定生成；
- JDBC readAll / readByKey / scanChangedSince；
- JDBC iterator 异常中断关闭；
- TTL + 三 Sheet mapping 校验；
- 稳定实体/关系 UID；
- 同一实体多来源 contribution；
- Excel/JDBC SourceRecord -> MappingResult 人工预览工具。

两个 preview 工具都支持 Excel/JDBC；它们是开发核验工具，不代表真实 Oracle 连通验证。

## 4. 当前同步验证

已验证：

- 单记录 resync；
- 单入口 fullSync；
- 全项目 fullRebuild；
- compensateSince；
- JDBC polling watermark 推进；
- checkpoint JSON 持久化、重启恢复，以及 checkpoint 优先于 initialWatermark；
- lookback 从 checkpoint 向前回看并允许重复 ChangeEvent；
- 成功批次按最大 sourceTimestamp 推进，空扫描不按系统时间推进；
- consumer / mapping / GraphStore 失败时 watermark 不错误推进；
- checkpoint 保存失败时本轮失败且水位不推进；
- checkpoint JSON 损坏明确失败，多 scope 保存互不覆盖；
- JDBC 流式 iterator 在正常和异常路径关闭；
- recent event cache 有界，失败事件可以重试；
- 显式 DELETE notice 携带 GraphStore 删除事务返回的 entity/relationship/anchor UID 摘要；
- deleteProjection 失败时不发布 notice，eventId 不会错误标记成功；
- NeighborhoodProjector 仍返回 SKIPPED_DELETE，AssociationProjector 仍不处理 DELETE；
- GraphChange 失败时 polling checkpoint 不推进，下轮再次 replaceProjection 并重试 GraphChange；
- 服务 sources 为空时进入 Query/API-only，不自动执行 fullRebuild。

## 5. 当前查询与关联验证

已验证：

- QueryTemplateRegistry 外置命名模板；
- `airport-direct-flights`；
- `segment-route-structures`；
- `planned-route-flights`；
- GraphChangeAssociationProjector 的一级关联 + 最多一次 continuation；
- Route/ScheduledFlightRoute 没有后续规则时自然停止；
- PlannedFlightRoute 可继续关联到 Flight；
- GraphChangeProcessor 固定按 Neighborhood -> Association -> result Consumer 执行；
- 正式 KgServiceMain 加载 change-query-rules，并把 Processor 注入 SyncRuntime；
- UPSERT 可从 fake SourceAdapter 经 DefaultSyncService 到达统一结果 Consumer；
- DELETE 统一结果保留 before-state UID，同时保持 Neighborhood/Association 跳过；
- Projector 与结果 Consumer 异常均向上传播；
- 控制台 Reporter 只打印状态和计数，不打印 GraphDTO 或 properties。

正式服务当前只把 GraphChange 统一结果输出为一行控制台摘要。该出口不持久化，也不是可靠 outward sink；
`tools/sync.cmd` 的人工同步链不执行 GraphChange。规则中的 queryId 尚无启动期交叉校验，未知 ID 在首次执行时
明确失败。

## 6. Viewer 验证

正式 Viewer 已收敛为 G6-only。Sigma、Cytoscape、Graphology 及其实验依赖、adapter、workbench 和 gate 已从正式实现中删除。

当前 Viewer 保留实体/K-hop/路径查询、节点与关系详情、标签模式、展开/收起、pin/unpin、重新平衡等已验证交互，并已完成 Graph/Table/Raw 三种结果视图与业务键 lookup 接线。
只读 Cypher 后端已验证：通过 `EXPLAIN` + `QueryType.READ_ONLY` 判定后执行，一次返回 table rows 与 GraphDTO graph；标量结果正常返回空 graph，写查询、贡献节点和其它 project 均有明确边界。Viewer 已真实联调 Cypher scalar/table、Cypher graph，以及 `/schema` 中文 class、关系和属性 label。

## 7. 尚未验证/未实现

以下内容不应标记为“已通过”：

- 真实业务 Oracle 数据库版本、HOST/PORT/SERVICE_NAME、Schema、表/视图、业务键和 watermark 字段尚待确认，真实连通与长期生产运行尚未验证；
- JDBC hard DELETE 自动发现；
- 超出 lookback 窗口的极晚记录完整保障；
- polling exactly-once；
- RouteSegment-Airspace 真实空间拓扑与三维高度重叠；
- 派生关系正式持久化/reconcile/ownership；
- GraphChange durable outward sink / 业务消息协议；
- 多来源关系 contribution；
- 银河麒麟 V4.0.2 最终实机部署闭环。

这些均属于后续阶段，不应通过 synthetic test 或配置声明提前标记为完成。
