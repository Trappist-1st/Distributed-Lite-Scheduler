package com.imperium.distributed_lite_scheduler_v1.exception;

/**
 * 预留阶段租户配额原子扣减失败时抛出，由事务回滚槽位与 usage 写入（设计稿 §5、§7）。
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
