package org.atmkg.tools;

import java.nio.file.Path;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.infra.mapping.PoiMappingRegistry;
import org.atmkg.infra.ontology.JenaOntologyService;

/** Minimal offline-friendly check entry point for ontology + mapping. */
public final class Phase1CheckMain {
    private Phase1CheckMain() {}

    public static void main(String[] args) {
        Path ontology = Path.of("ontology/atm_knowledge_graph.ttl");
        Path mapping = Path.of("mapping/字段映射.xlsx");
        boolean refresh = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ontology": ontology = Path.of(requireValue(args, ++i, "--ontology")); break;
                case "--mapping": mapping = Path.of(requireValue(args, ++i, "--mapping")); break;
                case "--refresh": refresh = true; break;
                default: throw new IllegalArgumentException("未知参数：" + args[i]);
            }
        }

        JenaOntologyService ontologyService = new JenaOntologyService();
        PoiMappingRegistry mappingRegistry = new PoiMappingRegistry();
        OntologySchema schema = ontologyService.load(ontology);
        if (refresh) mappingRegistry.refreshFromOntology(mapping, schema);
        MappingCatalog catalog = mappingRegistry.load(mapping, schema);

        System.out.println("ontology classes=" + schema.getClasses().size()
                + " datatypeProperties=" + schema.getDatatypeProperties().size()
                + " objectProperties=" + schema.getObjectProperties().size());
        System.out.println("active mappings entities=" + catalog.getEntities().size()
                + " properties=" + catalog.getProperties().size()
                + " relationships=" + catalog.getRelationships().size());
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException(option + " 缺少值");
        return args[index];
    }
}
