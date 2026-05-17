package com.imperium.distributed_lite_worker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WorkerRunAcceptedResponse {

    private Long taskInstanceId;

    private String message;
}
