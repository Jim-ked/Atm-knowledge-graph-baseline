# 人工映射

`字段映射.xlsx` 是当前唯一人工语义映射入口。正式 loader 只读取“实体映射”“属性映射”“关系映射”三张 Sheet；“本体参考”由 TTL 刷新，仅供人工查阅，不参与 `MappingCatalog`。

运行时只支持本文定义的表头，当前工作簿直接按此契约维护。

## 实体映射

精确表头：

```text
sourceId | sourceObject | 实体类 | 业务主键
```

它表达哪类 `SourceRecord` 生成哪个本体 Class。实体 UID 算法固定为 `Class IRI + 业务主键值`，工作簿不提供身份算法配置列。同一 Class 可由不同 sourceId/sourceObject 贡献，只要业务键值表达同一业务实体，就得到同一 canonical UID。

## 属性映射

精确表头：

```text
sourceId | sourceObject | 实体类 | 源字段/路径 | 本体属性 | 转换 | 必填
```

“实体类”和“本体属性”可填写唯一 localName 或完整 IRI。“转换”只允许空白、`trim`、`upper`、`lower`、`integer`、`long`、`decimal`、`boolean`；“必填”建议用“是/否”。

Excel 和 JDBC source 都可在写入 Neo4j 前用 `source-preview` 查看 `SourceRecord`，再用 `source-mapping-preview` 查看 `MappingResult`。完整命令及排障顺序见 `tools/START_HERE.txt`。

## 关系映射

精确表头：

```text
sourceId | sourceObject | 关系类型 | 起点类 | 起点引用字段 | 终点类 | 终点引用字段 | 说明
```

`sourceId + sourceObject` 只标识明确提供该关系事实的 `SourceRecord` 类型。起点/终点引用字段是在当前关系事实记录中存放两端业务键值的字段路径，不是跨表 join 配置。

关系端点 UID 直接按“端点 Class IRI + 当前记录引用值”生成，不要求关系来源同时拥有两端实体映射。`GraphStore` 后续仍会严格检查端点存在性与 ontology domain/range。

关系映射只表达源记录中明确存在的事实，不负责空间拓扑推导。`RouteSegment ↔ Airspace` 这类派生关系不能硬塞进工作簿。

## 本体刷新与人工编辑

关闭 Excel 后执行：

```text
mvn.cmd -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=org.atmkg.tools.Phase1CheckMain" "-Dexec.args=--refresh"
```

refresh 会根据唯一正式 TTL 重建“本体参考”，不会向三张正式 Mapping Sheet 追加行，也不会覆盖其中的人工配置。需要新增 Mapping 时，在本体参考中确认 IRI/domain/range 后，人工向正式 Sheet 新增一行。

工作簿已冻结首行、启用筛选、设置列宽，并为“转换”和“必填”提供下拉值。不要增加宏或依赖 Microsoft Excel 的功能。

## 修改原则

- 新数据表/Sheet：先改 `config/sources.yaml`；
- 新本体术语：先改唯一正式 TTL，再 refresh 本体参考；
- 新字段映射：只向三张正式 Mapping Sheet 增加实际启用行；
- 新固定查询：改 `queries/query-templates.yaml`；
- 不要为普通业务字段修改 `DefaultMappingEngine`、`DeterministicIdentityResolver`、`Neo4jGraphStore`。

修改 TTL/Mapping 文件不会自动改变 Neo4j。完成后重启服务，并对受影响入口执行 resync/fullSync；涉及身份或大范围删除时先评估再决定是否 fullRebuild。
