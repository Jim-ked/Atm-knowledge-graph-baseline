package org.atmkg.core.spi;

import java.nio.file.Path;
import org.atmkg.core.model.OntologySchema;

public interface OntologyService {
    OntologySchema load(Path ontologyFile);
}
