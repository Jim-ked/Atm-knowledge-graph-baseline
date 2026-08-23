package org.atmkg.tools.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BrowserCypherRendererTest {
    @Test
    void expandsDriverParametersAsEscapedCypherLiteralsForDisplayOnly() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("text", "O'Brien\\North\nLine");
        parameters.put("count", 2);
        parameters.put("enabled", true);
        parameters.put("nothing", null);

        String rendered = BrowserCypherRenderer.render(
                "RETURN $text AS text, $count AS count, $enabled AS enabled, $nothing AS nothing",
                parameters);

        assertEquals("RETURN 'O\\'Brien\\\\North\\nLine' AS text, 2 AS count, true AS enabled, null AS nothing", rendered);
        assertFalse(rendered.contains("$text"));
    }

    @Test
    void rejectsMissingOrUnsupportedDisplayParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> BrowserCypherRenderer.render("RETURN $missing", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> BrowserCypherRenderer.render("RETURN $items", Map.of("items", java.util.List.of("x"))));
    }
}
