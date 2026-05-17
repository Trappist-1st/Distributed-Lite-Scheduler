package com.imperium.distributed_lite_scheduler_v1.service.executor;

import lombok.Builder;
import lombok.Value;

/**
 * 单次任务执行结果（进程内执行器产出）。
 */
@Value
@Builder
public class ExecutionResult {

    boolean success;

    int exitCode;

    String stdout;

    String stderr;

    String errorMessage;

    boolean timedOut;

    public static ExecutionResult success(int exitCode, String stdout, String stderr) {
        return ExecutionResult.builder()
                .success(exitCode == 0)
                .exitCode(exitCode)
                .stdout(stdout)
                .stderr(stderr)
                .build();
    }

    public static ExecutionResult failure(int exitCode, String stdout, String stderr, String errorMessage) {
        return ExecutionResult.builder()
                .success(false)
                .exitCode(exitCode)
                .stdout(stdout)
                .stderr(stderr)
                .errorMessage(errorMessage)
                .build();
    }

    public static ExecutionResult timedOut(String errorMessage) {
        return ExecutionResult.builder()
                .success(false)
                .exitCode(-1)
                .timedOut(true)
                .errorMessage(errorMessage)
                .build();
    }

    public static ExecutionResult configurationError(String errorMessage) {
        return ExecutionResult.builder()
                .success(false)
                .exitCode(-1)
                .errorMessage(errorMessage)
                .build();
    }
}
