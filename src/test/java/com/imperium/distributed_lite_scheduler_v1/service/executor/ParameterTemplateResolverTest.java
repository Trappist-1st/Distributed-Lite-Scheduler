package com.imperium.distributed_lite_scheduler_v1.service.executor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterTemplateResolverTest {

    @Test
    void resolvesPlaceholders() {
        String template = "echo ${name} && echo ${count}";
        String resolved = ParameterTemplateResolver.resolve(
                template, Map.of("name", "world", "count", 3));
        assertEquals("echo world && echo 3", resolved);
    }

    @Test
    void leavesUnknownPlaceholders() {
        String template = "python run.py --input ${input_path}";
        String resolved = ParameterTemplateResolver.resolve(template, Map.of());
        assertEquals(template, resolved);
    }
}
