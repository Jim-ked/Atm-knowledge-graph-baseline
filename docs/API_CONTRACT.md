# 对外接口契约

## 1. 原则

最终调用端技术栈未知，因此对外冻结的是稳定 UID、查询语义和 GraphDTO 数据契约，不绑定 Vue/React，也不允许客户端直接依赖 Neo4j internal id、elementId 或业务 Cypher。

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
POST /api/v1/graph/one-hop
POST /api/v1/graph/k-hop
POST /api/v1/graph/path
POST /api/v1/graph/query
```

统一 `/graph/query` 当前只接受：

```text
ENTITY
NEIGHBORS
K_HOP
PATH
```

`NAMED` query 尚未开放 HTTP API。named query 目前由内部 `TemplateAwareQueryService` / association 组件使用。

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
- NAMED：由外置 query template 展开为受控 QuerySpec，目前不直接通过 HTTP 暴露。

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

- 任意 raw Cypher；
- NAMED query；
- ChangeEvent 写入端点；
- sync/fullRebuild/resync 管理端点；
- association outward sink；
- 空间推理端点。

同步控制目前通过内部运行时、polling 和 `tools/sync.cmd` 完成，不应把历史文档中“HTTP 必须暴露 ChangeEvent/resync”继续当成当前已实现契约。

## 7. Viewer 与 basePath

服务端 `basePath` 来自 `config/api.yaml`，但当前 Viewer 的 `viewer/src/core/api-client.js` 默认仍使用 `/api/v1`，并在前端独立限制 depth 为 1..8。

因此修改服务端 `basePath` 或 `maxDepth` 时，如果仍使用当前 Viewer，需要同步检查 Viewer 的 API client；两者目前不是同一动态配置源。
