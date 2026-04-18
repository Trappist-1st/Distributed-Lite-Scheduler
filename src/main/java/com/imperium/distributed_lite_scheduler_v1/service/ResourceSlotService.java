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

    Result<ReserveResourceResponse> reserve(ReserveResourceRequest request);

    Result<Void> release(ReleaseResourceRequest request);

    Result<List<ResourceUsage>> listUsage(ListResourceUsageRequest request);
}
