package com.imperium.distributed_lite_worker.service;

import com.imperium.distributed_lite_worker.config.WorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向调度中心注册节点并发送心跳。
 */
@Slf4j
@Component
public class SchedulerNodeClient {

    private final WorkerProperties properties;
    private final RestClient restClient = RestClient.create();

    public SchedulerNodeClient(WorkerProperties properties) {
        this.properties = properties;
    }

    public void register() {
        String baseUrl = trimBaseUrl(properties.getScheduler().getBaseUrl());
        Map<String, Object> body = buildNodePayload();
        try {
            restClient.post()
                    .uri(baseUrl + "/api/resource/register")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("已向调度中心注册 Worker 节点 host={} port={}", body.get("nodeHost"), body.get("nodePort"));
        } catch (Exception e) {
            log.error("Worker 节点注册失败", e);
        }
    }

    public void heartbeat() {
        sendHeartbeat("ONLINE");
    }

    /** 停机前通知调度中心将本节点标记为 OFFLINE，让调度器停止向本节点分发新任务。 */
    public void deregister() {
        sendHeartbeat("OFFLINE");
        log.info("已通知调度中心节点下线 host={} port={}",
                properties.getNode().getHost(), properties.getNode().getPort());
    }

    private void sendHeartbeat(String status) {
        String baseUrl = trimBaseUrl(properties.getScheduler().getBaseUrl());
        WorkerProperties.Node node = properties.getNode();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeHost", node.getHost());
        body.put("nodePort", node.getPort());
        body.put("availableCpu", node.getTotalCpu());
        body.put("availableMemoryMb", node.getTotalMemoryMb());
        body.put("availableGpu", node.getTotalGpu());
        body.put("status", status);
        try {
            restClient.post()
                    .uri(baseUrl + "/api/resource/heartbeat")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Worker 心跳成功 status={}", status);
        } catch (Exception e) {
            log.warn("Worker 心跳失败 status={}", status, e);
        }
    }

    private Map<String, Object> buildNodePayload() {
        WorkerProperties.Node node = properties.getNode();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodeName", node.getName());
        body.put("nodeHost", node.getHost());
        body.put("nodePort", node.getPort());
        body.put("nodeType", node.getType());
        body.put("totalCpu", node.getTotalCpu());
        body.put("totalMemoryMb", node.getTotalMemoryMb());
        body.put("totalGpu", node.getTotalGpu());
        if (StringUtils.hasText(node.getWorkerEndpoint())) {
            body.put("workerEndpoint", node.getWorkerEndpoint().trim());
        } else {
            body.put("workerEndpoint", "http://" + node.getHost() + ":" + node.getPort());
        }
        return body;
    }

    private static String trimBaseUrl(String baseUrl) {
        String url = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "http://localhost:8080";
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
