package org.atmkg.infra.mapping;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.atmkg.core.model.OntologyTerm;

final class MappingIriResolver {
    private MappingIriResolver() {}

    static String resolve(String raw, Map<String, OntologyTerm> allowed) {
        String value = trim(raw);
        if (value.isEmpty() || isPending(value)) return value;
        if (allowed.containsKey(value)) return value;
        String local = localName(value);
        List<String> matches = allowed.keySet().stream()
                .filter(iri -> localName(iri).equals(local))
                .collect(Collectors.toList());
        return matches.size() == 1 ? matches.get(0) : value;
    }

    static String compact(String iri) {
        if (iri == null) return "";
        int hash = iri.lastIndexOf('#');
        int slash = iri.lastIndexOf('/');
        int colon = iri.lastIndexOf(':');
        int pos = Math.max(hash, Math.max(slash, colon));
        return pos >= 0 && pos + 1 < iri.length() ? iri.substring(pos + 1) : iri;
    }

    static boolean isPending(String value) { return "[待映射]".equals(trim(value)); }
    static String trim(String value) { return value == null ? "" : value.trim(); }

    private static String localName(String value) {
        String v = trim(value);
        int hash = v.lastIndexOf('#');
        int slash = v.lastIndexOf('/');
        int colon = v.lastIndexOf(':');
        int pos = Math.max(hash, Math.max(slash, colon));
        return pos >= 0 && pos + 1 < v.length() ? v.substring(pos + 1) : v;
    }
}
