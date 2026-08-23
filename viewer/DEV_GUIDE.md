# Viewer 开发修改索引

正式入口：`/viewer/`。开发调试入口：`/viewer/?debug=true`。

## 我想修改什么 → 去哪里改

| 修改目标 | 文件 / 配置位置 |
|---|---|
| 节点颜色、稳定配色、指定类型覆盖色 | `src/config/viewer-config.js` → `color.palette`、`color.overrides` |
| 节点大小、描边、字体 | `viewer-config.js` → `node.defaultSize`、`borderWidth`、`fontSize`、`fontWeight` |
| 节点标签位置、最大宽度、省略方式 | `viewer-config.js` → `node.labelMode`、`labelMaxWidth`、`labelOverflow` |
| 关系颜色、线宽、字体、箭头 | `viewer-config.js` → `edge`；基础绘制映射在 `adapters/g6-adapter.js` → `#edgeOptions` |
| path 样式 | `viewer-config.js` → `edge.pathWidth`；颜色在 `g6-adapter.js` 的 `highlighted` state |
| selected / hover / inactive 样式 | `viewer-config.js` → `node.selectedStyle`、`hoverStyle`、`inactiveStyle`、`edge.*Style` |
| 双击行为、drag、dragEnd、pin/unpin、hide | `viewer-config.js` → `interaction` |
| 展开 / 收起的页面流程 | `src/app.js` → `expandSelected`、`collapseSelected`、`handleNodeAction` |
| G6 数据、事件和 state 映射 | `src/adapters/g6-adapter.js` |
| G6 persistent physics | `src/adapters/g6-persistent-simulation.js`（当前冻结，修改前先重新建立物理 Gate） |
| 关系平行边、双向边、self-loop 几何 | `src/adapters/g6-visual-geometry.js` 与 `g6-poc-config.js` transforms |
| 正常 / Debug 页面显隐 | `index.html` 的 `data-debug-only`，判断逻辑在 `src/app.js` 的 `isDebug` |
| 正式页面结构与样式 | `index.html`、`src/styles.css` |

## 标签模式

- 节点：`AUTO`、`INSIDE`、`OUTSIDE`、`HIDDEN`。
- 关系：`AUTO`、`VISIBLE`、`HIDDEN`。
- 模式、短 caption 和完整 caption 只存在于 Viewer 显示层，不写入 GraphDTO。

## 配色规则

默认键为 `kind`，缺失时使用第一个 label。确定性哈希保证同一键重复加载颜色不漂移。指定覆盖示例：

```js
overrides: {
  // "某 kind 或主要 label": "#0f766e"
}
```

Adapter 中不得硬编码 Airport、Route 等业务类型。

## 为什么物理层与显示层不同

- physics relationship 按无向节点对去重，是为了避免同端点多条业务关系重复叠加弹簧力。
- GraphDTO relationships 在绘制层全部保留，因为去重物理 link 不等于合并业务关系。
- `preferredDistance` 从两端视觉半径派生，节点视觉尺寸变化时弹簧目标距离同步变化。
- 新节点初始化在接近 `preferredDistance` 且位于 collision 区外，避免展开瞬间被碰撞力和 link force 同时高速推出。

## 验证

```text
npm test
npm run build
npm run gate:viewer
```

`gate:viewer` 只验证当前 G6 正式入口、Debug 隔离、标签模式和交互。
