package com.imperium.distributed_lite_worker.dto;

import lombok.Data;

@Data
public class WorkerRunCallback {

    private String statusTransitionUrl;

    /** 任务级心跳 URL，Worker 执行期间定期 POST 刷新 last_heartbeat_at。 */
    private String heartbeatUrl;

    private String internalToken;
}
