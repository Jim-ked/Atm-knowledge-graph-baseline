package org.atmkg.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.atmkg.core.ProjectConstants;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.infra.source.SourceAdapterFactory;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;

/**
 * Excel/JDBC SourceRecord 正确但 mapping 结果可疑时运行：
 * {@code tools\source-mapping-preview.cmd config\sources.yaml mapping\字段映射.xlsx 3}。
 * 新增属性/实体/关系只改工作簿和 TTL，不改本类。
 *
 * <p>只有抽样输出或预览工具支持范围经确认变化时才写 Java。无参数使用 sources.local + fixture mapping，
 * 不能据此宣称正式 mapping 正确；本工具通过 SourceAdapterFactory 复用正式物理读取，不写 GraphStore。
 * 正式 JDBC 应使用五参数形式明确指定 sourceId/sourceObject/limit，避免遍历全部表或视图。
 */
public final class SourceMappingPreviewMain {
    private static final SourceAdapterFactory SOURCE_ADAPTERS = new SourceAdapterFactory();

    private SourceMappingPreviewMain() {}

    public static void main(String[] args) {
        if (args.length == 4 || args.length > 5) throw new IllegalArgumentException(usage());
        Path sourcesFile = args.length > 0 ? Path.of(args[0]) : Path.of("config/sources.local.yaml");
        Path mappingFile = args.length > 1 ? Path.of(args[1]) : Path.of("fixtures/mapping/source_preview_mapping.xlsx");
        boolean targeted = args.length == 5;
        int sampleLimit = targeted ? positiveInt(args[4]) : args.length > 2 ? positiveInt(args[2]) : 3;

        var schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        MappingCatalog catalog = new PoiMappingRegistry().load(mappingFile, schema);
        DefaultMappingEngine engine = new DefaultMappingEngine(catalog,
                new DeterministicIdentityResolver(ProjectConstants.IDENTITY_NAMESPACE));
        SourceConfig sources = SourceConfig.load(sourcesFile);
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();

        System.out.println("==== SourceRecord → MappingResult 开发核验 ====");
        System.out.println("sources=" + sourcesFile.toAbsolutePath().normalize());
        System.out.println("mapping=" + mappingFile.toAbsolutePath().normalize());
        if (targeted) {
            ConfiguredSource source = sources.requireSource(args[2]);
            preview(source, args[3], SOURCE_ADAPTERS.create(source, projectRoot), engine, sampleLimit);
            return;
        }
        for (ConfiguredSource source : sources.getSources().values()) {
            if (!"excel".equalsIgnoreCase(source.getAdapter())) {
                throw new IllegalArgumentException("包含 JDBC 时请明确指定 sourceId/sourceObject：" + usage());
            }
            SourceAdapter adapter = SOURCE_ADAPTERS.create(source, projectRoot);
            source.getConfig().path("objects").fieldNames().forEachRemaining(objectName ->
                    preview(source, objectName, adapter, engine, sampleLimit));
        }
    }

    static List<MappingResult> preview(ConfiguredSource source, String objectName, SourceAdapter adapter,
                                       DefaultMappingEngine engine, int sampleLimit) {
        List<MappingResult> results = new ArrayList<>();
        for (SourceRecord record : SourcePreviewMain.readLimited(adapter.readAll(objectName), sampleLimit)) {
            results.add(engine.map(record));
        }
        Map<String, GraphEntity> uniqueEntities = new LinkedHashMap<>();
        Map<String, GraphRelationship> uniqueRelationships = new LinkedHashMap<>();
        int entityResults = 0;
        int relationshipResults = 0;
        for (MappingResult result : results) {
            entityResults += result.getEntities().size();
            relationshipResults += result.getRelationships().size();
            result.getEntities().forEach(entity -> uniqueEntities.putIfAbsent(entity.getUid(), entity));
            result.getRelationships().forEach(relation -> uniqueRelationships.putIfAbsent(relation.getUid(), relation));
        }

        System.out.println();
        System.out.println("[" + objectName + "] sourceId=" + source.getSourceId()
                + ", adapter=" + source.getAdapter());
        System.out.println("本次 SourceRecord=" + results.size()
                + ", 实体结果=" + entityResults + "（UID去重=" + uniqueEntities.size() + "）"
                + ", 关系结果=" + relationshipResults + "（UID去重=" + uniqueRelationships.size() + "）");
        uniqueEntities.values().stream().limit(sampleLimit).forEach(entity -> {
            System.out.println("  实体 UID=" + entity.getUid());
            System.out.println("       类=" + entity.getClassIri() + ", 属性=" + entity.getProperties());
        });
        uniqueRelationships.values().stream().limit(sampleLimit).forEach(relation -> {
            System.out.println("  关系 " + relation.getPredicateIri());
            System.out.println("       " + relation.getSourceUid() + " -> " + relation.getTargetUid());
        });
        return List.copyOf(results);
    }

    private static int positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("limit 必须是正整数：" + value, ex);
        }
    }

    private static String usage() {
        return "source-mapping-preview.cmd <sources.yaml> <mapping.xlsx> [limit]，或 "
                + "<sources.yaml> <mapping.xlsx> <sourceId> <sourceObject> <limit>";
    }
}
