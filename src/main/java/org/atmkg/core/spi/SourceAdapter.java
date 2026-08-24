package org.atmkg.core.spi;

import java.time.Instant;
import java.util.Optional;
import org.atmkg.core.model.SourceRecord;

/**
 * 只负责把物理数据源读取为 SourceRecord，不决定本体、字段映射或图存储语义。
 * 新增普通表、Sheet 或文件应先改 {@code config/sources.yaml}，不要扩展本接口加入业务字段。
 */
public interface SourceAdapter {
    Iterable<SourceRecord> readAll(String objectName);
    Optional<SourceRecord> readByKey(String objectName, String sourceKey);
    Iterable<SourceRecord> scanChangedSince(String objectName, Instant since);
}
