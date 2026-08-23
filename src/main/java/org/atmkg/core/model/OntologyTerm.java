package org.atmkg.core.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class OntologyTerm {
    private final String iri;
    private final String label;
    private final Set<String> domains;
    private final Set<String> ranges;
    private final Set<String> superClasses;
    private final String designStatus;

    public OntologyTerm(String iri, String label, Set<String> domains, Set<String> ranges, Set<String> superClasses) {
        this(iri, label, domains, ranges, superClasses, null);
    }

    public OntologyTerm(String iri, String label, Set<String> domains, Set<String> ranges,
                        Set<String> superClasses, String designStatus) {
        this.iri = Objects.requireNonNull(iri);
        this.label = label;
        this.domains = immutable(domains);
        this.ranges = immutable(ranges);
        this.superClasses = immutable(superClasses);
        this.designStatus = designStatus;
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    public String getIri() { return iri; }
    public String getLabel() { return label; }
    public Set<String> getDomains() { return domains; }
    public Set<String> getRanges() { return ranges; }
    public Set<String> getSuperClasses() { return superClasses; }
    public String getDesignStatus() { return designStatus; }
}
