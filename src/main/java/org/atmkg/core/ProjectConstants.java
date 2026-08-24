package org.atmkg.core;

/**
 * ATMKG 当前固定技术身份。
 *
 * <p>{@link #PROJECT_ID} 用于在 Neo4j 中区分本项目图数据。
 * {@link #IDENTITY_NAMESPACE} 用于稳定 UID 生成，修改它会改变图身份，不属于普通运行配置。
 * 本体 IRI 即使当前使用相同文本，也属于另一套语义，不通过本类生成。
 */
public final class ProjectConstants {
    public static final String PROJECT_ID = "atm-knowledge-graph";
    public static final String IDENTITY_NAMESPACE = "urn:atm-knowledge-graph:";

    private ProjectConstants() {}
}
