# 对外接口契约

最终调用端技术栈未知，因此当前冻结的是逻辑能力和数据契约，不绑定 Vue、React 或其他宿主框架，也不提前锁死 REST 框架。

查询请求由 `QuerySpec` 表达，查询响应统一为 `GraphDTO`。外部调用端只使用稳定业务 UID，不接触 Neo4j 内部 ID，也不向 Neo4j 直接提交业务 Cypher。

API 实现至少需要暴露：
- 实体/完整一跳/完整 K 跳/路径/命名查询；
- 增量 `ChangeEvent` 入口；
- 单记录 resync；
- 必要的全量同步与同步状态/错误反馈能力。

`ChangeEvent` 只说明哪个源对象发生变化；`SyncService` 依据 sourceId/sourceKey 回读权威源数据，再执行 Mapping 和 GraphStore 更新。DELETE 场景必须以稳定 sourceKey 仍能定位既有投影。

具体 HTTP 路径、认证方式和 REST 框架在 API 实现阶段确定，但不得改变 `QuerySpec / GraphDTO / ChangeEvent` 的职责边界。
