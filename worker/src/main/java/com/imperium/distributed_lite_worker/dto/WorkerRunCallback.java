package com.imperium.distributed_lite_worker.dto;

import lombok.Data;

@Data
public class WorkerRunCallback {

    private String statusTransitionUrl;

    private String internalToken;
}
