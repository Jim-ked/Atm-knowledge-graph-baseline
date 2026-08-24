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

当前 `ChangeQueryRuleRegistry + GraphChangeAssociationProjector` 已实现并有测试，但尚未装配进正式 `KgServiceMain/SyncRuntime`，也没有 outward sink。因此修改本文件当前不会让 `tools/service.cmd` 自动推送关联结果。

## 当前空间边界

`RouteSegment ↔ Airspace` 空间关系尚未计算。不能通过增加一个 named query、K-hop 或 class filter 来“推断”不存在的空间事实。

未来空间派生模块完成后，QueryService 仍只负责沿已经写入图中的事实查询。
