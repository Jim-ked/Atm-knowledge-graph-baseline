# 配置边界

运行配置按职责分离：
- `project.yaml`：项目与路径总入口；
- `ontology.yaml`：当前唯一现行本体与映射刷新规则；
- `sources.yaml`：结构化数据源；
- `neo4j.yaml`：Neo4j 连接；
- `sync.yaml`：全量、增量、补偿与重同步；
- `query.yaml`：图查询完整性约束；
- `api.yaml`：技术栈无关 API 外壳。

具体航空字段及关系语义不进入 YAML，统一由 `mapping/字段映射.xlsx` 维护。
