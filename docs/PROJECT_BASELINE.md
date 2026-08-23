# 项目执行基线

本项目从零实现独立的知识图谱构建、持续更新与查询能力：结构化业务数据依据当前唯一现行本体与人工映射形成稳定实体、属性和关系，写入 Neo4j；支持初始全量构图与后续增量同步；通过与调用端技术栈无关的 GraphDTO/API 提供实体、关系、路径和子图数据。

固定主链：

```text
SourceAdapter -> SourceRecord -> MappingEngine -> GraphEntity/GraphRelationship -> GraphStore -> Neo4j
TriggerAdapter -> ChangeEvent -> SyncService -> SourceAdapter -> MappingEngine -> GraphStore
QueryService -> GraphDTO -> External Client
```

## 现行语义输入

- 唯一本体：`ontology/atm_knowledge_graph.ttl`；运行目录禁止并存、切换多个本体版本，历史变化由 Git 保存。
- 本体后续允许增删改；普通类、属性、关系变化由本体 + 人工映射吸收，Java Core 不硬编码航空字段或表名语义。
- `mapping/字段映射.xlsx` 是唯一人工语义映射入口。本体刷新时，新元素增加 `[待映射]`，已填写映射不得覆盖；删除/改名造成失效必须显式报告。
- 当前停止关系挖掘和重新构建本体，工程只执行当前本体已经表达的语义。

## 数据与同步

源业务数据是权威事实，Neo4j 是可重建投影。`SourceAdapter` 只负责物理读取，不能决定本体语义。全量读取和变更扫描必须支持流式/迭代处理，不能要求把全部源记录一次装入内存。

稳定 `sourceKey` / 业务键用于确定性 UID；相同输入重复同步不得生成重复实体或关系。增量事件只说明哪个源记录发生变化，`SyncService` 必须回读权威源记录后重新映射。重映射必须能够清除该源记录已经不再成立的映射属性/关系，避免只 MERGE 新值而残留旧投影。支持事件处理、变更水位补偿与人工 resync；首期不要求 Kafka、Debezium 或其他大型 CDC 平台。

## 查询与 API

至少支持实体、完整一跳、完整 K 跳、两实体路径和固定语义/命名查询。相关实体、路线结构、影响查询可以作为外置命名查询资产实现。完整 K 跳返回约束内全部节点及这些节点之间原图已有的全部关系；不得随机抽样、top-N 或静默 LIMIT。超限应显式失败或要求增加约束。

GraphDTO 使用稳定业务 UID，不暴露 Neo4j 内部 ID；客户端不得直连 Neo4j、提交业务 Cypher或依赖某个 Viewer 的专用字段。最终业务界面技术栈未知，本项目只留稳定 API。

## Viewer 与规模验证

Viewer 是独立验证客户端而不是业务系统。G6、Cytoscape.js、Sigma.js + Graphology 均为可替换候选，使用同一查询语义、同一 GraphDTO 与同一测试数据进行公平比较；各候选只准备实际需要的最小布局/交互依赖。候选资源未被选中时不要求逐项烟测，选用后直接用本项目数据验证，效果不佳即可替换。

大规模目标属于 Neo4j 存储、索引、批量写入和 QueryService 查询能力，不要求浏览器一次性渲染全量大图。

## 测试数据

测试数据从零生成，参考 ATMONTO/ATMGRAPH 已验证的模拟数据生成组织方式，而不是使用其 TTL 作为本项目 Schema。生成结果必须是可由 SourceAdapter 读取的结构化源数据，用来真实经过 MappingEngine 构图；不得用 RDF/TTL 直接导入 Neo4j 绕开主链。测试集应支持固定 seed、不同规模和新增/修改/引用变化/删除等变化场景。

## 环境与部署边界

正式目标仍为银河麒麟 V4.0.2 x86_64（glibc 2.23）服务器。系统 Java 8 不动，优先使用项目私有 JDK 17 + Neo4j 5.26 LTS + Java 服务；只有实机验证失败后再采用兼容降级方案。联网开发机负责获取依赖与正常开发验证；Win7 离线机主要用于本体/数据准备、终端和必要兼容验证，不要求承载最新完整服务。

源码、离线开发资源、部署产物彻底分离。离线环境应能在已有 JDK/Maven/Neo4j/Protégé/前端资源条件下修改源码、修改本体和映射、运行测试并重新构建；不把这些工具和发行包塞入 Git 仓库。

## 依赖策略

对效果敏感且可替换的叶子依赖采用“主选 + 备选资源”。代码只接当前主选，不同时维护两套业务实现。Neo4j 是当前明确图数据库；APOC、GDS、n10s 等不得成为基础闭环硬依赖。

## 非目标

不迁移 NASR、ERA、旧 ATMONTO、旧 Neo4j import 或旧 Viewer 实现代码；不建设知识图谱管理平台；不假定最终 UI 技术栈；不进行关系挖掘；不让 Python 形成第二套 Mapping/UID/关系语义核心。
