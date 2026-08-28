package org.atmkg.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.infra.ontology.JenaOntologyService;

/** Standalone syntax and reference check for the formal ontology TTL. */
public final class OntologyCheckMain {
    private static final Path DEFAULT = Path.of("ontology", "atm_knowledge_graph.ttl");
    private OntologyCheckMain() {}

    public static void main(String[] args) {
        Path ontology = DEFAULT;
        if (args.length == 2 && "--ontology".equals(args[0])) ontology = Path.of(args[1]);
        else if (args.length != 0) { System.err.println("用法：OntologyCheckMain [--ontology <path>]"); System.exit(2); }
        System.exit(check(ontology));
    }

    static int check(Path ontology) {
        try {
            if (!Files.isRegularFile(ontology)) { System.err.println("本体文件不存在：" + ontology); return 2; }
            OntologySchema schema = new JenaOntologyService().load(ontology);
            Model model = RDFDataMgr.loadModel(ontology.toUri().toString());
            System.out.println("classes=" + schema.getClasses().size());
            System.out.println("datatypeProperties=" + schema.getDatatypeProperties().size());
            System.out.println("objectProperties=" + schema.getObjectProperties().size());
            int issues = 0;
            issues += checkReferences(model, schema, RDFS.subClassOf, "subClassOf", new Resource[0]);
            issues += checkReferences(model, schema, RDFS.domain, "domain", OWL.DatatypeProperty, OWL.ObjectProperty);
            issues += checkReferences(model, schema, RDFS.range, "range", OWL.ObjectProperty);
            return issues == 0 ? 0 : 1;
        } catch (RuntimeException ex) {
            System.err.println("本体检查失败：" + ex.getMessage()); return 2;
        }
    }

    private static int checkReferences(Model model, OntologySchema schema, org.apache.jena.rdf.model.Property predicate,
                                       String name, Resource... allowedTypes) {
        int issues = 0;
        StmtIterator it = model.listStatements(null, predicate, (RDFNode) null);
        try {
            while (it.hasNext()) {
                var statement = it.nextStatement();
                Resource subject = statement.getSubject();
                RDFNode object = statement.getObject();
                if (allowedTypes != null && allowedTypes.length > 0) {
                    boolean allowed = false;
                    for (Resource type : allowedTypes) if (model.contains(subject, RDF.type, type)) allowed = true;
                    if (!allowed) continue;
                }
                if (!object.isURIResource()) continue;
                String iri = object.asResource().getURI();
                if (!schema.hasClass(iri)) {
                    System.err.println("issue " + name + ": " + subject.getURI() + " -> " + iri);
                    issues++;
                }
            }
        } finally { it.close(); }
        return issues;
    }
}
