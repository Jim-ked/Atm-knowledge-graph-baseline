# 人工映射

`字段映射.xlsx` 是当前唯一人工语义映射入口，固定三张 Sheet：实体映射、属性映射、关系映射。

## 实体映射

用于确定：

```text
sourceId + sourceObject + 业务主键
  -> 本体 Class
  -> 稳定实体 UID
```

同一 Class 可以由多个 sourceObject 贡献，只要 UID 规则兼容且业务键值表达同一实体身份。

## 属性映射

用于确定：

```text
sourceId + sourceObject + SourceRecord 字段路径
  -> 某实体 Class 的 datatype property
```

普通新增业务字段通常只需确认 TTL 已有属性并填写本 Sheet，不要修改 DefaultMappingEngine。

Excel 和 JDBC source 现在都可以在写入 Neo4j 前用 `source-preview` 查看 SourceRecord，再用
`source-mapping-preview` 查看 MappingResult。正式 JDBC 应明确指定 sourceId/sourceObject 和 limit；
完整命令及排障顺序见 `tools/START_HERE.txt`。

## 关系映射

用于确定源记录中已经明确存在的端点定位字段如何形成 object property 关系。

当前关系映射按 `sourceId` 选择，没有独立 `sourceObject` 列。也就是说，同一 sourceId 下的每条 SourceRecord 都会检查该 sourceId 下的关系映射，再由 locator 是否有值决定是否产边。多个 sourceObject 共用同一 sourceId 时，如果出现相同字段名/locator，必须人工确认不会误触发关系。

关系映射只表达源记录中可明确定位的事实，不负责空间拓扑推导。`RouteSegment ↔ Airspace` 这类派生关系当前不应硬塞进工作簿。

## 本体刷新

TTL 新增类/属性/关系后，可以通过 `Phase1CheckMain --refresh` 向工作簿追加 `[待映射]` 行。刷新不得覆盖人工已填写内容。

`[待映射]` 行在 loader 中会被跳过，不是已生效配置；必须人工补齐 sourceId/sourceObject/businessKey/字段路径/locator 后再运行测试和同步。

## 本体或 Mapping 改了以后怎么处理

### A. 新增普通属性

新增 datatype property 时，先改唯一正式 TTL，执行 `Phase1CheckMain --refresh`，再在 `字段映射.xlsx` 补齐新增的 `[待映射]` 行并验证 Mapping。然后重启服务，让 TTL 和 Mapping 重新加载；对受影响的 sourceObject 执行 resync 或 fullSync，最后用 review 检查结果。普通属性新增不需要修改 `DefaultMappingEngine`、`Neo4jGraphStore` 或 `QueryService`。

### B. 新增普通实体或关系

基本流程与新增属性相同。新增实体时要人工确认业务主键、UID 规则以及 sourceId/sourceObject；新增关系时要人工确认起点类、终点类、locator 以及 TTL 中的 domain/range，程序不会自动猜。

### C. 删除或改名 Class / Property

refresh 只追加新术语，不会自动删除旧 Mapping，也不会自动改名。失效的旧完整 IRI 会在 Mapping 校验时报错；人工确认后再修改或删除相应行。完成后重启服务并重新同步受影响数据；变化范围很大或无法可靠判断时，再使用 fullRebuild。项目没有自动 Mapping migration。

Mapping 删除某属性或关系后，需要重新同步对应源记录，`replaceProjection` 才会清掉该 SourceRef 不再生成的旧属性或旧关系。仅修改 TTL/Mapping 文件不会自动改变 Neo4j。

### D. 身份相关变化

Class IRI、实体业务主键或 UID namespace 变化可能改变实体 `kg_uid`，并影响关系端点和外部引用；这类变化要先人工评估，通常按全图重建或专门的身份迁移处理，不能靠普通 `--refresh`。ObjectProperty IRI 变化也会改变关系 UID。

### E. 删除 sourceObject / 数据源

从 `sources.yaml` 删除 object 或 source，不会自动通知 Neo4j 删除旧投影。需要先人工清理对应投影，或者确认正式数据源范围完整后执行 fullRebuild；当前没有自动 source retirement。

## 修改原则

- 新数据表/Sheet：先改 `config/sources.yaml`；
- 新本体术语：先改 `ontology/atm_knowledge_graph.ttl`；
- 新字段映射：改本工作簿；
- 新固定查询：改 `queries/query-templates.yaml`；
- 不要为普通业务字段去修改 `DefaultMappingEngine`、`DeterministicIdentityResolver`、`Neo4jGraphStore`。

完整操作步骤见 `tools/START_HERE.txt`。
