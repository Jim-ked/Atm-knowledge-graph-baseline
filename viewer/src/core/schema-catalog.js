const localName = value => {
  const text = String(value ?? '');
  const hash = text.lastIndexOf('#');
  const slash = text.lastIndexOf('/');
  const colon = text.lastIndexOf(':');
  return text.slice(Math.max(hash, slash, colon) + 1);
};

const labelMap = value => value && typeof value === 'object' ? value : {};

export class SchemaCatalog {
  constructor(schema = {}) {
    this.schema = structuredClone(schema ?? {});
    this.classLabels = labelMap(this.schema.classLabels);
    this.objectPropertyLabels = labelMap(this.schema.objectPropertyLabels);
    this.datatypePropertyLabels = labelMap(this.schema.datatypePropertyLabels);
  }

  #resolve(value, labels) {
    const text = String(value ?? '');
    if (Object.prototype.hasOwnProperty.call(labels, text)) return labels[text];
    const short = localName(text);
    const matches = Object.entries(labels).filter(([iri]) => localName(iri) === short);
    return matches.length === 1 ? matches[0][1] : (short || text);
  }

  classLabel(value) { return this.#resolve(value, this.classLabels); }
  classDisplayName(value) { return this.classLabel(value); }
  objectPropertyLabel(value) { return this.#resolve(value, this.objectPropertyLabels); }
  datatypePropertyLabel(value) { return this.#resolve(value, this.datatypePropertyLabels); }
  propertyLabel(value) { return this.datatypePropertyLabel(value); }
  nodeLabel(node) { return this.classLabel(node?.kind ?? node?.labels?.[0] ?? ''); }
  relationshipLabel(relationship) { return this.objectPropertyLabel(relationship?.type ?? relationship); }

  classOptions() {
    const classes = Array.isArray(this.schema.classes) ? this.schema.classes : Object.keys(this.classLabels);
    return [...new Set(classes)].sort((a, b) => this.classLabel(a).localeCompare(this.classLabel(b), 'zh-CN'))
      .map(value => ({ value, label: this.classLabel(value) }));
  }

  relationshipOptions() {
    const properties = Array.isArray(this.schema.objectProperties)
      ? this.schema.objectProperties : Object.keys(this.objectPropertyLabels);
    return [...new Set(properties)].sort((a, b) => this.objectPropertyLabel(a).localeCompare(this.objectPropertyLabel(b), 'zh-CN'))
      .map(value => ({ value, label: this.objectPropertyLabel(value) }));
  }
}

export { localName };
