# 当前验证状态

当前环境已经实际完成：

- 当前唯一 `ontology/atm_knowledge_graph.ttl` 可解析；已知基线为 18 Classes、73 DatatypeProperties、12 ObjectProperties。
- 领域无关 Core、Fixture、MappingEngine、SyncService 等不依赖第三方库的主源码已使用 `javac --release 17` 编译。
- 固定 seed 测试数据同时包含 base / changed 两个真实结构化快照，以及新增、修改、引用变化、删除四种 ChangeEvent。
- 纯 Java 回归已经真实执行：
  - 同一 source object 内向后引用的关系不依赖源记录顺序；全量同步采用“实体端点先行、完整投影后写”的两遍流程。
  - 跨 source object 的 fullRebuild 即使对象顺序颠倒，也先完成全部实体端点再写关系。
  - changed snapshot 的属性更新、关系改挂、新增实体、删除实体均通过；旧关系不会残留。

已经写入但当前环境尚无法真实执行的 Gate：

- Jena `OntologyService`、POI `MappingRegistry` 的 Maven/JUnit 全量测试。
- Neo4j `GraphStore`、`QueryService` 的真实 Driver 编译与 Neo4j 5.26 实例集成。
- `Phase2Neo4jCheckMain` 已作为一次性验收入口，要求验证：
  - schema/constraint 建立；
  - fullRebuild 重复执行计数不变；
  - 一跳查询；
  - K-hop 返回完整诱导子图；
  - 最短路径；
  - 缺失关系端点显式失败；
  - UPSERT/关系改挂/INSERT/DELETE 增量更新。

当前容器没有 Maven、Neo4j 服务和正式第三方依赖，因此不得把上述 Neo4j Gate 标记为已通过。下一步应在联网开发机/Codex 环境执行，而不是继续在未验证的 GraphStore 之上叠加 API 实现。
