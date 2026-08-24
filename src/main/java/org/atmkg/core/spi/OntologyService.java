package org.atmkg.core.spi;

import java.nio.file.Path;
import org.atmkg.core.model.OntologySchema;

/** 读取正式 TTL 并形成 OntologySchema；不负责字段 mapping、UID 或图写入。 */
public interface OntologyService {
    OntologySchema load(Path ontologyFile);
}
