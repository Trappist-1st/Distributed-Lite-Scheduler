package com.imperium.distributed_lite_scheduler_v1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 租户配额 Mapper；消费/回退使用条件 UPDATE 保证并发下不超分（设计稿 §6.2）。
 */
@Mapper
public interface ResourceQuotaMapper extends BaseMapper<ResourceQuota> {

    @Update("""
            UPDATE resource_quota
            SET used_cpu = used_cpu + #{cpu},
                used_memory_mb = used_memory_mb + #{mem},
                used_gpu = used_gpu + #{gpu},
                running_tasks = running_tasks + #{runningDelta},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = #{tenantId}
              AND used_cpu + #{cpu} <= max_cpu
              AND used_memory_mb + #{mem} <= max_memory_mb
              AND used_gpu + #{gpu} <= max_gpu
              AND running_tasks + #{runningDelta} <= max_running_tasks
            """)
    int consumeIfWithinQuota(
            @Param("tenantId") long tenantId,
            @Param("cpu") int cpu,
            @Param("mem") int mem,
            @Param("gpu") int gpu,
            @Param("runningDelta") int runningDelta);

    @Update("""
            UPDATE resource_quota
            SET used_cpu = used_cpu - #{cpu},
                used_memory_mb = used_memory_mb - #{mem},
                used_gpu = used_gpu - #{gpu},
                running_tasks = running_tasks - #{runningDelta},
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = #{tenantId}
              AND used_cpu >= #{cpu}
              AND used_memory_mb >= #{mem}
              AND used_gpu >= #{gpu}
              AND running_tasks >= #{runningDelta}
            """)
    int releaseUsed(
            @Param("tenantId") long tenantId,
            @Param("cpu") int cpu,
            @Param("mem") int mem,
            @Param("gpu") int gpu,
            @Param("runningDelta") int runningDelta);
}
