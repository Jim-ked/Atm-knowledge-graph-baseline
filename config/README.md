# 配置说明

`config/` 中保留的正式 YAML 都会影响运行。修改前先确认对应消费者和验证方法：

- `sources.yaml`：正式数据源物理读取配置。`SyncRuntimeAssembler` 直接加载。
- `sync.yaml`：同步策略与 polling 配置。`SyncRuntimeAssembler` 直接加载。
- `api.yaml`：HTTP host/port/basePath/schemaVersion/depth/请求与结果规模上限。`KgServiceMain` 直接加载。

具体航空字段、实体、属性和关系语义不进入这些 YAML，统一由当前本体和 `mapping/字段映射.xlsx` 维护。

projectId、UID namespace、Neo4j 环境变量、唯一正式 TTL 和查询完整性原则等固定项见
`docs/固定项与运行配置说明.md`。接数据、改 API 或开启 polling 前，先按 `tools/START_HERE.txt`
中对应任务操作。

首次离线接手先复制 `tools/env.cmd.example`（Kylin/Linux 使用 `tools/env.sh.example`），再运行
`tools/check.cmd`；Linux 可先执行 `tools/runtime.sh status`，并人工检查 `ss -ltnp | grep 18080`。
正式 runtime 不读取 Maven 仓或 npm；`tools/service.cmd` 仅是开发启动入口。
