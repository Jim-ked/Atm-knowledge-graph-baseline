# Cytoscape Plugin Matrix

基线：`cytoscape@3.34.1`。版本和 peer dependency 来自 npm package metadata；用途/API 依据各包随 npm 发布的 README。所有扩展只属于 Cytoscape runtime，不写入 GraphDTO。

| package | version | license | peer dependency | maintenance/status | purpose | installed | enabled | debug only | ownership | conflict | verification |
|---|---:|---|---|---|---|---|---|---|---|---|---|
| cytoscape-fcose | 2.2.0 | MIT | ^3.2.0 | stable candidate | primary full/incremental layout | yes | AUTO/FCOSE | no | CytoscapeLayoutPolicy | none | runtime Gate |
| cytoscape-layout-utilities | 1.1.1 | MIT | ^3.2.0 | maintained candidate | placeNewNodes/packComponents | yes | expand + fCoSE | no | CytoscapeLayoutPolicy | none | runtime Gate |
| cytoscape-d3-force | 1.1.4 | MIT | ^3.2.0 | candidate | force comparison | yes | manual policy | yes | CytoscapeLayoutPolicy | no primary ownership | layout Gate |
| cytoscape-cola | 2.5.1 | MIT | ^3.2.0 | candidate | constraints/flow comparison | yes | manual policy | yes | CytoscapeLayoutPolicy | no primary ownership | layout Gate |
| cytoscape-elk | 2.3.0 | MIT | >=3.2.0 | maintained candidate | layered/mrtree/stress DAG/tree comparison | yes | AUTO for detected DAG + manual | yes | CytoscapeLayoutPolicy | no primary ownership | layout Gate |
| cytoscape-dagre | 4.0.0 | MIT | ^3.2.22 | maintained candidate | DAG/tree rank layout | yes | manual policy | yes | CytoscapeLayoutPolicy | no primary ownership | layout Gate |
| cytoscape-view-utilities | 6.0.0 | MIT | ^3.2.0 | maintained candidate | highlight/hide/show | yes | yes | no | view-utilities | excludes GraphModel data | interaction Gate |
| cytoscape-undo-redo | 1.3.3 | MIT | ^3.3.0 | maintained candidate | workspace undo/redo | yes | yes | no | undo-redo | never API requery | interaction Gate |
| cytoscape-context-menus | 4.2.1 | MIT | ^2.7.0 \|\| ^3.0.0 | maintained candidate | node/edge actions | yes | debug shell | yes | context-menus | calls shared actions only | interaction Gate |
| cytoscape-popper | 4.0.1 | MIT | ^3.2.0 | maintained candidate | element tooltip anchor | yes | debug shell | yes | popper + Floating UI | no properties duplication | interaction Gate |
| @floating-ui/dom | 1.8.0 | MIT | n/a | maintained | tooltip positioning | yes | via popper | yes | popper + Floating UI | none | tooltip Gate |
| cytoscape-navigator | 2.0.2 | MIT | ^2.6.0 \|\| ^3.0.0 | candidate | large-graph overview | yes | node threshold >50 | no | navigator | throttled updates | navigator Gate |
| cytoscape-cise | 2.0.1 | MIT | ^3.2.0 | debug candidate | clustered circular layout | yes | manual policy | yes | CytoscapeLayoutPolicy | no primary ownership | layout Gate |
| cytoscape-avsdf | 1.0.0 | MIT | ^3.2.0 | debug candidate | circular layout | yes | manual policy | yes | CytoscapeLayoutPolicy | no primary ownership | layout Gate |
| cytoscape-spread | 3.0.0 | MIT | ^3.0.0 | debug candidate | spread/overlap comparison | yes | manual policy | yes | CytoscapeLayoutPolicy | no primary ownership | layout Gate |
| cytoscape-automove | 1.10.3 | MIT | ^3.2.0 | debug candidate | explicit drag-follow probe | yes | opt-in debug probe | yes | automove | never default | lifecycle Gate |
| cytoscape-expand-collapse | 4.1.1 | MIT | ^3.3.0 | **deprecated: README says no longer maintained** | visual collapse compatibility check | yes | compatibility Debug only | yes | plugin visual state only | never API expand/collapse | compatibility Gate |
| cytoscape.js-complexity-management | unpublished | n/a | n/a | not available on npm | investigated only | no | no | yes | none | cannot be a runtime dependency | npm lookup: 404 |

正式 ownership：API expand/collapse 仍由 `GraphModel`；Cytoscape expand-collapse 只做兼容 Debug；hide/show/highlight 由 view-utilities；undo/redo 由 undo-redo；tooltip 由 popper + Floating UI；layout 由 `CytoscapeLayoutPolicy`。

官方参考：

- https://js.cytoscape.org/
- https://github.com/iVis-at-Bilkent/cytoscape.js-fcose
- https://github.com/iVis-at-Bilkent/cytoscape.js-layout-utilities
- https://github.com/iVis-at-Bilkent/cytoscape.js-view-utilities
- https://github.com/iVis-at-Bilkent/cytoscape.js-undo-redo
- https://github.com/iVis-at-Bilkent/cytoscape.js-context-menus
- https://github.com/cytoscape/cytoscape.js-popper
- https://github.com/cytoscape/cytoscape.js-navigator
