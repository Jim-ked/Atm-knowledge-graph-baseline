# 本体关键口径

- `Runway` 表示物理跑道；`RunwayDirection` 表示一个具体运行方向（如 16L）。入口/出口位置和高程属于方向，机场代码等身份属性保持在跑道或机场的既有语义中。
- `RouteNode` 是可复用的路线/航路网络节点；`RouteSegment` 通过 `fromNode`、`toNode` 和 `sequenceNumber` 表达路线的具体顺序。是否跨航路、跨 `sourceId` 合并由 Mapping 选择的业务主键决定。
- `BoundaryPoint` 的 `sequenceNumber` 表示 `AirspaceGeometry` 中的绘制顺序；Polygon 首尾闭合属于几何解释，不新增边界段类或下一边界点关系。
- TTL 中存在术语只表示本体允许表达，不等于当前必须存在 Mapping；实际数据提供什么由 Mapping 决定。
- 未来空间或业务计算产生的实例、关系事实应由独立计算模块写入，不在运行时动态发明 Class。
