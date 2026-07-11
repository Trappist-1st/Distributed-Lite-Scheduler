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
 * 向调度中心回调任务状态，并在任务执行期间定期上报心跳。
 *
 * <p>状态回调带指数退避重试（最多 3 次），防止调度器短暂重启或网络抖动导致结果丢失。
 * 心跳调用不重试——单次失败只是一次漏报，Watchdog 容忍偶发漏跳。
 */
@Slf4j
@Component
public class SchedulerCallbackClient {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private static final int MAX_RETRY = 3;
    private static final long INITIAL_DELAY_MS = 2_000;

    private final RestClient restClient = RestClient.create();

    // -------------------------------------------------------------------------
    // 任务完成回调（带重试）
    // -------------------------------------------------------------------------

    public void report(WorkerRunCallback callback, ExecutionResult result) {
        if (callback == null || !StringUtils.hasText(callback.getStatusTransitionUrl())) {
            log.error("缺少回调地址，无法上报任务状态");
            return;
        }
        TaskStatusTransitionBody body = buildBody(result);
        postWithRetry(callback.getStatusTransitionUrl(), callback.getInternalToken(), body, "状态回调");
    }

    // -------------------------------------------------------------------------
    // 任务级心跳（单次，失败仅打 warn）
    // -------------------------------------------------------------------------

    public void heartbeat(WorkerRunCallback callback, Long taskInstanceId) {
        if (callback == null || !StringUtils.hasText(callback.getHeartbeatUrl())) {
            return;
        }
        try {
            buildSpec(callback.getHeartbeatUrl(), callback.getInternalToken(), null)
                    .retrieve().toBodilessEntity();
            log.trace("任务心跳已上报 taskInstanceId={}", taskInstanceId);
        } catch (RestClientException e) {
            log.warn("任务心跳上报失败 taskInstanceId={}", taskInstanceId, e);
        }
    }

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

    private void postWithRetry(String url, String token, Object body, String label) {
        long delayMs = INITIAL_DELAY_MS;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                buildSpec(url, token, body).retrieve().toBodilessEntity();
                log.info("{}成功（第 {} 次）", label, attempt);
                return;
            } catch (RestClientException e) {
                if (attempt == MAX_RETRY) {
                    log.error("{}最终失败，已重试 {} 次 url={}", label, MAX_RETRY, url, e);
                } else {
                    log.warn("{}失败，{}ms 后重试（{}/{}）url={}", label, delayMs, attempt, MAX_RETRY, url);
                    sleepQuietly(delayMs);
                    delayMs *= 2;
                }
            }
        }
    }

    private RestClient.RequestBodySpec buildSpec(String url, String token, Object body) {
        var spec = restClient.post().uri(url);
        if (StringUtils.hasText(token)) {
            spec = spec.header(INTERNAL_TOKEN_HEADER, token);
        }
        if (body != null) {
            return spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TaskStatusTransitionBody buildBody(ExecutionResult result) {
        if (result.isTimedOut()) {
            return TaskStatusTransitionBody.builder()
                    .fromStatus("RUNNING").toStatus("TIMEOUT")
                    .triggerSource("WORKER").reason("执行超时")
                    .exitCode(-1).errorMessage(result.getErrorMessage())
                    .build();
        }
        if (result.isSuccess()) {
            return TaskStatusTransitionBody.builder()
                    .fromStatus("RUNNING").toStatus("SUCCESS")
                    .triggerSource("WORKER").reason("执行成功")
                    .exitCode(result.getExitCode())
                    .build();
        }
        return TaskStatusTransitionBody.builder()
                .fromStatus("RUNNING").toStatus("FAILED")
                .triggerSource("WORKER").reason("执行失败")
                .exitCode(result.getExitCode()).errorMessage(result.getErrorMessage())
                .build();
    }
}
