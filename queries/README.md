# 查询资产

查询层只读取图中已经存在的事实，不负责补造关系或执行空间/业务推理。

## query-templates.yaml

生产 named query 定义放在 `queries/query-templates.yaml`。当前模板只能展开为受控 `QuerySpec`：

- `NEIGHBORS`；
- `K_HOP`；
- `direction`；
- `depth`；
- `relationshipTypes`；
- `classFilters`。

不接受 raw Cypher、SQL、script 或任意表达式。

当前正式模板包括：

- `airport-direct-flights`：机场与图中直接相邻的 Flight；
- `segment-route-structures`：RouteSegment 反查 Route / ScheduledFlightRoute / PlannedFlightRoute；
- `planned-route-flights`：PlannedFlightRoute 反查 Flight。

这些表示已有图事实关联，不应直接命名为“完整影响分析”。

## change-query-rules.yaml

该文件只表达：

```text
GraphNodeDTO.kind -> 已存在的 queryId
```

例如 RouteSegment 变化后选择 `segment-route-structures`，PlannedFlightRoute 再选择 `planned-route-flights`。

正式 `KgServiceMain` 会严格加载本文件。UPSERT 写图成功后，GraphChangeProcessor 先执行 Neighborhood，再按本文件执行 Association，并把统一结果输出为一行控制台摘要。Projector 或下游异常会向上传播；在 JDBC polling 中，该失败批次不会推进 checkpoint。

该控制台输出不是 durable sink 或业务消息推送。DELETE notice 虽保留 before-state UID，现有 Projector 仍跳过 DELETE 推导；`tools/sync.cmd` 是人工同步入口，也不执行 GraphChange。

## 当前空间边界

`RouteSegment ↔ Airspace` 空间关系尚未计算。不能通过增加一个 named query、K-hop 或 class filter 来“推断”不存在的空间事实。

未来空间派生模块完成后，QueryService 仍只负责沿已经写入图中的事实查询。
