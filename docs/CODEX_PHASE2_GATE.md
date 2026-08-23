# Codex Phase 2 Gate

本任务只验证已经实现的 Phase 1/2 主链，不扩展 REST、Viewer、JDBC 正式数据源或本体语义。

## 目标

在联网开发机上完成真实依赖构建和独立 Neo4j 5.26.x 实例验证，确认以下能力成立后停止：

1. `mvn test` 全部普通测试通过。
2. Jena 能读取唯一 `ontology/atm_knowledge_graph.ttl`。
3. POI 能读取/刷新三 Sheet Mapping，人工填写内容不被刷新覆盖。
4. GraphStore 可建立 Community 可用的约束/索引。
5. `fullRebuild` 使用全部实体端点先行、关系/投影后写；重复执行节点/关系计数不增加。
6. 缺失关系端点必须使写入失败，不允许静默丢边。
7. 一跳查询正确。
8. K-hop 返回到达节点及这些节点之间的完整原始关系诱导子图。
9. PATH 验证正确。
10. changed snapshot 的属性修改、关系改挂、新增、删除正确，旧关系不残留。

## 环境要求

- 不使用含其他项目正式数据的 Neo4j 实例。
- 使用独立、可丢弃的 Neo4j 5.26.x Community 测试实例；独立 `data/conf/logs` 和端口。
- 不修改系统 Java 8 等既有系统环境；开发机可使用现有 JDK，只要 `maven.compiler.release=17` 编译成功。
- Neo4j 连接必须显式设置以下环境变量，禁止代码添加 localhost/default fallback：
  - `ATMKG_NEO4J_URI`
  - `ATMKG_NEO4J_DATABASE`
  - `ATMKG_NEO4J_USERNAME`
  - `ATMKG_NEO4J_PASSWORD`

## 执行顺序

1. 解压工程到新的干净工作目录。
2. 检查 JDK、Maven，不修改项目语义资产。
3. 执行：

```text
mvn test
```

4. 准备并启动独立 Neo4j 5.26.x Community 测试实例。
5. 设置四个 `ATMKG_NEO4J_*` 环境变量。
6. 执行真实 Neo4j Gate：

```text
mvn -Datmkg.neo4j.it=true -Dtest=Neo4jPhase2AcceptanceTest test
```

成功时应看到 `PHASE2_NEO4J_OK`。

## 允许修复

如果构建或真实 Neo4j Gate 失败，只允许修复当前实现的兼容性/正确性问题，例如：

- Jena/POI/Neo4j Driver API 使用错误；
- Cypher 5.26 Community 语法问题；
- Neo4j 属性值类型转换问题；
- fullRebuild/replaceProjection 幂等或旧投影清理错误；
- QueryService 一跳/K-hop/path 语义错误；
- 测试自身与 fixture 明显不一致。

## 禁止

- 不改变 `ontology/atm_knowledge_graph.ttl` 的业务语义。
- 不替用户填写正式 `mapping/字段映射.xlsx` 的 `[待映射]` 项。
- 不迁移 NASR、ERA、旧 ATMONTO/Viewer 源码。
- 不新增航空业务 Java 类或字段分支。
- 不增加 REST/UI/Kafka/Debezium/GDS/n10s/LLM 等后续能力。
- 不把 Neo4j/JDK/Maven/下载依赖复制进源码仓。
- 不生成或维护 SHA/校验清单。
- 不为了让测试通过而放宽“一跳/K-hop 完整性”“缺失端点显式失败”“旧投影清理”等要求。

## 回传内容

只回传：

- `java -version`
- `mvn -version`
- Neo4j 版本与独立实例目录/端口（不回传密码）
- `mvn test` 汇总
- Phase 2 Gate 输出
- 如有修改：`git diff --stat` 与具体 diff
- 仍未解决的问题

完成 Gate 后停止，等待审查，不进入 REST/API 阶段。
