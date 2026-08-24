package org.atmkg.core.spi;

import org.atmkg.core.model.GraphDTO;
import org.atmkg.core.model.QuerySpec;

/**
 * 只读取图中已有事实并返回 GraphDTO，不负责空间计算、业务推理或 Viewer 展示状态。
 * 普通 named query 应修改 {@code queries/query-templates.yaml}，不要扩展公共查询接口。
 */
public interface QueryService {
    GraphDTO query(QuerySpec spec);
}
