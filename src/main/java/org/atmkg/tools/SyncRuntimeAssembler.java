package org.atmkg.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.atmkg.core.ProjectConstants;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.spi.GraphStore;
import org.atmkg.core.spi.MappingEngine;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.core.spi.SyncService;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.atmkg.infra.neo4j.Neo4jGraphStore;
import org.atmkg.infra.source.SourceAdapterFactory;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;
import org.atmkg.infra.trigger.JdbcPollingTriggerAdapter;
import org.atmkg.infra.trigger.PollingCheckpointStore;
import org.atmkg.service.sync.DefaultSyncService;
import org.atmkg.service.sync.GraphChangeNotice;
import org.atmkg.service.sync.SyncRuntime;
import org.atmkg.service.sync.SyncRuntimeConfig;
import org.neo4j.driver.Driver;

/**
 * 只有“已有通用 SourceAdapter 已获准进入正式进程”或同步组件装配关系变化时才改这里。新增 Excel Sheet/
 * JDBC 表去 {@code config/sources.yaml}，polling scope 去 {@code config/sync.yaml}，字段和关系去
 * {@code mapping/字段映射.xlsx}，不要为这些需求增加 switch 分支。
 *
 * <p>当前固定读取正式 sources/sync、正式 mapping，按 adapter=excel|jdbc 创建实现并可创建 polling；上层正式
 * 服务可注入 GraphChange notice listener，旧重载继续供不需要变化输出的人工工具使用。
 * 误加载 sources.local.yaml/fixture mapping 会把开发数据带进服务；把 initialFullImport 当自动 fullRebuild
 * 会造成启动清图。配置失败先查 sourceId/objects、polling scope 和 watermarkField，再查本类 validate。
 */
final class SyncRuntimeAssembler {
    private static final SourceAdapterFactory SOURCE_ADAPTERS = new SourceAdapterFactory();

    private SyncRuntimeAssembler() {}

    static SyncRuntime assemble(Path projectRoot, OntologySchema schema, Driver driver,
                                Neo4jConnectionSettings neo4j) {
        return assemble(projectRoot, schema, driver, neo4j, notice -> {});
    }

    static SyncRuntime assemble(Path projectRoot, OntologySchema schema, Driver driver,
                                Neo4jConnectionSettings neo4j,
                                Consumer<GraphChangeNotice> noticeListener) {
        Objects.requireNonNull(noticeListener, "noticeListener");
        AssemblyPlan plan = plan(projectRoot);
        if (plan.sources().getSources().isEmpty()) return SyncRuntime.disabled();
        return assembleEnabled(plan, schema, driver, neo4j, noticeListener).runtime();
    }

    static SyncRuntime assemble(Path projectRoot, EnabledRuntimeFactory enabledFactory) {
        Objects.requireNonNull(enabledFactory, "enabledFactory");
        AssemblyPlan plan = plan(projectRoot);
        if (plan.sources().getSources().isEmpty()) return SyncRuntime.disabled();
        return Objects.requireNonNull(enabledFactory.create(plan), "enabled sync runtime");
    }

    static AssemblyPlan plan(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path root = projectRoot.toAbsolutePath().normalize();
        SourceConfig sources = SourceConfig.load(root.resolve("config/sources.yaml"));
        SyncRuntimeConfig sync = SyncRuntimeConfig.load(root.resolve("config/sync.yaml"));
        return validate(root, sources, sync);
    }

    private static AssemblyPlan validate(Path root, SourceConfig sources, SyncRuntimeConfig sync) {
        for (ConfiguredSource source : sources.getSources().values()) {
            if (!SOURCE_ADAPTERS.supports(source.getAdapter())) {
                throw new IllegalArgumentException("未知 SourceAdapter：" + source.getAdapter()
                        + " @ " + source.getSourceId());
            }
            JsonNode objects = source.getConfig().get("objects");
            if (objects == null || !objects.isObject() || objects.isEmpty()) {
                throw new IllegalArgumentException("数据源 objects 不能为空：" + source.getSourceId());
            }
        }
        for (SyncRuntimeConfig.PollingScope scope : sync.getPollingScopes()) {
            ConfiguredSource source;
            try {
                source = sources.requireSource(scope.sourceId());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("polling scope 引用不存在的数据源："
                        + scope.sourceId() + "/" + scope.sourceObject(), ex);
            }
            if (!"jdbc".equalsIgnoreCase(source.getAdapter())) {
                throw new IllegalArgumentException("polling scope 只支持 JDBC 数据源："
                        + scope.sourceId() + "/" + scope.sourceObject());
            }
            JsonNode objects = source.getConfig().get("objects");
            JsonNode object = objects == null ? null : objects.get(scope.sourceObject());
            if (object == null || !object.isObject()) {
                throw new IllegalArgumentException("polling scope sourceObject 不存在："
                        + scope.sourceId() + "/" + scope.sourceObject());
            }
            JsonNode watermark = object.get("watermarkField");
            if (watermark == null || !watermark.isTextual() || watermark.textValue().isBlank()) {
                throw new IllegalArgumentException("polling scope 未配置 watermarkField："
                        + scope.sourceId() + "/" + scope.sourceObject());
            }
        }
        return new AssemblyPlan(root, sources, sync);
    }

    static SyncAssembly assembleEnabled(AssemblyPlan plan, OntologySchema schema, Driver driver,
                                        Neo4jConnectionSettings neo4j) {
        return assembleEnabled(plan, schema, driver, neo4j, notice -> {});
    }

    static SyncAssembly assembleEnabled(AssemblyPlan plan, OntologySchema schema, Driver driver,
                                        Neo4jConnectionSettings neo4j,
                                        Consumer<GraphChangeNotice> noticeListener) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(neo4j, "neo4j");
        Objects.requireNonNull(noticeListener, "noticeListener");

        // 1. 根据正式 sources 配置创建物理数据源 Adapter。
        Map<String, SourceAdapter> adapters = new LinkedHashMap<>();
        for (ConfiguredSource source : plan.sources().getSources().values()) {
            SourceAdapter adapter = SOURCE_ADAPTERS.create(source, plan.root());
            adapters.put(source.getSourceId(), adapter);
        }

        // 2. 加载人工 mapping，并初始化 Neo4j 图存储和同步服务。
        MappingCatalog catalog = new PoiMappingRegistry().load(
                plan.root().resolve("mapping/字段映射.xlsx"), schema);
        DefaultMappingEngine mapping = new DefaultMappingEngine(catalog,
                new DeterministicIdentityResolver(ProjectConstants.IDENTITY_NAMESPACE));
        Neo4jGraphStore store = new Neo4jGraphStore(driver, neo4j, schema);
        store.initializeSchema();
        DefaultSyncService syncService = syncService(adapters, mapping, store, noticeListener);
        if (!plan.sync().isPollingEnabled()) {
            return new SyncAssembly(syncService, SyncRuntime.enabled(syncService));
        }

        // 3. 仅在正式 sync 配置启用时装配 JDBC polling。
        List<JdbcPollingTriggerAdapter.PollingScope> scopes = plan.sync().getPollingScopes().stream()
                .map(scope -> new JdbcPollingTriggerAdapter.PollingScope(
                        scope.sourceId(), scope.sourceObject(), scope.initialWatermark()))
                .toList();
        JdbcPollingTriggerAdapter polling = new JdbcPollingTriggerAdapter(
                adapters, scopes, plan.sync().getPollingInterval(), plan.sync().getPollingLookback(),
                new PollingCheckpointStore(plan.root().resolve(PollingCheckpointStore.DEFAULT_RELATIVE_PATH)));
        return new SyncAssembly(syncService, SyncRuntime.enabled(syncService, polling));
    }

    static DefaultSyncService syncService(Map<String, SourceAdapter> adapters, MappingEngine mapping,
                                          GraphStore store, Consumer<GraphChangeNotice> noticeListener) {
        return new DefaultSyncService(adapters, mapping, store, noticeListener);
    }

    record AssemblyPlan(Path root, SourceConfig sources, SyncRuntimeConfig sync) {}
    record SyncAssembly(SyncService syncService, SyncRuntime runtime) {}

    @FunctionalInterface
    interface EnabledRuntimeFactory {
        SyncRuntime create(AssemblyPlan plan);
    }
}
