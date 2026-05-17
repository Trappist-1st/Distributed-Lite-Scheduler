package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.config.OpenApiConfig;
import com.imperium.distributed_lite_scheduler_v1.model.dto.*;
import com.imperium.distributed_lite_scheduler_v1.security.JwtUserPrincipal;
import com.imperium.distributed_lite_scheduler_v1.service.AuthService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "认证", description = "用户登录、注册、租户上下文切换")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "用户登录", description = "使用用户名和密码登录，返回 JWT 及可访问租户列表")
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @Operation(summary = "用户注册", description = "注册新用户账号")
    @PostMapping("/register")
    public Result<RegisterResponse> register(@RequestBody @Valid RegisterRequest request) {
        return authService.register(request);
    }

    @Operation(
            summary = "当前用户 ID",
            description = "需携带 Authorization: Bearer {jwt}",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @GetMapping("/me")
    public Result<Long> me(@AuthenticationPrincipal JwtUserPrincipal principal) {
        return Result.success(principal.userId());
    }

    @Operation(
            summary = "当前用户可访问租户列表",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @GetMapping("/tenants")
    public Result<List<LoginTenantItem>> getTenants(@AuthenticationPrincipal JwtUserPrincipal principal) {
        return Result.success(authService.loadLoginTenants(principal.userId()));
    }

    @Operation(
            summary = "切换租户",
            description = "请求体填写 tenantId 或 tenantCode 之一，成功返回新的租户作用域 accessToken",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
    @PostMapping("/switch-tenant")
    public Result<LoginResponse> switchTenant(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @RequestBody SwitchTenantRequest request) {
        return authService.switchTenant(principal.userId(), request);
    }
}
