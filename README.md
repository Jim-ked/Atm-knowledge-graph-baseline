# 航管知识图谱

本仓库从零实现一个独立的知识图谱构建、持续更新与查询能力模块。当前唯一现行本体为 `ontology/atm_knowledge_graph.ttl`；结构化源数据通过人工维护的 `mapping/字段映射.xlsx` 映射为稳定实体、属性和关系，后续写入 Neo4j，并通过技术栈无关 GraphDTO/API 对外提供查询结果。

当前开发阶段已经进入 Phase 1：本体读取、人工映射读取/刷新、确定性测试数据、纯内存 MappingEngine。Neo4j、REST、Viewer 和正式 JDBC 尚未进入本阶段。

开发边界和后续目标见：

- `AGENTS.md`
- `docs/PROJECT_BASELINE.md`
- `docs/PHASE1_IMPLEMENTATION.md`

测试数据使用 Java 生成器从零生成结构化 CSV，参考 ATMONTO/ATMGRAPH 已验证的模拟数据组织方法，但不使用其 TTL 作为本项目 Schema，也不直接向 Neo4j 导入 RDF。
