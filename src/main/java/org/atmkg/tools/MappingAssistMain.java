package org.atmkg.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.core.spi.SourceFieldProvider;
import org.atmkg.infra.mapping.MappingAssist;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.infra.source.SourceAdapterFactory;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;

/** Human-in-the-loop helper for appending strict entity/property mapping candidates. */
public final class MappingAssistMain {
    private MappingAssistMain() {}

    public static void main(String[] args) {
        if (args.length != 0) {
            System.err.println("用法：MappingAssistMain（无参数）");
            return;
        }
        try {
            run(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException ex) {
            System.err.println("Mapping Assist 执行失败：" + ex.getMessage());
            System.exit(1);
        }
    }

    static void run(BufferedReader input) throws IOException {
        Path projectRoot = Path.of(".").toAbsolutePath().normalize();
        Path configFile = SourcePreviewMain.selectConfigFile();
        if (configFile == null) {
            System.out.println("未找到 config\\sources.local.yaml 或 config\\sources.yaml。");
            return;
        }
        SourceConfig sourceConfig = SourceConfig.load(configFile);
        List<SourceEntry> entries = sourceEntries(sourceConfig);
        System.out.println("==== Mapping Assist ====");
        System.out.println("数据源配置：" + configFile);
        if (entries.isEmpty()) {
            System.out.println("当前没有已配置的 sourceId / sourceObject。");
            return;
        }
        System.out.println();
        System.out.println("选择 sourceObject：");
        for (int i = 0; i < entries.size(); i++) {
            SourceEntry entry = entries.get(i);
            System.out.println((i + 1) + ". " + entry.source().getSourceId() + " / " + entry.sourceObject());
        }
        SourceEntry entry = entries.get(readChoice(input, entries.size(), "输入编号：") - 1);

        SourceAdapter adapter = new SourceAdapterFactory().create(entry.source(), projectRoot);
        if (!(adapter instanceof SourceFieldProvider provider)) {
            System.out.println("当前 Adapter 不支持结构字段发现，不读取业务数据。" );
            return;
        }
        List<String> fieldPaths = provider.fieldPaths(entry.sourceObject());
        System.out.println();
        System.out.println("检测到的字段路径（仅显示字段名，不显示业务数据值）：");
        if (fieldPaths.isEmpty()) {
            System.out.println("（未检测到可映射字段）");
            return;
        }
        for (int i = 0; i < fieldPaths.size(); i++) {
            System.out.println((i + 1) + ". " + fieldPaths.get(i));
        }

        OntologySchema schema = new JenaOntologyService().load(
                projectRoot.resolve("ontology").resolve("atm_knowledge_graph.ttl"));
        List<OntologyTerm> classes = schema.getClasses().values().stream()
                .sorted(Comparator.comparing(term -> MappingAssist.localName(term.getIri())))
                .toList();
        if (classes.isEmpty()) throw new IllegalStateException("正式本体中没有 Class");
        System.out.println();
        System.out.println("选择实体 Class（不会根据 sourceObject 自动猜测）：");
        for (int i = 0; i < classes.size(); i++) {
            OntologyTerm term = classes.get(i);
            String label = term.getLabel() == null || term.getLabel().isBlank() ? "（无中文 label）" : term.getLabel();
            System.out.println((i + 1) + ". " + label + " / " + MappingAssist.localName(term.getIri()));
        }
        OntologyTerm selectedClass = classes.get(readChoice(input, classes.size(), "输入编号：") - 1);

        System.out.println();
        System.out.println("选择业务主键字段（不会使用 sources.yaml keyFields 自动决定）：");
        for (int i = 0; i < fieldPaths.size(); i++) {
            System.out.println((i + 1) + ". " + fieldPaths.get(i));
        }
        String businessKey = fieldPaths.get(readChoice(input, fieldPaths.size(), "输入编号：") - 1);
        MappingAssist.Analysis analysis = MappingAssist.analyze(fieldPaths, selectedClass.getIri(), schema);
        printAnalysis(entry, selectedClass, businessKey, analysis);

        System.out.println();
        System.out.print("是否写入 mapping/字段映射.xlsx？[y/N] ");
        String confirmation = input.readLine();
        if (confirmation == null || !"y".equalsIgnoreCase(confirmation.trim())) {
            System.out.println("未写入 Mapping 工作簿。");
            return;
        }
        Path mappingFile = projectRoot.resolve("mapping").resolve("字段映射.xlsx");
        MappingAssist.WriteResult result = MappingAssist.write(mappingFile,
                entry.source().getSourceId(), entry.sourceObject(), selectedClass.getIri(),
                businessKey, analysis, schema);
        System.out.println("写入完成：实体新增 " + (result.entityAdded() ? 1 : 0)
                + " 行，属性新增 " + result.propertiesAdded() + " 行，已有属性保留 "
                + result.propertiesSkipped() + " 行。");
        if (result.existingEntityConflict()) {
            System.out.println("业务主键冲突：同一 sourceId/sourceObject/Class 已有不同业务主键的人工实体行，"
                    + "已保留且未写入任何候选，请人工处理。");
        }
        System.out.println("Mapping 工作簿结构已重新检查，当前目标 scope 校验通过。");
    }

    private static void printAnalysis(SourceEntry entry, OntologyTerm selectedClass, String businessKey,
                                      MappingAssist.Analysis analysis) {
        String className = MappingAssist.localName(selectedClass.getIri());
        System.out.println();
        System.out.println("【实体映射】");
        System.out.println(entry.source().getSourceId() + " | " + entry.sourceObject()
                + " | " + className + " | " + businessKey);
        System.out.println();
        System.out.println("【属性候选】");
        if (analysis.candidates().isEmpty()) System.out.println("（无）");
        for (MappingAssist.PropertyCandidate candidate : analysis.candidates()) {
            System.out.println(candidate.sourcePath() + " -> " + propertyText(candidate.property()));
        }
        System.out.println();
        System.out.println("【未匹配】");
        if (analysis.unmatched().isEmpty()) System.out.println("（无）");
        else analysis.unmatched().forEach(System.out::println);
        System.out.println();
        System.out.println("【歧义】");
        if (analysis.ambiguous().isEmpty()) System.out.println("（无）");
        for (MappingAssist.AmbiguousProperty ambiguous : analysis.ambiguous()) {
            System.out.println(ambiguous.sourcePath() + " -> "
                    + ambiguous.properties().stream().map(MappingAssistMain::propertyText).toList());
        }
    }

    private static String propertyText(OntologyTerm property) {
        String label = property.getLabel() == null ? "" : property.getLabel().trim();
        return MappingAssist.localName(property.getIri()) + (label.isEmpty() ? "" : " / " + label);
    }

    private static int readChoice(BufferedReader input, int size, String prompt) throws IOException {
        System.out.print(prompt);
        String value = input.readLine();
        try {
            int choice = Integer.parseInt(value == null ? "" : value.trim());
            if (choice < 1 || choice > size) throw new NumberFormatException();
            return choice;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("请输入 1 到 " + size + " 之间的数字编号");
        }
    }

    private static List<SourceEntry> sourceEntries(SourceConfig config) {
        List<SourceEntry> entries = new ArrayList<>();
        for (ConfiguredSource source : config.getSources().values()) {
            JsonNode objects = source.getConfig().path("objects");
            if (!objects.isObject()) continue;
            objects.fieldNames().forEachRemaining(name -> entries.add(new SourceEntry(source, name)));
        }
        return entries;
    }

    private record SourceEntry(ConfiguredSource source, String sourceObject) {}
}
