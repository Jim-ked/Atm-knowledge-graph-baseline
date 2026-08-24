# 配置说明

当前配置文件按职责分离，但不是每个 YAML 都是运行时动态入口。不要因为文件存在就假设修改后服务一定生效。

- `sources.yaml`：正式数据源物理读取配置。`SyncRuntimeAssembler` 直接加载。
- `sync.yaml`：同步策略与 polling 配置。`SyncRuntimeAssembler` 直接加载。
- `api.yaml`：HTTP host/port/basePath/schemaVersion/depth/请求与结果规模上限。`KgServiceMain` 直接加载。
- `project.yaml`：当前主要作为项目/路径索引；关键 projectId、namespace、路径在 Java 中仍有固定值，单独修改本文件不会迁移运行时。
- `neo4j.yaml`：当前记录固定环境变量名称；实际连接由 `ATMKG_NEO4J_URI`、`ATMKG_NEO4J_DATABASE`、`ATMKG_NEO4J_USERNAME`、`ATMKG_NEO4J_PASSWORD` 直接读取。
- `query.yaml`：当前是查询完整性基线声明，不是 QueryService 运行时开关。
- `ontology.yaml`：当前是正式 TTL/refresh 规则说明；服务实际直接加载 `ontology/atm_knowledge_graph.ttl`。

具体航空字段、实体、属性和关系语义不进入这些 YAML，统一由当前本体和 `mapping/字段映射.xlsx` 维护。

接数据、改 API、开启 polling 或迁移 namespace 前，请先按 `tools/START_HERE.txt` 中对应任务操作。尤其不要仅修改 `project.yaml`、`neo4j.yaml`、`query.yaml`、`ontology.yaml` 后假定运行时已经改变。
