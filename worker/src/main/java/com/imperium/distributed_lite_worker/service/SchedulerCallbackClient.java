package com.imperium.distributed_lite_worker.service;

import com.imperium.distributed_lite_worker.dto.TaskStatusTransitionBody;
import com.imperium.distributed_lite_worker.dto.WorkerRunCallback;
import com.imperium.distributed_lite_worker.executor.ExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 任务完成后回调调度中心内部状态 API。
 */
@Slf4j
@Component
public class SchedulerCallbackClient {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient = RestClient.create();

    public void report(WorkerRunCallback callback, ExecutionResult result) {
        if (callback == null || !StringUtils.hasText(callback.getStatusTransitionUrl())) {
            log.error("缺少回调地址，无法上报任务状态");
            return;
        }

        TaskStatusTransitionBody body = buildBody(result);
        try {
            var spec = restClient.post().uri(callback.getStatusTransitionUrl()).body(body);
            if (StringUtils.hasText(callback.getInternalToken())) {
                spec = spec.header(INTERNAL_TOKEN_HEADER, callback.getInternalToken());
            }
            spec.contentType(MediaType.APPLICATION_JSON).retrieve().toBodilessEntity();
            log.info(
                    "已回调调度中心 taskInstanceId 目标状态={}",
                    body.getToStatus());
        } catch (RestClientException e) {
            log.error("回调调度中心失败 url={}", callback.getStatusTransitionUrl(), e);
        }
    }

    private TaskStatusTransitionBody buildBody(ExecutionResult result) {
        if (result.isTimedOut()) {
            return TaskStatusTransitionBody.builder()
                    .fromStatus("RUNNING")
                    .toStatus("TIMEOUT")
                    .triggerSource("WORKER")
                    .reason("执行超时")
                    .exitCode(-1)
                    .errorMessage(result.getErrorMessage())
                    .build();
        }
        if (result.isSuccess()) {
            return TaskStatusTransitionBody.builder()
                    .fromStatus("RUNNING")
                    .toStatus("SUCCESS")
                    .triggerSource("WORKER")
                    .reason("执行成功")
                    .exitCode(result.getExitCode())
                    .build();
        }
        return TaskStatusTransitionBody.builder()
                .fromStatus("RUNNING")
                .toStatus("FAILED")
                .triggerSource("WORKER")
                .reason("执行失败")
                .exitCode(result.getExitCode())
                .errorMessage(result.getErrorMessage())
                .build();
    }
}
