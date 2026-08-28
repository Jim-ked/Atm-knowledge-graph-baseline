package org.atmkg.core.model.mapping;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;

/** Scope-local diagnostics produced while inspecting a workbook. */
public final class MappingValidationReport {
    private final List<MappingIssue> issues;
    private final Set<MappingScope> discoveredScopes;

    public MappingValidationReport(List<MappingIssue> issues, Set<MappingScope> discoveredScopes) {
        this.issues = List.copyOf(issues);
        this.discoveredScopes = Collections.unmodifiableSet(new LinkedHashSet<>(discoveredScopes));
    }

    public List<MappingIssue> issues() { return issues; }
    public Set<MappingScope> discoveredScopes() { return discoveredScopes; }
    public List<MappingIssue> getIssues() { return issues; }
    public Set<MappingScope> getDiscoveredScopes() { return discoveredScopes; }

    public MappingScopeStatus status(MappingScope scope) {
        if (scope == null || !discoveredScopes.contains(scope)) return MappingScopeStatus.UNMAPPED;
        return issues.stream().anyMatch(issue -> scope.equals(issue.scope()))
                ? MappingScopeStatus.INVALID : MappingScopeStatus.VALID;
    }

    public List<MappingIssue> issuesFor(MappingScope scope) {
        List<MappingIssue> out = new ArrayList<>();
        for (MappingIssue issue : issues) if (scope != null && scope.equals(issue.scope())) out.add(issue);
        return List.copyOf(out);
    }
}
