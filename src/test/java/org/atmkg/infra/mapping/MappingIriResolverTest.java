package org.atmkg.infra.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.atmkg.core.error.MappingValidationException;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.model.mapping.EntityMappingSpec;
import org.atmkg.core.model.mapping.MappingCatalog;
import org.atmkg.core.model.mapping.PropertyMappingSpec;
import org.junit.jupiter.api.Test;

class MappingIriResolverTest {
    @Test
    void obsoleteCompleteClassIriRemainsUnchangedAndFailsValidation() {
        OntologySchema schema = schema(
                Map.of("urn:new:Airport", term("urn:new:Airport")),
                Map.of());

        String resolved = MappingIriResolver.resolve("urn:old:Airport", schema.getClasses());

        assertEquals("urn:old:Airport", resolved);
        MappingCatalog catalog = new MappingCatalog(
                List.of(new EntityMappingSpec(resolved, "source", "airport", "airportCode")),
                List.of(),
                List.of());
        MappingValidationException error = assertThrows(
                MappingValidationException.class, () -> new PoiMappingRegistry().validate(catalog, schema));
        assertTrue(error.getMessage().contains("未知实体类 urn:old:Airport"));
    }

    @Test
    void uniqueLocalNameShorthandStillResolves() {
        Map<String, OntologyTerm> classes = Map.of(
                "urn:atm-knowledge-graph:Airport", term("urn:atm-knowledge-graph:Airport"));

        assertEquals("urn:atm-knowledge-graph:Airport", MappingIriResolver.resolve("Airport", classes));
    }

    @Test
    void ambiguousLocalNameShorthandRemainsUnresolved() {
        Map<String, OntologyTerm> classes = Map.of(
                "urn:first:Airport", term("urn:first:Airport"),
                "urn:second:Airport", term("urn:second:Airport"));

        assertEquals("Airport", MappingIriResolver.resolve("Airport", classes));
    }

    @Test
    void removedDatatypePropertyRemainsUnchangedAndFailsValidation() {
        String classIri = "urn:atm-knowledge-graph:Airport";
        OntologySchema schema = schema(
                Map.of(classIri, term(classIri)),
                Map.of("urn:new:airportName", property("urn:new:airportName", classIri)));
        String resolved = MappingIriResolver.resolve("urn:old:airportName", schema.getDatatypeProperties());
        MappingCatalog catalog = new MappingCatalog(
                List.of(),
                List.of(new PropertyMappingSpec(
                        classIri, resolved, "source", "airport", "airportName", "", false)),
                List.of());

        assertEquals("urn:old:airportName", resolved);
        MappingValidationException error = assertThrows(
                MappingValidationException.class, () -> new PoiMappingRegistry().validate(catalog, schema));
        assertTrue(error.getMessage().contains("未知数据属性 urn:old:airportName"));
    }

    private OntologySchema schema(Map<String, OntologyTerm> classes, Map<String, OntologyTerm> datatypeProperties) {
        return new OntologySchema(classes, datatypeProperties, Map.of());
    }

    private OntologyTerm term(String iri) {
        return new OntologyTerm(iri, null, Set.of(), Set.of(), Set.of());
    }

    private OntologyTerm property(String iri, String domain) {
        return new OntologyTerm(iri, null, Set.of(domain), Set.of(), Set.of());
    }
}
