package com.imperium.distributed_lite_scheduler_v1.service.workflow.condition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 对工作流 DAG 中依赖边上的条件表达式做批量语法校验（P4-4）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkflowConditionSyntaxValidator {

    private final ConditionEvaluator conditionEvaluator;

    /**
     * 对多条条件串做语法校验；null 或空白跳过。
     */
    public void validateAll(List<String> conditionExpressions) {
        if (conditionExpressions == null || conditionExpressions.isEmpty()) {
            return;
        }
        for (String expr : conditionExpressions) {
            if (!StringUtils.hasText(expr)) {
                continue;
            }
            conditionEvaluator.validateSyntax(expr);
        }
    }
}
