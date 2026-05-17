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

    private String internalToken;
}
