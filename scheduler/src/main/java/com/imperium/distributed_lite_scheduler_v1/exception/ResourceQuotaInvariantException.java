package com.imperium.distributed_lite_scheduler_v1.exception;

/**
 * 配额快照与业务操作不一致（如释放时回退行数为 0），需人工对账。
 */
public class ResourceQuotaInvariantException extends RuntimeException {

    public ResourceQuotaInvariantException(String message) {
        super(message);
    }
}
