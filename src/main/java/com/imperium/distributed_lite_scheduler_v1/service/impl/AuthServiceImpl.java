package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TenantMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TenantMemberMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.UserMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.LoginResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.LoginTenantItem;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterResponse;
import com.imperium.distributed_lite_scheduler_v1.model.dto.SwitchTenantRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Tenant;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TenantMember;
import com.imperium.distributed_lite_scheduler_v1.model.entity.User;
import com.imperium.distributed_lite_scheduler_v1.security.JwtTokenProvider;
import com.imperium.distributed_lite_scheduler_v1.service.AuthService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class AuthServiceImpl implements AuthService {

    /** 与实体 User.status：1-正常 */
    private static final int USER_STATUS_ACTIVE = 1;
    /** 与实体 Tenant.status：1-正常 */
    private static final int TENANT_STATUS_ACTIVE = 1;

    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthServiceImpl(
            UserMapper userMapper,
            TenantMapper tenantMapper,
            TenantMemberMapper tenantMemberMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.tenantMemberMapper = tenantMemberMapper;
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

        List<LoginTenantItem> tenants = loadLoginTenants(user.getId());
        Long defaultTenantId = resolveDefaultTenantId(tenants);

        LoginResponse body = new LoginResponse(
                token,
                LoginResponse.BEARER,
                jwtTokenProvider.getExpiresInSeconds(),
                null,
                tenants,
                defaultTenantId);
        return Result.success(body);
    }

    /**
     * 加载登录租户
     * @param userId
     * @return
     */
    @Override
    public List<LoginTenantItem> loadLoginTenants(Long userId) {
        List<TenantMember> members = tenantMemberMapper.selectList(
                new LambdaQueryWrapper<TenantMember>().eq(TenantMember::getUserId, userId));
        if (members.isEmpty()) {
            return List.of();
        }
        List<LoginTenantItem> items = new ArrayList<>();
        for (TenantMember m : members) {
            Tenant t = tenantMapper.selectById(m.getTenantId());
            if (!isTenantUsable(t)) {
                continue;
            }
            items.add(new LoginTenantItem(
                    t.getId(),
                    t.getTenantCode(),
                    t.getTenantName(),
                    m.getRole() != null ? m.getRole() : ""));
        }
        items.sort(Comparator.comparing(LoginTenantItem::tenantId));
        return List.copyOf(items);
    }

    private static Long resolveDefaultTenantId(List<LoginTenantItem> tenants) {
        if (tenants.isEmpty()) {
            return null;
        }
        return tenants.stream()
                .filter(i -> "OWNER".equalsIgnoreCase(i.role()))
                .findFirst()
                .map(LoginTenantItem::tenantId)
                .orElseGet(() -> tenants.get(0).tenantId());
    }

    @Override
    public Result<LoginResponse> switchTenant(Long userId, SwitchTenantRequest request) {
        if (userId == null) {
            return Result.failure(ResultCode.UNAUTHORIZED, "未登录");
        }
        boolean hasId = request.tenantId() != null;
        boolean hasCode = StringUtils.hasText(request.tenantCode());
        if (hasId && hasCode) {
            return Result.failure(ResultCode.BAD_REQUEST, "请只填写 tenantId 或 tenantCode 之一");
        }
        if (!hasId && !hasCode) {
            return Result.failure(ResultCode.BAD_REQUEST, "请提供 tenantId 或 tenantCode");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.failure(ResultCode.UNAUTHORIZED, "用户不存在");
        }

        //查询登录用户将会切换进入哪个租户里权限
        Tenant tenant;
        if (hasId) {
            tenant = tenantMapper.selectById(request.tenantId());
        } else {
            tenant = tenantMapper.selectOne(
                    new LambdaQueryWrapper<Tenant>()
                            .eq(Tenant::getTenantCode, request.tenantCode().trim()));
        }
        if (!isTenantUsable(tenant)) {
            return Result.failure(ResultCode.BAD_REQUEST, "租户不存在、已禁用或已过期");
        }

        TenantMember membership = tenantMemberMapper.selectOne(
                new LambdaQueryWrapper<TenantMember>()
                        .eq(TenantMember::getUserId, userId)
                        .eq(TenantMember::getTenantId, tenant.getId()));
        if (membership == null) {
            return Result.failure(ResultCode.FORBIDDEN, "无权访问该租户");
        }

        String accessToken = jwtTokenProvider.createTenantAccessToken(
                userId,
                user.getUsername(),
                tenant.getId(),
                tenant.getTenantCode(),
                membership.getRole());

        List<LoginTenantItem> tenants = loadLoginTenants(userId);
        Long defaultTenantId = tenant.getId();

        LoginResponse body = new LoginResponse(
                accessToken,
                LoginResponse.BEARER,
                jwtTokenProvider.getExpiresInSeconds(),
                null,
                tenants,
                defaultTenantId);
        return Result.success(body);
    }

    private boolean isTenantUsable(Tenant t) {
        if (t == null || t.getStatus() == null || t.getStatus() != TENANT_STATUS_ACTIVE) {
            return false;
        }
        if (t.getExpireTime() != null && !t.getExpireTime().isAfter(LocalDateTime.now())) {
            return false;
        }
        return true;
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
