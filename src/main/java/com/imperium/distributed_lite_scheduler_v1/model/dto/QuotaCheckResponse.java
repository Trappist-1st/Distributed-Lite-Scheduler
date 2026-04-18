package com.imperium.distributed_lite_scheduler_v1.model.dto;

/**
 * 配额预检查结果（设计稿 §4.3、§7）。
 */
public record QuotaCheckResponse(
        boolean allowed,
        String rejectReason
) {
    public static QuotaCheckResponse ok() {
        return new QuotaCheckResponse(true, null);
    }

    public static QuotaCheckResponse reject(String reason) {
        return new QuotaCheckResponse(false, reason);
    }
}
