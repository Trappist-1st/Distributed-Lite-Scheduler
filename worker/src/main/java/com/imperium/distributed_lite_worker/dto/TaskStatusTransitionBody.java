package com.imperium.distributed_lite_worker.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TaskStatusTransitionBody {

    private String fromStatus;

    private String toStatus;

    private String triggerSource;

    private String reason;

    private Long operatorUserId;

    private Integer exitCode;

    private String errorMessage;
}
