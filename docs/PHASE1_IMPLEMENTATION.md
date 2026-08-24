# Phase 1 实现说明（历史阶段记录）

> 本文件记录早期 Phase 1 的阶段性范围，不代表当前项目状态。当前实现已经进入 Neo4j、JDBC、同步运行时、HTTP API、named query 和 G6 Viewer 阶段。当前状态请看 `README.md`、`docs/PROJECT_BASELINE.md`、`docs/VALIDATION_STATUS.md`。

本阶段当时固定范围为：

`atm_knowledge_graph.ttl -> OntologySchema -> 字段映射.xlsx -> MappingCatalog -> SourceRecord -> GraphEntity/GraphRelationship`

当时已实现：

- `JenaOntologyService`：读取唯一现行 TTL，提取 Class、DatatypeProperty、ObjectProperty、label、domain/range、subClassOf。
- `PoiMappingRegistry`：读取三张人工映射表；新增本体项刷新为 `[待映射]`；不覆盖已填内容；未知/失效映射显式报错。
- `DeterministicIdentityResolver`：使用项目命名空间 + URI 编码生成稳定 UID，不依赖 Neo4j 内部 ID。
- `DefaultMappingEngine`：领域无关执行实体、属性和源记录中明确定位字段的关系映射。
- Fixture/CSV 测试链：证明测试数据经过 SourceAdapter/MappingEngine 主链。

当时“尚未实现 Neo4j/REST/Viewer/JDBC”等描述只适用于 Phase 1，已经不适用于当前代码。

该阶段依赖基线中的 JDK 17 / Jena 5.x / POI 5.x 原则仍沿用，但准确版本以当前 `pom.xml` 为准。
