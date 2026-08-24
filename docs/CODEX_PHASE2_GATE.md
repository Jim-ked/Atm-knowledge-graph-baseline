# Codex Phase 2 Gate（历史验收记录）

> 本文件记录早期 Phase 2 的 Neo4j Gate 目标和执行约束，不代表当前项目只完成到 Phase 2。当前代码已经继续实现 JDBC、同步运行时、HTTP API、named query、变化关联组件和 G6 Viewer。当前验证状态请看 `docs/VALIDATION_STATUS.md`。

本 Gate 当时用于确认：

1. Maven 普通测试；
2. Jena / POI 正式依赖；
3. Neo4j 5.26 Community schema/constraint/index；
4. fullRebuild 幂等；
5. 缺失关系端点显式失败；
6. 一跳；
7. K-hop 完整诱导子图；
8. PATH；
9. changed snapshot 的属性、关系、新增、删除投影更新。

当时要求使用独立、可丢弃的 Neo4j 5.26.x Community 实例，并显式设置：

```text
ATMKG_NEO4J_URI
ATMKG_NEO4J_DATABASE
ATMKG_NEO4J_USERNAME
ATMKG_NEO4J_PASSWORD
```

这些连接边界至今仍有效。

原 Gate 的“不进入 REST/API 阶段”等限制只是阶段性约束，目前已不再适用；不要据此回退当前实现。
