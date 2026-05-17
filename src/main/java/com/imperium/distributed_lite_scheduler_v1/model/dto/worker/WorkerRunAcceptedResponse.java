package com.imperium.distributed_lite_scheduler_v1.model.dto.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Worker 接受异步执行后的响应体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerRunAcceptedResponse {

    private Long taskInstanceId;

    private String message;
}
