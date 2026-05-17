package com.imperium.distributed_lite_scheduler_v1.service.workflow.condition;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.condition.ConditionContext;
import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.condition.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    @Test
    void blankAlwaysTrue() {
        assertTrue(evaluator.evaluate("", new ConditionContext()));
        assertTrue(evaluator.evaluate("   ", new ConditionContext()));
    }

    @Test
    void simpleSpelOnTasks() {
        ConditionContext ctx = new ConditionContext();
        TaskResult r = new TaskResult();
        r.setStatus("SUCCESS");
        r.setExitCode(0);
        r.setOutput(Map.of("score", 95));
        ctx.getTasks().put("data_validation", r);

        assertTrue(evaluator.evaluate("#tasks['data_validation'].output['score'] >= 90", ctx));
        assertFalse(evaluator.evaluate("#tasks['data_validation'].output['score'] < 90", ctx));
    }

    @Test
    void normalizeDollarBracesPlaceholder() {
        ConditionContext ctx = new ConditionContext();
        TaskResult r = new TaskResult();
        r.setOutput(Map.of("x", true));
        ctx.getTasks().put("a", r);

        assertTrue(evaluator.evaluate("${#tasks['a'].output['x'] == true}", ctx));
    }

    @Test
    void rejectsTypeReference() {
        assertThrows(IllegalArgumentException.class, () -> evaluator.validateSyntax("T(Boolean).FALSE"));
    }
}
