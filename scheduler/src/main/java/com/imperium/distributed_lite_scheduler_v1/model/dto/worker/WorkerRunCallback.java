package com.imperium.distributed_lite_scheduler_v1.model.dto.worker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Worker 完成后回调调度中心内部状态 API 所需信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkerRunCallback {

    private String statusTransitionUrl;

    /** 任务级心跳 URL（Worker 执行期间定期 POST，更新 last_heartbeat_at）。 */
    private String heartbeatUrl;

    private String internalToken;
}
