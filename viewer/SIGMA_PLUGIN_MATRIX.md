# Sigma v3 插件/能力矩阵

本轮实际锁定 `sigma@3.0.3`、`graphology@0.26.0`。Sigma v4 仍是 alpha，本工程不使用 v4 API。

| 包 | 版本 | 用途 | 许可证 | 当前是否接入 | 是否仅备用 | 兼容验证结果 |
|---|---:|---|---|---|---|---|
| `sigma` | 3.0.3 | WebGL renderer、v3 events/reducers/camera | MIT | 是 | 是 | 真实 Sigma Gate 通过 |
| `graphology` | 0.26.0 | GraphDTO 内存图、稳定 node/edge key | MIT | 是 | 是 | 33 项单测/真实 Gate 通过 |
| `@sigma/node-border` | 3.0.0 | 填充节点 + 细 border renderer | MIT | 是 | 是 | `NodeBorderProgram` 实际注册并渲染 |
| `@sigma/edge-curve` | 3.1.0 | 曲线箭头、parallel index/curvature | MIT | 是 | 是 | `EdgeCurvedArrowProgram`、`indexParallelEdgesIndex` 实际使用 |
| `@sigma/utils` | 3.0.0 | camera fit to nodes | MIT | 是 | 是 | `fitViewportToNodes` 实际调用 |
| `graphology-layout` | 0.6.1 | layout 坐标收集/基础 layout 工具 | MIT | 是 | 是 | `collectLayout` 实际记录坐标 |
| `graphology-layout-force` | 0.2.4 | 小图有机 Force | MIT | 是 | 是 | Z001/R001/AS0001 Force + Noverlap 通过 |
| `graphology-layout-forceatlas2` | 0.10.1 | 中大型布局及 Worker | MIT | 是（已有） | 是 | `inferSettings` + Worker 实际启动 |
| `graphology-layout-noverlap` | 0.4.2 | layout 后防重叠 | MIT | 是（已有） | 是 | 使用 Sigma size 的 inputReducer 实际执行 |
| `@sigma/export-image` | 3.0.0 | 导出图片 | MIT | 否 | 是 | peer 范围覆盖 Sigma 3；当前无导出需求，避免增加 `file-saver` 运行依赖 |
| `@sigma/node-image` | 3.0.0 | 图片节点 renderer | MIT | 否 | 是 | 可作为未来 renderer 替换资源；当前 KG 无图片节点需求 |
| `@sigma/node-piechart` | 3.0.1 | 饼图节点 renderer | MIT | 否 | 是 | 可作为未来 renderer 替换资源；当前不把统计字段硬编码进 Viewer |
| `@sigma/node-square` | 3.0.0 | 方形节点 renderer | MIT | 否 | 是 | 可作为未来 renderer 替换资源；当前普通 KG 使用 border 节点 |
| `@sigma/layer-webgl` | 3.0.0 | 自定义 WebGL layer | MIT | 否 | 是 | 能力已调查；当前 NodeBorder/EdgeCurve 已满足需求，不增加 layer 复杂度 |
| `@sigma/layer-leaflet` | 3.0.0 | Leaflet 地图 layer | MIT | 否 | 是 | 需要额外 `leaflet@^1.9.4`；当前 Graph Viewer 非地图场景 |
| `@sigma/layer-maplibre` | 3.0.0 | MapLibre 地图 layer | MIT | 否 | 是 | 需要额外 `maplibre-gl@^4.5.0`；当前 Graph Viewer 非地图场景 |

官方能力依据：各包 npm README、包内 TypeScript declarations，以及已安装版本的 `package.json`。关键 API 来源：

- https://www.sigmajs.org/docs/
- https://www.npmjs.com/package/@sigma/node-border
- https://www.npmjs.com/package/@sigma/edge-curve
- https://www.npmjs.com/package/@sigma/utils
- https://www.npmjs.com/package/graphology-layout-force
- https://www.npmjs.com/package/graphology-layout-forceatlas2
- https://www.npmjs.com/package/graphology-layout-noverlap

## 当前 Sigma LayoutPolicy

`AUTO` 不是简单别名：首次建立图时小图（不超过 40 节点）使用 Force；较大图使用 ForceAtlas2 Worker；已有有效坐标的人工切换优先 KEEP；显式“重新平衡”才重新运行 AUTO 物理布局。Force/FA2 路径之后均执行 Noverlap。`FORCE`、`FORCE_ATLAS2`、`NOVERLAP`、`KEEP` 只在 Debug Workbench 中人工比较。

ForceAtlas2 Worker 使用官方 `graphology-layout-forceatlas2/worker`、`inferSettings(graph)` 和大图 Barnes-Hut；Noverlap 的 `inputReducer` 读取实际 Sigma `size`，不会用另一套碰撞尺寸。

## 部署侧注意

当前 `KgApiServer` 的 CSP 为 `script-src 'self'` 且未声明 `worker-src`。官方 FA2 Worker 由浏览器 Blob Worker 创建，因此在该 CSP 下浏览器会阻止 worker。Sigma Gate 使用隔离测试上下文 `bypassCSP` 验证了 Worker 本身、布局和交互；正式部署若要启用 `FORCE_ATLAS2` Worker，需要由服务部署方在允许范围内增加 `worker-src blob:`（本轮禁止修改 Java/API）。Force/Noverlap/KEEP 不依赖该 Blob Worker。
