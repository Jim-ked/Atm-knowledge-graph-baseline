package org.atmkg.service.query;

import java.util.Objects;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.spi.QueryService;

/**
 * 新增/修改 named query 只改 {@code queries/query-templates.yaml}，不要在这里写 queryId switch。
 * 本类只把 NAMED 的 queryId + startUid 交给 QueryTemplateRegistry，再把普通 QuerySpec 委托下层。
 *
 * <p>只有 NAMED 装饰/委托契约本身变化才写 Java。把模板逻辑塞进 Neo4jQueryService 会形成第二套 registry；
 * 非 NAMED 行为异常时直接查 delegate，NAMED 未知时先查 YAML queryId。
 */
public final class TemplateAwareQueryService implements QueryService {
    private final QueryService delegate;
    private final QueryTemplateRegistry registry;

    public TemplateAwareQueryService(QueryService delegate, QueryTemplateRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public GraphDTO query(QuerySpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.getType() != QuerySpec.Type.NAMED) return delegate.query(spec);
        return delegate.query(registry.resolve(spec.getQueryId(), spec.getStartUid()));
    }
}
