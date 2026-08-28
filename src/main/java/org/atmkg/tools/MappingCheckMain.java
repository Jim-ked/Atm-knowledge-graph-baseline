package org.atmkg.tools;

import java.nio.file.Path;
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
        try {
            OntologySchema schema = new JenaOntologyService().load(root.resolve("ontology/atm_knowledge_graph.ttl"));
            var inspection = new PoiMappingRegistry().inspect(root.resolve("mapping/字段映射.xlsx"), schema);
            for (MappingScope scope : inspection.report().discoveredScopes()) {
                System.out.println(scope.sourceId() + "/" + scope.sourceObject() + " = "
                        + inspection.report().status(scope));
            }
            for (MappingIssue issue : inspection.report().issues()) {
                System.out.println("[" + issue.mappingKind() + "] 第 " + issue.rowNumber() + " 行 "
                        + issue.sheetName() + " sourceId=" + issue.sourceId()
                        + " sourceObject=" + issue.sourceObject() + "：" + issue.message());
            }
        } catch (RuntimeException ex) {
            System.err.println("Mapping 检查失败：" + ex.getMessage());
            System.exit(1);
        }
    }
}
