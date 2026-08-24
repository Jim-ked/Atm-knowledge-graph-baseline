package org.atmkg.infra.source;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.atmkg.core.spi.SourceAdapter;
import org.atmkg.infra.source.config.ConfiguredSource;
import org.atmkg.infra.source.config.SourceConfig;
import org.atmkg.infra.source.excel.ExcelSourceAdapter;
import org.atmkg.infra.source.jdbc.JdbcSourceAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceAdapterFactoryTest {
    @TempDir Path temp;

    @Test
    void createsExcelAdapterFromConfiguredSource() throws Exception {
        ConfiguredSource source = source("""
                sources:
                  - sourceId: excel-main
                    adapter: excel
                    root: data
                    objects:
                      route:
                        files: '*.xlsx'
                        sheet: Route
                        keyFields: [ID]
                """, "excel-main");

        SourceAdapter adapter = new SourceAdapterFactory().create(source, temp);

        assertInstanceOf(ExcelSourceAdapter.class, adapter);
    }

    @Test
    void createsJdbcAdapterWithoutConnectingToOracle() throws Exception {
        ConfiguredSource source = source("""
                sources:
                  - sourceId: jdbc-main
                    adapter: jdbc
                    driver: oracle.jdbc.OracleDriver
                    url: jdbc:oracle:thin:@//example.invalid:1521/ATM
                    usernameEnv: PATH
                    passwordEnv: PATH
                    objects:
                      route:
                        table: ATM.ROUTE
                        keyFields: [ID]
                """, "jdbc-main");

        SourceAdapter adapter = new SourceAdapterFactory().create(source, temp);

        assertInstanceOf(JdbcSourceAdapter.class, adapter);
    }

    @Test
    void rejectsUnknownAdapterWithSourceIdentity() throws Exception {
        ConfiguredSource source = source("""
                sources:
                  - sourceId: csv-main
                    adapter: csv
                    objects: {}
                """, "csv-main");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SourceAdapterFactory().create(source, temp));

        assertTrue(failure.getMessage().contains("csv"));
        assertTrue(failure.getMessage().contains("csv-main"));
    }

    private ConfiguredSource source(String yaml, String sourceId) throws Exception {
        Path file = temp.resolve(sourceId + ".yaml");
        Files.writeString(file, yaml);
        return SourceConfig.load(file).requireSource(sourceId);
    }
}
