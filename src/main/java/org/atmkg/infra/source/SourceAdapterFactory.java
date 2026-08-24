package org.atmkg.infra.source;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.excel.ExcelSourceAdapter;
import org.atmkg.infra.source.jdbc.JdbcSourceAdapter;

/**
 * 根据 {@code sources.yaml} 已解析配置创建正式 SourceAdapter。
 *
 * <p>只有项目正式增加一种新的 SourceAdapter 类型时才修改本类。新增 Excel 文件、Sheet、Oracle 表或
 * View，以及新增字段、修改 TTL/Mapping，都仍然只修改对应配置和人工语义文件。
 */
public final class SourceAdapterFactory {
    public boolean supports(String adapter) {
        return typeOf(adapter) != null;
    }

    public SourceAdapter create(ConfiguredSource source, Path projectRoot) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(projectRoot, "projectRoot");
        AdapterType type = typeOf(source.getAdapter());
        if (type == null) {
            throw new IllegalArgumentException("未知 SourceAdapter：" + source.getAdapter()
                    + " @ " + source.getSourceId());
        }
        return switch (type) {
            case EXCEL -> new ExcelSourceAdapter(source, projectRoot);
            case JDBC -> new JdbcSourceAdapter(source);
        };
    }

    private AdapterType typeOf(String adapter) {
        if (adapter == null || adapter.isBlank()) return null;
        try {
            return AdapterType.valueOf(adapter.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private enum AdapterType { EXCEL, JDBC }
}
