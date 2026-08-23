package org.atmkg.infra.ontology;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.atmkg.core.error.OntologyLoadException;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.OntologyTerm;
import org.atmkg.core.spi.OntologyService;

public final class JenaOntologyService implements OntologyService {
    private static final String DESIGN_STATUS_IRI = "urn:atm-knowledge-graph:designStatus";

    @Override
    public OntologySchema load(Path ontologyFile) {
        if (ontologyFile == null || !Files.isRegularFile(ontologyFile)) {
            throw new OntologyLoadException("本体文件不存在：" + ontologyFile);
        }
        try {
            Model model = RDFDataMgr.loadModel(ontologyFile.toUri().toString());
            Map<String, OntologyTerm> classes = loadTerms(model, OWL.Class, TermKind.CLASS);
            Map<String, OntologyTerm> datatypeProperties = loadTerms(model, OWL.DatatypeProperty, TermKind.DATATYPE_PROPERTY);
            Map<String, OntologyTerm> objectProperties = loadTerms(model, OWL.ObjectProperty, TermKind.OBJECT_PROPERTY);
            if (classes.isEmpty()) throw new OntologyLoadException("本体未声明任何 owl:Class：" + ontologyFile);
            return new OntologySchema(classes, datatypeProperties, objectProperties);
        } catch (OntologyLoadException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new OntologyLoadException("本体读取失败：" + ontologyFile, ex);
        }
    }

    private Map<String, OntologyTerm> loadTerms(Model model, Resource rdfType, TermKind kind) {
        Map<String, OntologyTerm> result = new LinkedHashMap<>();
        StmtIterator it = model.listStatements(null, RDF.type, rdfType);
        try {
            while (it.hasNext()) {
                Resource subject = it.nextStatement().getSubject();
                if (!subject.isURIResource()) continue;
                String iri = subject.getURI();
                Set<String> domains = resourceUris(model.listObjectsOfProperty(subject, RDFS.domain));
                Set<String> ranges = resourceUris(model.listObjectsOfProperty(subject, RDFS.range));
                Set<String> supers = kind == TermKind.CLASS
                        ? resourceUris(model.listObjectsOfProperty(subject, RDFS.subClassOf))
                        : Set.of();
                result.put(iri, new OntologyTerm(iri, preferredLabel(model, subject), domains, ranges, supers,
                        annotationValue(model, subject, DESIGN_STATUS_IRI)));
            }
        } finally {
            it.close();
        }
        return result;
    }

    private String annotationValue(Model model, Resource resource, String propertyIri) {
        Statement statement = resource.getProperty(model.createProperty(propertyIri));
        return statement == null || !statement.getObject().isLiteral()
                ? null : statement.getObject().asLiteral().getString();
    }

    private Set<String> resourceUris(org.apache.jena.rdf.model.NodeIterator iterator) {
        Set<String> values = new LinkedHashSet<>();
        try {
            while (iterator.hasNext()) {
                RDFNode node = iterator.next();
                if (node.isURIResource()) values.add(node.asResource().getURI());
            }
        } finally {
            iterator.close();
        }
        return values;
    }

    private String preferredLabel(Model model, Resource resource) {
        Literal first = null;
        Literal noLanguage = null;
        StmtIterator it = model.listStatements(resource, RDFS.label, (RDFNode) null);
        try {
            while (it.hasNext()) {
                RDFNode node = it.nextStatement().getObject();
                if (!node.isLiteral()) continue;
                Literal literal = node.asLiteral();
                if (first == null) first = literal;
                String lang = literal.getLanguage();
                if ("zh-CN".equalsIgnoreCase(lang) || "zh".equalsIgnoreCase(lang)) return literal.getString();
                if (lang == null || lang.isBlank()) noLanguage = literal;
            }
        } finally {
            it.close();
        }
        if (noLanguage != null) return noLanguage.getString();
        return first == null ? null : first.getString();
    }

    private enum TermKind { CLASS, DATATYPE_PROPERTY, OBJECT_PROPERTY }
}
