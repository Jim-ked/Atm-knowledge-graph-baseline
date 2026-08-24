package org.atmkg.core.spi;

import java.nio.file.Path;
import org.atmkg.core.model.OntologySchema;
import org.atmkg.core.model.mapping.MappingCatalog;

/**
 * 读取并校验人工 mapping 工作簿；不执行逐条 SourceRecord 映射，也不修改图。
 * 普通映射内容直接编辑工作簿，只有工作簿通用列结构变化才修改接口或实现。
 */
public interface MappingRegistry {
    /** 根据当前本体读取、规范化并校验 mapping。 */
    MappingCatalog load(Path mappingFile, OntologySchema schema);

    void validate(MappingCatalog catalog, OntologySchema schema);

    /**
     * 把本体术语同步到工作簿但不覆盖已填写内容。新术语只追加为待映射；删除或改名造成的失效项
     * 继续保留并由校验报告，等待人工修复。
     */
    void refreshFromOntology(Path mappingFile, OntologySchema schema);
}
