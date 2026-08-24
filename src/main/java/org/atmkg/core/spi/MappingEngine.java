package org.atmkg.core.spi;

import org.atmkg.core.model.MappingResult;
import org.atmkg.core.model.SourceRecord;

/**
 * 根据当前人工映射把一条 SourceRecord 转成实体和关系投影；不负责读取源数据或写 Neo4j。
 * 普通实体、属性、关系变化应修改正式 TTL 和 {@code mapping/字段映射.xlsx}。
 */
public interface MappingEngine {
    MappingResult map(SourceRecord record);
}
