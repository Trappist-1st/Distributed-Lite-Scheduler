package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.config.WorkerRestClientConfig;
import com.imperium.distributed_lite_scheduler_v1.config.properties.TaskExecutorProperties;
import com.imperium.distributed_lite_scheduler_v1.model.dto.worker.WorkerRunAcceptedResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.worker.WorkerRunRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 向远程 Worker 下发运行请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerHttpClient {

    private final RestClient workerRestClient;
    private final TaskExecutorProperties taskExecutorProperties;
    private final WorkerEndpointResolver workerEndpointResolver;

    public boolean submitRun(ResourceNode node, WorkerRunRequest request) {
        String baseUrl = workerEndpointResolver.resolveBaseUrl(node);
        String path = taskExecutorProperties.getWorkerDispatchPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String url = baseUrl + path;

        try {
            var spec = workerRestClient.post().uri(url).body(request);
            if (StringUtils.hasText(taskExecutorProperties.getWorkerApiToken())) {
                spec = spec.header(
                        WorkerRestClientConfig.WORKER_TOKEN_HEADER,
                        taskExecutorProperties.getWorkerApiToken());
            }
            var responseEntity = spec.retrieve().toEntity(WorkerRunAcceptedResponse.class);
            if (responseEntity.getStatusCode() != HttpStatus.ACCEPTED) {
                log.warn(
                        "Worker 未返回 202 taskInstanceId={} url={} status={}",
                        request.getTaskInstanceId(),
                        url,
                        responseEntity.getStatusCode());
                return false;
            }

            log.info(
                    "Worker 已接受任务 taskInstanceId={} workerUrl={} response={}",
                    request.getTaskInstanceId(),
                    url,
                    responseEntity.getBody());
            return true;
        } catch (RestClientResponseException e) {
            log.warn(
                    "Worker 返回错误 taskInstanceId={} url={} status={} body={}",
                    request.getTaskInstanceId(),
                    url,
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString(),
                    e);
            return false;
        } catch (RestClientException e) {
            log.warn(
                    "调用 Worker 失败 taskInstanceId={} url={}",
                    request.getTaskInstanceId(),
                    url,
                    e);
            return false;
        }
    }

    /**
     * 请求 Worker 取消正在执行的任务。
     */
    public boolean cancelRun(ResourceNode node, Long taskInstanceId) {
        String baseUrl = workerEndpointResolver.resolveBaseUrl(node);
        String url = baseUrl + "/api/worker/runs/" + taskInstanceId + "/cancel";
        try {
            var spec = workerRestClient.post().uri(url);
            if (StringUtils.hasText(taskExecutorProperties.getWorkerApiToken())) {
                spec = spec.header(
                        WorkerRestClientConfig.WORKER_TOKEN_HEADER,
                        taskExecutorProperties.getWorkerApiToken());
            }
            var response = spec.retrieve().toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            log.warn("Worker 取消请求失败 taskInstanceId={} url={}", taskInstanceId, url, e);
            return false;
        }
    }
}
