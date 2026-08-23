# Phase 1 实现说明

本阶段已经开始实际编码，范围固定为：

`atm_knowledge_graph.ttl -> OntologySchema -> 字段映射.xlsx -> MappingCatalog -> SourceRecord -> GraphEntity/GraphRelationship`

已实现：

- `JenaOntologyService`：读取唯一现行 TTL，提取 Class、DatatypeProperty、ObjectProperty、label、domain/range、subClassOf。
- `PoiMappingRegistry`：读取三张人工映射表；新增本体项刷新为 `[待映射]`；不覆盖已填内容；未知/失效映射显式报错。
- `DeterministicIdentityResolver`：使用项目命名空间 + URI 编码生成稳定 UID，不依赖 Neo4j 内部 ID，也不使用哈希清单。
- `DefaultMappingEngine`：领域无关执行实体、属性和“源记录中明确定位字段”的关系映射；支持简单字段路径和少量必要类型转换。
- `FixtureDataGenerator`：参考 ATMONTO/ATMGRAPH 的测试数据组织思路，从零生成结构化航空 fixture；支持固定 seed、small/medium/large、增删改情境。
- `CsvFixtureSourceAdapter`：开发测试用结构化源适配器，用于证明测试数据没有绕过 SourceAdapter/MappingEngine 主链。

当前故意没有实现：Neo4j GraphStore、REST、Viewer、正式 JDBC、复杂路线相邻推导、空间关系推导、关系挖掘。

## 依赖版本

- JDK target: 17
- Apache Jena: 5.6.0（Jena 6 已要求 Java 21，因此当前不选 Jena 6）
- Apache POI: 5.5.1
- JUnit Jupiter: 5.11.4

这些依赖只写入 Maven 工程，不把依赖包提交到源码仓库。
