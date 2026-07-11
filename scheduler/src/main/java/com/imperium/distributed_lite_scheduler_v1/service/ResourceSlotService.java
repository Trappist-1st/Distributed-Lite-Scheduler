package com.imperium.distributed_lite_scheduler_v1.service;

import com.imperium.distributed_lite_scheduler_v1.model.dto.ListResourceUsageRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReleaseResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ReserveResourceResponse;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceUsage;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

import java.util.List;

/**
 * 资源槽位与使用流水服务（P2-2）。
 */
public interface ResourceSlotService {

    // 预留任务所需资源并返回预留流水信息。
    Result<ReserveResourceResponse> reserve(ReserveResourceRequest request);

    /**
     * 调度器内部预留（无 JWT 租户上下文，通过 taskInstance 反查租户并校验一致性）。
     */
    Result<ReserveResourceResponse> reserveForScheduler(ReserveResourceRequest request);

    // 按预留流水释放资源槽位并回收配额。
    Result<Void> release(ReleaseResourceRequest request);

    // 分页或按条件查询资源使用流水记录。
    Result<List<ResourceUsage>> listUsage(ListResourceUsageRequest request);

    /**
     * 系统内部：任务终态后释放资源占用（无租户 JWT，供执行器/守护线程调用）。
     */
    void releaseForTaskInstanceSystem(Long taskInstanceId, String reason);
}
