package org.atmkg.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OntologyCheckMainTest {
    @TempDir Path temp;

    @Test
    void validOntologyReturnsZero() throws Exception {
        Path ttl = temp.resolve("ok.ttl");
        Files.writeString(ttl, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix ex: <urn:ex:> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "ex:A a owl:Class . ex:p a owl:ObjectProperty ; rdfs:domain ex:A ; rdfs:range ex:A .\n");
        assertEquals(0, OntologyCheckMain.check(ttl));
    }

    @Test
    void missingClassReferenceReturnsOne() throws Exception {
        Path ttl = temp.resolve("bad.ttl");
        Files.writeString(ttl, "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix ex: <urn:ex:> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "ex:A a owl:Class . ex:p a owl:ObjectProperty ; rdfs:domain ex:Missing .\n");
        assertEquals(1, OntologyCheckMain.check(ttl));
    }

    @Test
    void missingFileReturnsTwo() {
        assertEquals(2, OntologyCheckMain.check(temp.resolve("none.ttl")));
    }
}
