package com.imperium.distributed_lite_scheduler_v1.service;

import com.imperium.distributed_lite_scheduler_v1.model.dto.LoginResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterResponse;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;

/**
 * 认证相关能力（登录、令牌、会话等）。不继承 {@code IService<User>}，避免与「用户资料/成员关系」的 CRUD 服务职责混淆。
 * <p>
 * 需要访问用户持久化信息时，在实现类中注入 {@link com.imperium.distributed_lite_scheduler_v1.mapper.UserMapper}
 * 或 {@link UserService}（组合优于继承）。
 */
public interface AuthService {

    Result<LoginResponse> login(String username, String password);

    Result<RegisterResponse> register(RegisterRequest request);
}
