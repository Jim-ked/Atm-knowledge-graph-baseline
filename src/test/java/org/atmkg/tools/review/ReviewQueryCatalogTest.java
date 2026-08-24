package org.atmkg.tools.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewQueryCatalogTest {
    @Test
    void loadsTheSingleReviewTemplateCatalogWithoutFixtureSpecificBrowserParameters() {
        ReviewQueryCatalog catalog = ReviewQueryCatalog.load(Path.of("review/queries.yaml"));

        assertEquals(8, catalog.templates().size());
        assertEquals(ReviewQueryTemplate.Locator.SINGLE, catalog.template("entity").locator());
        assertEquals(ReviewQueryTemplate.Locator.PAIR, catalog.template("path").locator());

        String source = catalog.sourceText().toLowerCase();
        assertFalse(source.contains("browser_parameters"));
        assertFalse(source.contains("z001"));
        assertFalse(source.contains("z002"));
        assertFalse(source.contains("r001"));
        assertTrue(source.contains("kg_source_key"));
        assertTrue(source.contains("kg_uid"));
    }

    @Test
    void rendersOnlyValidatedPositiveIntegerLiterals() {
        ReviewQueryTemplate template = ReviewQueryCatalog.load(Path.of("review/queries.yaml")).template("k_hop");

        assertTrue(template.render(Map.of("depth", "2")).contains("[*0..2]"));
        assertThrows(IllegalArgumentException.class, () -> template.render(Map.of("depth", "0")));
        assertThrows(IllegalArgumentException.class, () -> template.render(Map.of("depth", "2] DELETE n //")));
    }

    @Test
    void rejectsCypherParametersNotDeclaredByTheTemplate() {
        assertThrows(IllegalArgumentException.class, () -> new ReviewQueryTemplate(
                "broken", "Broken", ReviewQueryTemplate.Locator.NONE,
                List.of("project_id"), List.of(), "RETURN $undeclared"));
    }
}
