package org.atmkg.core.spi;

import java.nio.file.Path;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.mapping.MappingCatalog;

public interface MappingRegistry {
    /** Load and normalize mappings against the current ontology schema. */
    MappingCatalog load(Path mappingFile, OntologySchema schema);

    void validate(MappingCatalog catalog, OntologySchema schema);

    /**
     * Synchronize ontology terms into the workbook without overwriting filled human mappings.
     * New terms are added as pending mappings; removed/renamed terms remain visible and are
     * reported by validation for human repair.
     */
    void refreshFromOntology(Path mappingFile, OntologySchema schema);
}
