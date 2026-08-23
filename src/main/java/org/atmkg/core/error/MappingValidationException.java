package org.atmkg.core.error;

import java.util.List;

public final class MappingValidationException extends RuntimeException {
    private final List<String> issues;

    public MappingValidationException(List<String> issues) {
        super("字段映射校验失败：" + String.join("；", issues));
        this.issues = List.copyOf(issues);
    }

    public List<String> getIssues() { return issues; }
}
