package org.atmkg.tools;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.atmkg.api.http.ApiConfig;
import org.atmkg.api.http.KgApiServer;
import org.atmkg.core.spi.QueryService;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.atmkg.infra.neo4j.Neo4jDriverFactory;
import org.atmkg.infra.ontology.JenaOntologyService;
import org.atmkg.infra.query.Neo4jQueryService;
import org.atmkg.service.query.QueryTemplateRegistry;
import org.atmkg.service.query.TemplateAwareQueryService;
import org.atmkg.service.sync.SyncRuntime;
import org.neo4j.driver.Driver;

/**
 * 服务启动/关闭顺序出问题时才改这里。新增源表去 {@code config/sources.yaml}，新增本体字段去
 * {@code ontology/atm_knowledge_graph.ttl + mapping/字段映射.xlsx}，新增 named query 去
 * {@code queries/query-templates.yaml}；这些需求都不应修改本类。
 *
 * <p>本类固定加载 {@code config/api.yaml}、正式 TTL 和 {@code queries/query-templates.yaml}，再装配
 * HTTP/Viewer/SyncRuntime。只有要正式接入新的进程级组件、改变这些固定路径或修复资源关闭顺序才写 Java。
 * 误把 sources.local/fixture 或 GraphChangeAssociationProjector 接进来会改变生产入口；后者当前没有
 * outward sink。启动失败先看控制台发生在 Driver、SyncRuntimeAssembler 还是 server.start。
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
        Neo4jConnectionSettings neo4j = Neo4jConnectionSettings.fromEnvironment("atm-knowledge-graph", 500);
        Driver driver = Neo4jDriverFactory.create(neo4j);
        QueryService queryService = new TemplateAwareQueryService(
                new Neo4jQueryService(driver, neo4j, api.getSchemaVersion()), queryTemplates);
        SyncRuntime syncRuntime;
        KgApiServer server;
        try {
            syncRuntime = SyncRuntimeAssembler.assemble(root, schema, driver, neo4j);
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
}
