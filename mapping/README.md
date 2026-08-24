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

## 关系映射

用于确定源记录中已经明确存在的端点定位字段如何形成 object property 关系。

当前关系映射按 `sourceId` 选择，没有独立 `sourceObject` 列。也就是说，同一 sourceId 下的每条 SourceRecord 都会检查该 sourceId 下的关系映射，再由 locator 是否有值决定是否产边。多个 sourceObject 共用同一 sourceId 时，如果出现相同字段名/locator，必须人工确认不会误触发关系。

关系映射只表达源记录中可明确定位的事实，不负责空间拓扑推导。`RouteSegment ↔ Airspace` 这类派生关系当前不应硬塞进工作簿。

## 本体刷新

TTL 新增类/属性/关系后，可以通过 `Phase1CheckMain --refresh` 向工作簿追加 `[待映射]` 行。刷新不得覆盖人工已填写内容。

`[待映射]` 行在 loader 中会被跳过，不是已生效配置；必须人工补齐 sourceId/sourceObject/businessKey/字段路径/locator 后再运行测试和同步。

## 修改原则

- 新数据表/Sheet：先改 `config/sources.yaml`；
- 新本体术语：先改 `ontology/atm_knowledge_graph.ttl`；
- 新字段映射：改本工作簿；
- 新固定查询：改 `queries/query-templates.yaml`；
- 不要为普通业务字段去修改 `DefaultMappingEngine`、`DeterministicIdentityResolver`、`Neo4jGraphStore`。

完整操作步骤见 `tools/START_HERE.txt`。
