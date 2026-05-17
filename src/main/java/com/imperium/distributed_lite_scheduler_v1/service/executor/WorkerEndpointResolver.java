package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 解析 Worker 可访问的 HTTP 根地址。
 */
@Component
public class WorkerEndpointResolver {

    public String resolveBaseUrl(ResourceNode node) {
        if (node == null) {
            throw new IllegalArgumentException("资源节点不能为空");
        }
        if (StringUtils.hasText(node.getWorkerEndpoint())) {
            return trimTrailingSlash(node.getWorkerEndpoint().trim());
        }
        if (!StringUtils.hasText(node.getNodeHost()) || node.getNodePort() == null) {
            throw new IllegalStateException("节点缺少 host/port，无法推导 Worker 地址 nodeId=" + node.getId());
        }
        return "http://" + node.getNodeHost().trim() + ":" + node.getNodePort();
    }

    private static String trimTrailingSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
