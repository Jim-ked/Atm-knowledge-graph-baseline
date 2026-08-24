# 依赖策略

源码、开发资源和部署产物分离。源码仓只保存当前实现所需代码和构建描述，不保存 JDK、Neo4j、Maven 仓、Protégé、离线安装介质或候选依赖分发包。

## 当前正式实现

Java 编译目标：JDK 17。

当前 Maven 主要依赖：

- Apache Jena 5.6.0：本体读取；
- Apache POI 5.5.1：人工映射 XLSX；
- Neo4j Java Driver 6.2.1：Neo4j 访问；
- Oracle JDBC `ojdbc17` 23.26.3.0.0：当前正式 Oracle 源库的 runtime Thin Driver；
- Jackson 2.22.2：JSON/YAML；
- JUnit Jupiter 5.11.4：测试。

Viewer 当前正式依赖：

- `@antv/g6` 5.1.1；
- `d3-force` 3.0.0；
- esbuild / playwright-core 仅用于构建和验证。

Sigma、Graphology、Cytoscape 及其插件已经从正式 Viewer 代码和依赖中移除，不再作为当前并行实现维护。

## 可替换原则

对于效果敏感或外部环境相关的叶子依赖，允许保留清晰接口边界，等真实需要出现后替换；不为了“可能会换”提前维护两套业务实现。

例如：

- `SourceAdapter` 隔离物理数据源；
- `GraphStore` 隔离图写入；
- `QueryService` 隔离图查询；
- Viewer 只消费 GraphDTO，不反向绑定后端；
- `DerivedRelationPlugin` 只保留未来派生计算扩展边界。

`JdbcSourceAdapter` 仍只依赖标准 `java.sql`。Oracle 只出现在 JDBC 配置和 runtime 叶子依赖；当前不引入 `ojdbc17-production`、UCP、Wallet/PKI、OCI 或连接池框架。以后更换其他 JDBC 数据库时，原则上只替换 driver、URL、源对象配置和对应 runtime driver，不修改 Core、Mapping、Sync、Query、GraphDTO 或 Viewer。

## 明确不采用的方式

- 不为备份同时开发第二套图数据库；
- 不把 APOC/GDS/n10s 作为基础闭环硬依赖；
- 不把 Kafka/Debezium/完整 CDC 平台作为当前同步必需条件；
- 不让 Python 形成第二套 Mapping/UID/关系语义核心；
- 不因历史实验保留 Sigma/Cytoscape 运行依赖；
- 不提前引入 JTS/GeoTools/R-tree，直到真实空间派生需求进入实现阶段。
