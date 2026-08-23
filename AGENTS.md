# AGENTS.md

- 新工程从零开发，不复制 NASR、ERA、旧 ATMONTO、旧 Neo4j import 或旧 Viewer 实现代码。
- 当前唯一现行本体：`ontology/atm_knowledge_graph.ttl`；禁止运行时多版本本体并存或切换，历史变化由 Git 保存。
- 本体可以后续增删改；普通类、属性、关系变化由本体 + 人工映射吸收，Java Core 不硬编码航空字段或表名语义。
- 当前不进行关系挖掘或重新构建本体。
- `SourceAdapter` 只负责物理数据读取；`MappingEngine` 负责映射执行；`GraphStore` 是 Neo4j 写入边界；`QueryService` 只输出 GraphDTO。
- 源数据是权威事实，Neo4j 是知识图谱投影；增量事件后必须回读权威源记录。重新同步后不得保留已经失效的旧属性或旧关系。
- UID 必须由稳定业务键确定；重复全量/增量同步必须幂等，不得生成重复节点和关系。
- 全量和补偿读取必须允许迭代/批处理，不得把规模设计建立在一次性 `List` 全量加载上。
- 调用端技术栈未知，不得绑定 Vue/React/特定业务界面；客户端不得直接依赖 Neo4j 内部 ID 或业务 Cypher。
- 完整 K 跳/子图查询不得静默抽样、top-N 或 LIMIT 截断；返回节点之间的原图关系必须完整。超限显式失败或要求增加约束。
- G6、Cytoscape.js、Sigma.js + Graphology 只作为可替换 Viewer 候选；公平比较必须使用同一 GraphDTO 和同一测试数据。
- 测试数据参考 ATMONTO/ATMGRAPH 的模拟数据生成方式，但必须从结构化源数据经过本项目 Mapping 主链构图，不得用 ATMONTO TTL 直接导入 Neo4j。
- 正式目标环境为银河麒麟 V4.0.2 x86_64；系统 Java 8 不动，优先私有 JDK 17 + Neo4j 5.26 LTS，实机失败后才降级。
- 源码、开发资源、部署产物分离；不把 JDK、Neo4j、Protégé、Maven 仓、候选依赖资源或部署包提交到本仓库。
- 可替换叶子依赖按主选 + 备选资源准备；代码只实现当前主选，不维护双业务实现。
- Java 是正式运行时唯一语义核心；Python 只允许用于外围准备、测试数据生成、实验或分析，不得形成第二套 Mapping/UID/关系语义实现。
- 首期不引入 Kafka、Debezium、完整 CDC 平台、LLM 或推理框架作为基础闭环依赖。
