package org.atmkg.tools;

import java.nio.file.Path;
import java.io.PrintStream;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.mapping.MappingIssue;
import org.atmkg.core.model.mapping.MappingScope;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.ontology.JenaOntologyService;

/** Read-only diagnostic for mapping scope status and located issues. */
public final class MappingCheckMain {
    private MappingCheckMain() {}

    public static void main(String[] args) {
        Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        root = root.toAbsolutePath().normalize();
        int code = run(root, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    static int run(Path root, PrintStream out, PrintStream err) {
        try {
            OntologySchema schema = new JenaOntologyService().load(root.resolve("ontology/atm_knowledge_graph.ttl"));
            var inspection = new PoiMappingRegistry().inspect(root.resolve("mapping/字段映射.xlsx"), schema);
            for (MappingScope scope : inspection.report().discoveredScopes()) {
                out.println(scope.sourceId() + "/" + scope.sourceObject() + " = "
                        + inspection.report().status(scope));
            }
            for (MappingIssue issue : inspection.report().issues()) {
                out.println(format(issue));
            }
            return inspection.report().issues().isEmpty() ? 0 : 1;
        } catch (RuntimeException ex) {
            err.println("Mapping 检查失败：" + ex.getMessage());
            return 2;
        }
    }

    private static String format(MappingIssue issue) {
        StringBuilder out = new StringBuilder("[").append(issue.mappingKind()).append("] ")
                .append(issue.sheetName()).append(" 第 ").append(issue.rowNumber()).append(" 行")
                .append(" sourceId=").append(issue.sourceId())
                .append(" sourceObject=").append(issue.sourceObject());
        append(out, "classIri", issue.classIri());
        append(out, "term", issue.term());
        append(out, "sourcePath/locator", issue.sourcePath());
        append(out, "expected", issue.expected());
        append(out, "actual", issue.actual());
        out.append(" 影响 scope=").append(issue.sourceId()).append('/').append(issue.sourceObject());
        out.append(" message=").append(issue.message());
        return out.toString();
    }

    private static void append(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) out.append(' ').append(label).append('=').append(value);
    }
}
