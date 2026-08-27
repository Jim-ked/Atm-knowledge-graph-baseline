# 对外接口契约

## 1. 原则

最终调用端技术栈未知，因此对外冻结的是稳定 UID、查询语义和 GraphDTO 数据契约，不绑定 Vue/React；客户端不得依赖 Neo4j internal id 或 elementId。Viewer 另有受控的只读 Cypher 入口，但它不进入 QueryService 主链。

HTTP 层只是轻量适配器；查询语义仍由 `QueryService` 负责。

## 2. 当前 HTTP 入口

默认配置：

```text
host: 127.0.0.1
port: 18080
basePath: /api/v1
```

当前已开放：

```text
GET  /api/v1/health
GET  /api/v1/schema
GET  /api/v1/entities/{uid}
POST /api/v1/entities/lookup
POST /api/v1/graph/one-hop
POST /api/v1/graph/k-hop
POST /api/v1/graph/path
POST /api/v1/graph/query
POST /api/v1/graph/named
POST /api/v1/graph/cypher
```

统一 `/graph/query` 当前只接受：

```text
ENTITY
NEIGHBORS
K_HOP
PATH
```

`/entities/lookup` 只接受 `{ "key": "ZBAA" }` 或额外提供可选 `classIri`。`key`、`classIri` 去除首尾空白后传入定位服务；服务按当前 project 的 canonical `KGEntity.kg_caption` 去除首尾空白后做大小写不敏感的精确业务键匹配，不做 contains、全文搜索、Class 猜测、fallback 或其它输入改写。返回 GraphDTO，空结果是 200，超过 50 项显式返回 `RESULT_TOO_LARGE`。内部 ENTITY / NEIGHBORS / K_HOP / PATH 仍严格使用稳定 UID。

`/graph/named` 只接受 `{ "queryId": "...", "startUid": "..." }`，构造已有 `QuerySpec.Type.NAMED` 后继续交给 `TemplateAwareQueryService` / `QueryTemplateRegistry`，不接受 raw Cypher。

`/graph/cypher` 只接受 `{ "cypher": "MATCH ... RETURN ..." }`。服务端先执行 Neo4j 官方 `EXPLAIN`，仅当 `ResultSummary.queryType()` 为 `QueryType.READ_ONLY` 时才执行原查询，并额外使用 `AccessMode.READ` 会话。输入 `EXPLAIN`/`PROFILE` 会明确拒绝；该入口不接受 params、raw 查询模板或写操作。响应为 `CypherResultDTO`：`schemaVersion`、有序 `columns`、JSON-safe `rows`、`graph` 和 `meta`。标量/表格查询正常返回 rows 和空 GraphDTO；Node、Relationship、Path 及其 List/Map 嵌套值会同时进入 rows，并在满足 canonical 图投影条件时进入 graph。Node/Relationship 的结构化 rows 仅在存在 `kg_uid` 时输出 `uid`，不输出 Neo4j internal elementId 或关系端点 elementId。表格超过固定 1000 行会显式返回 `RESULT_TOO_LARGE`，不静默截断。

`/schema` 保留 `schemaVersion`、`classes`、`objectProperties`，并增加 `datatypeProperties`、`classLabels`、`datatypePropertyLabels`、`objectPropertyLabels`。label 缺失时统一回退为 IRI localName。

## 3. GraphDTO

GraphDTO 使用稳定业务 UID。节点对外字段保持：

```text
id
labels
kind
caption
properties
```

关系保持：

```text
id
source
target
type
properties
```

同时包含 `schemaVersion` 与 `meta`。

GraphDTO 不暴露 Neo4j internal id / elementId，也不加入 G6 或其他 Viewer 专用字段。

## 4. 查询语义

- ENTITY：按稳定 UID 查询实体；
- NEIGHBORS：完整一跳，可限制关系类型、类过滤和方向；
- K_HOP：在给定深度内返回到达节点和这些节点间原图关系的完整诱导子图；
- PATH：查询两实体之间路径；
- NAMED：由外置 query template 展开为受控 QuerySpec，通过独立 `/graph/named` HTTP 入口调用。

不得通过 top-N、随机采样或静默 LIMIT 改变完整查询语义。结果超过 `config/api.yaml` 中节点/关系上限时返回显式 `RESULT_TOO_LARGE`，而不是截断。

## 5. API 校验与错误

POST 请求要求 `Content-Type: application/json`，未知请求字段会被拒绝。请求体大小、depth 和结果规模均受 `config/api.yaml` 限制。

主要错误语义包括：

- 非法请求：400；
- 实体不存在：404；
- 结果超过配置上限：413；
- Neo4j health 不可用：503；
- 图查询/服务执行失败：500。

health 返回 Neo4j 可用性；当 Neo4j 不可用时服务返回 `DEGRADED`。

## 6. 当前不属于 HTTP 契约的能力

当前 HTTP API 不直接提供：

- ChangeEvent 写入端点；
- sync/fullRebuild/resync 管理端点；
- association outward sink；
- 空间推理端点。

Viewer Cypher 允许标量、表格和图结果在一次执行中同时返回。graph 仅保留带 `KGEntity`、当前 `kg_project` 和稳定 `kg_uid` 的正式节点；关系必须能解析到两个已返回的正式节点并带稳定 `kg_uid`。贡献节点、其它 project、Neo4j internal id/elementId 均不会进入 GraphDTO；仅返回关系但未返回端点时 rows 正常，graph 不强造端点。结果由固定 1000 行表格上限、用户 Cypher 中的 `LIMIT` 与服务端节点/关系硬上限共同控制，超限显式返回 `RESULT_TOO_LARGE`，不静默截断。

同步控制目前通过内部运行时、polling 和 `tools/sync.cmd` 完成，不应把历史文档中“HTTP 必须暴露 ChangeEvent/resync”继续当成当前已实现契约。

## 7. Viewer 与 basePath

服务端 `basePath` 来自 `config/api.yaml`，但当前 Viewer 的 `viewer/src/core/api-client.js` 默认仍使用 `/api/v1`，并在前端独立限制 depth 为 1..8。

因此修改服务端 `basePath` 或 `maxDepth` 时，如果仍使用当前 Viewer，需要同步检查 Viewer 的 API client；两者目前不是同一动态配置源。
