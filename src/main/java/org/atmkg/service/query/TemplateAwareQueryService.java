package org.atmkg.service.query;

import java.util.Objects;
import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.QuerySpec;
import org.atmkg.core.spi.QueryService;

/** Resolves configured NAMED requests, while leaving ordinary QuerySpec execution unchanged. */
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
