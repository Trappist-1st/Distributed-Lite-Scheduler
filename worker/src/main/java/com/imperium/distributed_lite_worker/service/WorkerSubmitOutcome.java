package com.imperium.distributed_lite_worker.service;

/**
 * Worker 接收调度下发请求的结果（HTTP 层仍统一返回 202，见 {@link WorkerRunService}）。
 */
public enum WorkerSubmitOutcome {

    /** 首次接受，已提交线程池 */
    ACCEPTED("accepted"),

    /** 同一 taskInstanceId 已在执行或排队，拒绝二次执行 */
    ALREADY_ACCEPTED("already_accepted"),

    /** 线程池已满，已通过回调上报失败 */
    REJECTED_POOL_FULL("rejected_pool_full");

    private final String message;

    WorkerSubmitOutcome(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
