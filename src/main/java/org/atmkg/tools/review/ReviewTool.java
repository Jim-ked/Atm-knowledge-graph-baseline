package org.atmkg.tools.review;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.atmkg.core.ProjectConstants;
import org.atmkg.infra.neo4j.Neo4jConnectionSettings;
import org.atmkg.infra.neo4j.Neo4jDriverFactory;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/** 供人工核验使用的开发期控制台工具，只执行 {@code review/queries.yaml} 中的受控查询。 */
public final class ReviewTool {
    private ReviewTool() {}

    public static void main(String[] args) {
        Path root = args.length == 0 ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(args[0]).toAbsolutePath().normalize();
        ReviewQueryCatalog catalog = ReviewQueryCatalog.load(root.resolve("review/queries.yaml"));
        Neo4jConnectionSettings settings = Neo4jConnectionSettings.fromEnvironment(
                ProjectConstants.PROJECT_ID, 500);
        try (Driver driver = Neo4jDriverFactory.create(settings);
             BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(System.out, true)) {
            driver.verifyConnectivity();
            run(catalog, input, output, (template, cypher, parameters) -> {
                ReviewResultSummary summary = new ReviewResultSummary();
                try (Session session = driver.session(SessionConfig.forDatabase(settings.getDatabase()))) {
                    Result result = session.run(cypher, parameters);
                    while (result.hasNext()) summary.accept(result.next());
                }
                summary.print(output);
            });
        } catch (Exception ex) {
            System.err.println("ReviewTool 启动失败：" + rootCauseMessage(ex));
            System.exit(1);
        }
    }

    static void run(ReviewQueryCatalog catalog, BufferedReader input, PrintWriter output,
                    ReviewQueryExecution execution) throws IOException {
        List<ReviewQueryTemplate> templates = new ArrayList<>(catalog.templates().values());
        output.println("航管知识图谱人工 Review");
        while (true) {
            output.println();
            for (int i = 0; i < templates.size(); i++) {
                output.println((i + 1) + " " + templates.get(i).title());
            }
            output.println("0 退出");
            String selection = prompt(input, output, "请选择: ");
            if (selection == null || selection.equals("0")) return;
            int index;
            try {
                index = Integer.parseInt(selection) - 1;
            } catch (NumberFormatException ex) {
                output.println("无效选择：" + selection);
                continue;
            }
            if (index < 0 || index >= templates.size()) {
                output.println("无效选择：" + selection);
                continue;
            }
            ReviewQueryTemplate template = templates.get(index);
            try {
                Map<String, Object> parameters = collectParameters(
                        template, ProjectConstants.PROJECT_ID, input, output);
                Map<String, String> literals = collectLiterals(template, input, output);
                String cypher = template.render(literals);
                String browserCypher = BrowserCypherRenderer.render(cypher, parameters);
                output.println();
                output.println("模板: " + template.name() + " / " + template.title());
                output.println("Driver 参数化 Cypher:");
                output.println(cypher);
                execution.execute(template, cypher, parameters);
                output.println();
                output.println("---------------- Browser 可直接执行 ----------------");
                output.println(browserCypher);
                output.println("-----------------------------------------------------");
            } catch (Exception ex) {
                output.println("查询失败：" + rootCauseMessage(ex));
            }
        }
    }

    private static Map<String, Object> collectParameters(ReviewQueryTemplate template, String projectId,
                                                          BufferedReader input, PrintWriter output) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("project_id", projectId);
        if (template.locator() == ReviewQueryTemplate.Locator.SINGLE) {
            readLocator("", values, input, output);
        } else if (template.locator() == ReviewQueryTemplate.Locator.PAIR) {
            readLocator("start_", values, input, output);
            readLocator("target_", values, input, output);
        }
        for (String name : template.parameters()) {
            if (values.containsKey(name)) continue;
            String value = prompt(input, output, parameterPrompt(name));
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
            value = value.trim();
            validateTypedParameter(name, value);
            values.put(name, value);
        }
        return values;
    }

    private static void readLocator(String prefix, Map<String, Object> values,
                                    BufferedReader input, PrintWriter output) throws IOException {
        String role = prefix.equals("start_") ? "起点" : prefix.equals("target_") ? "终点" : "实体";
        String sourceKey = prompt(input, output, role + " source_key（推荐，留空则输入 kg_uid）: ");
        if (sourceKey == null) throw new IllegalArgumentException("输入已结束");
        sourceKey = sourceKey.trim();
        String uid = "";
        if (sourceKey.isEmpty()) {
            uid = prompt(input, output, role + " kg_uid: ");
            if (uid == null || uid.isBlank()) throw new IllegalArgumentException(role + "定位值不能为空");
            uid = uid.trim();
        }
        values.put(prefix + "source_key", sourceKey);
        values.put(prefix + "kg_uid", uid);
    }

    private static Map<String, String> collectLiterals(ReviewQueryTemplate template,
                                                        BufferedReader input, PrintWriter output) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : template.literalParameters()) {
            String defaultValue = name.equals("max_depth") ? "5" : "2";
            String value = prompt(input, output, literalPrompt(name) + " [" + defaultValue + "]: ");
            values.put(name, value == null || value.isBlank() ? defaultValue : value.trim());
        }
        return values;
    }

    private static String parameterPrompt(String name) {
        if (name.equals("relationship_type")) return "关系类型（Neo4j type，例如 HAS_RUNWAY）: ";
        if (name.equals("entity_type")) return "实体类型（ontology Label，例如 Airport）: ";
        return name + ": ";
    }

    private static String literalPrompt(String name) {
        if (name.equals("depth")) return "K 跳深度";
        if (name.equals("max_depth")) return "最大路径深度";
        return name;
    }

    private static void validateTypedParameter(String name, String value) {
        if (name.equals("relationship_type") && !value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("relationship_type 必须是大写 Neo4j Relationship Type");
        }
        if (name.equals("entity_type") && !value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("entity_type 必须是合法 ontology Label");
        }
    }

    private static String prompt(BufferedReader input, PrintWriter output, String text) throws IOException {
        output.print(text);
        output.flush();
        return input.readLine();
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface ReviewQueryExecution {
        void execute(ReviewQueryTemplate template, String cypher, Map<String, Object> parameters) throws Exception;
    }
}
