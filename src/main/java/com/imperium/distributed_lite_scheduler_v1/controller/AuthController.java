package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.*;
import com.imperium.distributed_lite_scheduler_v1.security.JwtUserPrincipal;
import com.imperium.distributed_lite_scheduler_v1.service.AuthService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/register")
    public Result<RegisterResponse> register(@RequestBody @Valid RegisterRequest request) {
        return authService.register(request);
    }

    /**
     * 需携带 {@code Authorization: Bearer &lt;jwt&gt;}。
     * {@link AuthenticationPrincipal} 为 Spring Security 内置，注入当前登录主体（即 {@link JwtUserPrincipal}）。
     */
    @GetMapping("/me")
    public Result<Long> me(@AuthenticationPrincipal JwtUserPrincipal principal) {
        return Result.success(principal.userId());
    }

    @GetMapping("/tenants")
    public Result<List<LoginTenantItem>> getTenants(@AuthenticationPrincipal JwtUserPrincipal principal) {
        return Result.success(authService.loadLoginTenants(principal.userId()));
    }

    /**
     * 切换当前租户上下文。请求体填 {@code tenantId} 或 {@code tenantCode} 之一。
     * 成功返回新的 tenant-scoped accessToken（与登录响应结构一致）。
     */
    @PostMapping("/switch-tenant")
    public Result<LoginResponse> switchTenant(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @RequestBody SwitchTenantRequest request) {
        return authService.switchTenant(principal.userId(), request);
    }
}
