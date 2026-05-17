package com.imperium.distributed_lite_scheduler_v1.model.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体（JSON：username、password）。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
