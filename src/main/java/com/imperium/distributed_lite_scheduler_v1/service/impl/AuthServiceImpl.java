package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.UserMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.LoginResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterResponse;
import com.imperium.distributed_lite_scheduler_v1.model.entity.User;
import com.imperium.distributed_lite_scheduler_v1.security.JwtTokenProvider;
import com.imperium.distributed_lite_scheduler_v1.service.AuthService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class AuthServiceImpl implements AuthService {

    /** 与实体 User.status：1-正常 */
    private static final int USER_STATUS_ACTIVE = 1;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthServiceImpl(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Result<LoginResponse> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Result.failure(ResultCode.BAD_REQUEST, "用户名和密码不能为空");
        }
        String name = username.trim();

        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, name);
        User user = userMapper.selectOne(queryWrapper);

        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return Result.failure(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ACTIVE) {
            return Result.failure(ResultCode.FORBIDDEN, "账号已禁用，请联系管理员");
        }

        String token = jwtTokenProvider.createAccessToken(user.getId(), user.getUsername());
        touchLastLogin(user.getId());

        LoginResponse body = new LoginResponse(
                token,
                LoginResponse.BEARER,
                jwtTokenProvider.getExpiresInSeconds());
        return Result.success(body);
    }

    @Override
    public Result<RegisterResponse> register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (existsUsername(username)) {
            return Result.failure(ResultCode.CONFLICT, "用户名已存在，请直接登录");
        }
        if (existsEmail(email)) {
            return Result.failure(ResultCode.CONFLICT, "该邮箱已被注册");
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(username);
        user.setStatus(USER_STATUS_ACTIVE);

        userMapper.insert(user);
        return Result.success(new RegisterResponse("注册成功，请登录"));
    }

    private boolean existsUsername(String username) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0;
    }

    private boolean existsEmail(String email) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, email)) > 0;
    }

    private void touchLastLogin(Long userId) {
        LambdaUpdateWrapper<User> uw = new LambdaUpdateWrapper<>();
        uw.eq(User::getId, userId).set(User::getLastLoginTime, LocalDateTime.now());
        userMapper.update(null, uw);
    }
}
