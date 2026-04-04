package com.imperium.distributed_lite_scheduler_v1.controller;

import com.imperium.distributed_lite_scheduler_v1.model.dto.LoginRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.LoginResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterResponse;
import com.imperium.distributed_lite_scheduler_v1.security.JwtUserPrincipal;
import com.imperium.distributed_lite_scheduler_v1.service.AuthService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
