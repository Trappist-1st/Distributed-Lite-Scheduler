package com.imperium.distributed_lite_scheduler_v1.model.dto;

import java.util.List;

/**
 * 通用分页响应载荷。
 *
 * @param records 当前页数据
 * @param total 总记录数
 * @param <T> 数据类型
 */
public record PageResponse<T>(
        List<T> records,
        long total
) {
}
