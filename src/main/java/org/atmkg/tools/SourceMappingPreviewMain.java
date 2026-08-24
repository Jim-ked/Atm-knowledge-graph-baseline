package org.atmkg.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.atmkg.core.model.GraphEntity;
import org.atmkg.core.model.GraphRelationship;
import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.infra.identity.DeterministicIdentityResolver;
import org.atmkg.infra.mapping.DefaultMappingEngine;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;
import org.atmkg.infra.source.excel.ExcelSourceAdapter;

/**
 * Excel SourceRecord 正确但 mapping 结果可疑时运行：
 * {@code tools\source-mapping-preview.cmd config\sources.yaml mapping\字段映射.xlsx 3}。
 * 新增属性/实体/关系只改工作簿和 TTL，不改本类。
 *
 * <p>只有抽样输出或预览工具支持范围经确认变化时才写 Java。无参数使用 sources.local + fixture mapping，
 * 不能据此宣称正式 mapping 正确；本工具只创建 ExcelSourceAdapter，不预览 JDBC，也绝不写 GraphStore。
 * sampleLimit 只限制最终打印数量；实现会读取整个目标 Excel 入口并保留全部 MappingResult，再汇总 UID，
 * 不要用它做超大正式数据源性能测试。
 */
public final class SourceMappingPreviewMain {
    private SourceMappingPreviewMain() {}

    public static void main(String[] args) {
        Path sourcesFile = args.length > 0 ? Path.of(args[0]) : Path.of("config/sources.local.yaml");
        Path mappingFile = args.length > 1 ? Path.of(args[1]) : Path.of("fixtures/mapping/source_preview_mapping.xlsx");
        int sampleLimit = args.length > 2 ? Integer.parseInt(args[2]) : 3;

        var schema = new JenaOntologyService().load(Path.of("ontology/atm_knowledge_graph.ttl"));
        MappingCatalog catalog = new PoiMappingRegistry().load(mappingFile, schema);
        DefaultMappingEngine engine = new DefaultMappingEngine(catalog,
                new DeterministicIdentityResolver("urn:atm-knowledge-graph"));
        SourceConfig sources = SourceConfig.load(sourcesFile);

        System.out.println("==== SourceRecord → MappingResult 开发核验 ====");
        System.out.println("sources=" + sourcesFile.toAbsolutePath().normalize());
        System.out.println("mapping=" + mappingFile.toAbsolutePath().normalize());
        for (ConfiguredSource source : sources.getSources().values()) {
            source.getConfig().path("objects").fieldNames().forEachRemaining(objectName ->
                    preview(source, objectName, engine, sampleLimit));
        }
    }

    private static void preview(ConfiguredSource source, String objectName,
                                DefaultMappingEngine engine, int sampleLimit) {
        ExcelSourceAdapter adapter = new ExcelSourceAdapter(source, Path.of("."));
        List<MappingResult> results = new ArrayList<>();
        int records = 0;
        for (SourceRecord record : adapter.readAll(objectName)) {
            results.add(engine.map(record));
            records++;
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
        System.out.println("[" + objectName + "] sourceId=" + source.getSourceId());
        System.out.println("SourceRecord=" + records
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
    }
}
