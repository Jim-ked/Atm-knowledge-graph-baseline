package org.atmkg.tools;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.atmkg.api.http.ApiConfig;
import org.atmkg.api.http.KgApiServer;
import org.atmkg.core.ProjectConstants;
import org.atmkg.core.spi.QueryService;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.atmkg.infra.neo4j.Neo4jDriverFactory;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.infra.query.Neo4jQueryService;
import org.atmkg.service.change.ChangeQueryRuleRegistry;
import org.atmkg.service.change.GraphChangeAssociationProjector;
import org.atmkg.service.change.GraphChangeConsoleReporter;
import org.atmkg.service.change.GraphChangeNeighborhoodProjector;
import org.atmkg.service.change.GraphChangeProcessor;
import org.atmkg.service.query.QueryTemplateRegistry;
import org.atmkg.service.query.TemplateAwareQueryService;
import org.atmkg.service.sync.GraphChangeNotice;
import org.atmkg.service.sync.SyncRuntime;
import org.neo4j.driver.Driver;

/**
 * 服务启动/关闭顺序出问题时才改这里。新增源表去 {@code config/sources.yaml}，新增本体字段去
 * {@code ontology/atm_knowledge_graph.ttl + mapping/字段映射.xlsx}，新增 named query 去
 * {@code queries/query-templates.yaml}；这些需求都不应修改本类。
 *
 * <p>本类固定加载 API、正式 TTL、query templates 和 change-query-rules，再装配 QueryService、GraphChange、
 * HTTP/Viewer/SyncRuntime。GraphChange 当前只把简短摘要写到控制台，不是 durable sink；人工 sync.cmd 也不走
 * 这条变化输出链。启动失败先看控制台发生在规则加载、Driver、SyncRuntimeAssembler 还是 server.start。
 */
public final class KgServiceMain {
    private KgServiceMain() {}

    public static void main(String[] args) {
        Path root = args.length == 0
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(args[0]).toAbsolutePath().normalize();
        ApiConfig api = ApiConfig.load(root.resolve("config/api.yaml"));
        var schema = new JenaOntologyService().load(root.resolve("ontology/atm_knowledge_graph.ttl"));
        QueryTemplateRegistry queryTemplates = QueryTemplateRegistry.load(
                root.resolve("queries/query-templates.yaml"));
        Neo4jConnectionSettings neo4j = Neo4jConnectionSettings.fromEnvironment(
                ProjectConstants.PROJECT_ID, 500);
        Driver driver = Neo4jDriverFactory.create(neo4j);
        QueryService queryService = new TemplateAwareQueryService(
                new Neo4jQueryService(driver, neo4j, api.getSchemaVersion()), queryTemplates);
        SyncRuntime syncRuntime;
        KgApiServer server;
        try {
            Consumer<GraphChangeNotice> graphChange = graphChangeListener(root, queryService, System.out);
            syncRuntime = SyncRuntimeAssembler.assemble(root, schema, driver, neo4j, graphChange);
            server = new KgApiServer(api, queryService, schema, () -> {
                try {
                    driver.verifyConnectivity();
                    return true;
                } catch (RuntimeException ex) {
                    return false;
                }
            });
        } catch (RuntimeException ex) {
            driver.close();
            throw ex;
        }
        Path viewer = root.resolve("viewer/dist");
        if (java.nio.file.Files.isDirectory(viewer)) server.mountStatic("/viewer", viewer);
        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                try {
                    syncRuntime.close();
                } finally {
                    try {
                        server.close();
                    } finally {
                        driver.close();
                        shutdown.countDown();
                    }
                }
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(close, "atmkg-service-shutdown"));
        try {
            server.start();
            syncRuntime.start();
        } catch (RuntimeException ex) {
            close.run();
            throw ex;
        }
        System.out.println("kg-service started: http://" + api.getHost() + ":" + server.getAddress().getPort()
                + api.getBasePath());
        System.out.println(syncRuntime.isEnabled()
                ? "sync runtime configured; polling=" + syncRuntime.isPollingEnabled()
                : "sync runtime disabled: query/API-only mode");
        if (java.nio.file.Files.isDirectory(viewer)) {
            System.out.println("viewer started: http://" + api.getHost() + ":" + server.getAddress().getPort()
                    + "/viewer/");
        }
        try {
            shutdown.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            close.run();
        }
    }

    static Consumer<GraphChangeNotice> graphChangeListener(Path root, QueryService queryService,
                                                           PrintStream output) {
        ChangeQueryRuleRegistry rules = ChangeQueryRuleRegistry.load(
                root.resolve("queries/change-query-rules.yaml"));
        return new GraphChangeProcessor(
                new GraphChangeNeighborhoodProjector(queryService),
                new GraphChangeAssociationProjector(queryService, rules),
                new GraphChangeConsoleReporter(output));
    }
}
