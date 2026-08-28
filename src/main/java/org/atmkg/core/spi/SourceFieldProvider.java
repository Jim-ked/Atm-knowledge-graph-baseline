package org.atmkg.core.spi;

import java.util.List;

/** Provides physical/logical field paths without reading business record values. */
public interface SourceFieldProvider {
    List<String> fieldPaths(String objectName);
}
