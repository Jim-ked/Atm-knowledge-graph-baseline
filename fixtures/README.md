# 测试数据

这里的 fixture 只用于开发与回归，不是正式业务数据，也不是第二套知识语义。

- `generated/small/`：固定 seed `20260821` 生成的结构化样例数据。
- `mapping/fixture_mapping.xlsx`：只用于 fixture 的人工映射样例，证明测试数据通过正式 `SourceAdapter -> MappingEngine` 主链。
- 生成器：`org.atmkg.fixture.FixtureGeneratorMain`。

示例：

```bash
java ... org.atmkg.fixture.FixtureGeneratorMain --output fixtures/generated/small --scale small --seed 20260821
```

生成方式参考 ATMONTO/ATMGRAPH 的“稳定基础对象 + 可调规模 + 变化情境”组织方式；不读取其 TTL，不把 RDF 直接导入 Neo4j。
