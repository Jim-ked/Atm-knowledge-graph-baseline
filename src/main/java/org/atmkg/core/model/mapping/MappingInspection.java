package org.atmkg.core.model.mapping;

import java.util.Objects;

/** Parsed workbook plus scope-local diagnostics. */
public record MappingInspection(MappingCatalog validCatalog, MappingValidationReport report) {
    public MappingInspection {
        Objects.requireNonNull(validCatalog, "validCatalog");
        Objects.requireNonNull(report, "report");
    }

    public MappingCatalog getValidCatalog() { return validCatalog; }
    public MappingValidationReport getReport() { return report; }
}
