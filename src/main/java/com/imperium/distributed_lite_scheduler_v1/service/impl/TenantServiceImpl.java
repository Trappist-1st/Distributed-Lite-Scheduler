package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.imperium.distributed_lite_scheduler_v1.mapper.TenantMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.TenantMemberMapper;
import com.imperium.distributed_lite_scheduler_v1.mapper.UserMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.CreateTenantRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.Tenant;
import com.imperium.distributed_lite_scheduler_v1.model.entity.TenantMember;
import com.imperium.distributed_lite_scheduler_v1.model.entity.User;
import com.imperium.distributed_lite_scheduler_v1.security.JwtUserPrincipal;
import com.imperium.distributed_lite_scheduler_v1.security.TenantAccessGuard;
import com.imperium.distributed_lite_scheduler_v1.service.TenantService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class TenantServiceImpl extends ServiceImpl<TenantMapper, Tenant> implements TenantService {

    private static final int TENANT_STATUS_ACTIVE = 1;
    private static final int USER_STATUS_ACTIVE = 1;
    private static final int DEFAULT_MAX_PROJECTS = 10;
    private static final int DEFAULT_MAX_TASKS = 1000;
    private static final Set<String> ALL_ROLES = Set.of("OWNER", "ADMIN", "MEMBER", "GUEST");

    private final TenantMapper tenantMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final UserMapper userMapper;
    private final TenantAccessGuard tenantAccessGuard;

    public TenantServiceImpl(
            TenantMapper tenantMapper,
            TenantMemberMapper tenantMemberMapper,
            UserMapper userMapper,
            TenantAccessGuard tenantAccessGuard) {
        this.tenantMapper = tenantMapper;
        this.tenantMemberMapper = tenantMemberMapper;
        this.userMapper = userMapper;
        this.tenantAccessGuard = tenantAccessGuard;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result<Tenant> createTenant(CreateTenantRequest request) {
        //首先理清实现的业务具体是什么样的，这是要创建一个新的租户，而这只能是由已登录用户完成的
        //1.首先检验用户是否登录，如果没有登录则直接返回错误
        Result<JwtUserPrincipal> login = tenantAccessGuard.requireLogin();
        if (!login.isSuccess()) {
            return Result.failure(login.getCode(), login.getMessage());
        }
        com.imperium.distributed_lite_scheduler_v1.security.JwtUserPrincipal principal = login.getData();

        //2.根据request创建一个新的Tenant对象（应该会需要拷贝一些属性），然后将它放到数据库里
        String tenantCode = request.tenantCode().trim().toLowerCase(Locale.ROOT);
        Long duplicate = tenantMapper.selectCount(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getTenantCode, tenantCode));
        if (duplicate != null && duplicate > 0) {
            return Result.failure(ResultCode.CONFLICT, "租户编码已存在");
        }

        Tenant tenant = new Tenant();
        tenant.setTenantName(request.tenantName().trim());
        tenant.setTenantCode(tenantCode);
        tenant.setOwnerUserId(principal.userId());
        tenant.setDescription(StringUtils.hasText(request.description()) ? request.description().trim() : null);
        tenant.setStatus(TENANT_STATUS_ACTIVE);
        tenant.setMaxProjects(request.maxProjects() != null ? request.maxProjects() : DEFAULT_MAX_PROJECTS);
        tenant.setMaxTasks(request.maxTasks() != null ? request.maxTasks() : DEFAULT_MAX_TASKS);
        tenant.setExpireTime(request.expireTime());
        int insertedTenant = tenantMapper.insert(tenant);
        if (insertedTenant != 1 || tenant.getId() == null) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "创建租户失败");
        }

        //3.似乎也需要更新tenant_member表，该用户需要作为这个租户的OWNER放到tenant_member表里（一条新记录）
        TenantMember ownerMembership = new TenantMember();
        ownerMembership.setTenantId(tenant.getId());
        ownerMembership.setUserId(principal.userId());
        ownerMembership.setRole("OWNER");
        ownerMembership.setJoinTime(LocalDateTime.now());
        int insertedMember = tenantMemberMapper.insert(ownerMembership);
        if (insertedMember != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "创建租户成员关系失败");
        }

        //4.最后返回创建好的Tenant对象，包装在Result里
        return Result.success(tenant);
    }

    @Override
    public Result<List<TenantMember>> getTenantMembers(Long tenantId) {
        //这个看起来还可以，首先检查用户登陆状态（这绝对能单独抽出一个方法了），
        Result<JwtUserPrincipal> login = tenantAccessGuard.requireLogin();
        if (!login.isSuccess()) {
            return Result.failure(login.getCode(), login.getMessage());
        }
        JwtUserPrincipal principal = login.getData();

        Tenant tenant = tenantMapper.selectById(tenantId);
        if (!isTenantUsable(tenant)) {
            return Result.failure(ResultCode.NOT_FOUND, "租户不存在、已禁用或已过期");
        }

        // 然后检查用户在这个租户里的权限是否足够（至少要是成员），租户内成员有权查看租户中的其他成员列表，如果权限不够则直接返回错误
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(Set.of(), "");
        if (!access.isSuccess() || !tenantId.equals(access.getData().principal().tenantId())) {
            return Result.failure(ResultCode.FORBIDDEN, "无权查看该租户成员");
        }

        // 最后查询tenant_member表，返回这个租户的所有成员列表
        List<TenantMember> members = tenantMemberMapper.selectList(
                new LambdaQueryWrapper<TenantMember>().eq(TenantMember::getTenantId, tenantId));
        return Result.success(members);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result<TenantMember> addTenantMember(Long tenantId, Long userId, String role) {
        //这个看起来也还可以，首先检查用户登陆状态（这绝对能单独抽出一个方法了），
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(Set.of(), "");
        if (!access.isSuccess() || !tenantId.equals(access.getData().principal().tenantId())) {
            return Result.failure(ResultCode.FORBIDDEN, "无权操作该租户成员");
        }
        JwtUserPrincipal principal = access.getData().principal();

        Tenant tenant = tenantMapper.selectById(tenantId);
        if (!isTenantUsable(tenant)) {
            return Result.failure(ResultCode.NOT_FOUND, "租户不存在、已禁用或已过期");
        }

        //然后检查用户在这个租户里的权限是否足够（至少要是管理员），其中OWNER有权添加任意成员，ADMIN只能添加MEMBER/GUEST，其他不允许，如果权限不够则直接返回错误
        String operatorRole = normalizeRole(access.getData().membership().getRole());
        String targetRole = normalizeRole(role);
        if (targetRole == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "角色无效，仅支持 OWNER/ADMIN/MEMBER/GUEST");
        }
        if (!canAddRole(operatorRole, targetRole)) {
            return Result.failure(ResultCode.FORBIDDEN, "当前角色无添加该成员角色权限");
        }

        User targetUser = userMapper.selectById(userId);
        if (targetUser == null || targetUser.getStatus() == null || targetUser.getStatus() != USER_STATUS_ACTIVE) {
            return Result.failure(ResultCode.BAD_REQUEST, "目标用户不存在或不可用");
        }
        TenantMember existing = findMembership(tenantId, userId);
        if (existing != null) {
            return Result.failure(ResultCode.CONFLICT, "该用户已是租户成员");
        }

        //最后在tenant_member表里添加一条记录，表示这个用户加入了这个租户，并且具有指定的角色
        TenantMember member = new TenantMember();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setRole(targetRole);
        member.setJoinTime(LocalDateTime.now());
        int inserted = tenantMemberMapper.insert(member);
        if (inserted != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "添加成员失败");
        }

        //最后返回添加的TenantMember对象，包装在Result里
        return Result.success(member);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result<TenantMember> updateTenantMemberRole(Long tenantId, Long userId, String role) {
        //这个看起来也还可以，首先检查用户登陆状态（这绝对能单独抽出一个方法了），
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(Set.of(), "");
        if (!access.isSuccess() || !tenantId.equals(access.getData().principal().tenantId())) {
            return Result.failure(ResultCode.FORBIDDEN, "只有 OWNER 可以修改成员角色");
        }
        JwtUserPrincipal principal = access.getData().principal();

        Tenant tenant = tenantMapper.selectById(tenantId);
        if (!isTenantUsable(tenant)) {
            return Result.failure(ResultCode.NOT_FOUND, "租户不存在、已禁用或已过期");
        }

        //然后检查用户在这个租户里的权限是否足够，只有OWNER有权修改成员角色，如果权限不够则直接返回错误
        if (!"OWNER".equals(normalizeRole(access.getData().membership().getRole()))) {
            return Result.failure(ResultCode.FORBIDDEN, "只有 OWNER 可以修改成员角色");
        }
        String newRole = normalizeRole(role);
        if (newRole == null) {
            return Result.failure(ResultCode.BAD_REQUEST, "角色无效，仅支持 OWNER/ADMIN/MEMBER/GUEST");
        }
        TenantMember targetMembership = findMembership(tenantId, userId);
        if (targetMembership == null) {
            return Result.failure(ResultCode.NOT_FOUND, "成员不存在");
        }
        String oldRole = normalizeRole(targetMembership.getRole());
        if ("OWNER".equals(oldRole) && !"OWNER".equals(newRole)) {
            Long ownerCount = tenantMemberMapper.selectCount(
                    new LambdaQueryWrapper<TenantMember>()
                            .eq(TenantMember::getTenantId, tenantId)
                            .eq(TenantMember::getRole, "OWNER"));
            if (ownerCount != null && ownerCount <= 1) {
                return Result.failure(ResultCode.BAD_REQUEST, "租户必须至少保留一个 OWNER");
            }
        }

        //最后在tenant_member表里更新对应记录的角色字段，表示这个用户在这个租户里的角色被修改了
        targetMembership.setRole(newRole);
        int updated = tenantMemberMapper.updateById(targetMembership);
        if (updated != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "更新成员角色失败");
        }
        // 若更新的是 ownerUserId 对应成员，保持 owner 字段与成员角色一致。
        if (tenant.getOwnerUserId() != null && tenant.getOwnerUserId().equals(userId) && !"OWNER".equals(newRole)) {
            TenantMember anotherOwner = tenantMemberMapper.selectOne(
                    new LambdaQueryWrapper<TenantMember>()
                            .eq(TenantMember::getTenantId, tenantId)
                            .eq(TenantMember::getRole, "OWNER")
                            .last("LIMIT 1"));
            if (anotherOwner != null) {
                tenant.setOwnerUserId(anotherOwner.getUserId());
                tenantMapper.updateById(tenant);
            }
        } else if ("OWNER".equals(newRole) && (tenant.getOwnerUserId() == null || !tenant.getOwnerUserId().equals(userId))) {
            tenant.setOwnerUserId(userId);
            tenantMapper.updateById(tenant);
        }

        //最后返回更新后的TenantMember对象，包装在Result里
        return Result.success(targetMembership);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Result<Void> deleteTenantMember(Long tenantId, Long userId) {
        //这个看起来也还可以，首先检查用户登陆状态（这绝对能单独抽出一个方法了），
        Result<TenantAccessGuard.AccessContext> access = tenantAccessGuard.requireTenantMember(Set.of(), "");
        if (!access.isSuccess() || !tenantId.equals(access.getData().principal().tenantId())) {
            return Result.failure(ResultCode.FORBIDDEN, "无权操作该租户成员");
        }
        JwtUserPrincipal principal = access.getData().principal();

        Tenant tenant = tenantMapper.selectById(tenantId);
        if (!isTenantUsable(tenant)) {
            return Result.failure(ResultCode.NOT_FOUND, "租户不存在、已禁用或已过期");
        }
        if (principal.userId().equals(userId)) {
            return Result.failure(ResultCode.BAD_REQUEST, "不支持通过该接口删除自己，请使用退出租户接口");
        }

        //然后检查用户在这个租户里的权限是否足够（至少要是管理员），OWNER可以删除其他任何成员，ADMIN有权删除成员（不能操作OWNER），如果权限不够则直接返回错误
        TenantMember operatorMembership = access.getData().membership();
        TenantMember targetMembership = findMembership(tenantId, userId);
        if (targetMembership == null) {
            return Result.failure(ResultCode.NOT_FOUND, "成员不存在");
        }
        String operatorRole = normalizeRole(operatorMembership.getRole());
        String targetRole = normalizeRole(targetMembership.getRole());
        if (!canDeleteMember(operatorRole, targetRole)) {
            return Result.failure(ResultCode.FORBIDDEN, "当前角色无删除该成员权限");
        }
        if ("OWNER".equals(targetRole)) {
            Long ownerCount = tenantMemberMapper.selectCount(
                    new LambdaQueryWrapper<TenantMember>()
                            .eq(TenantMember::getTenantId, tenantId)
                            .eq(TenantMember::getRole, "OWNER"));
            if (ownerCount != null && ownerCount <= 1) {
                return Result.failure(ResultCode.BAD_REQUEST, "租户必须至少保留一个 OWNER");
            }
        }

        //最后在tenant_member表里删除对应记录，表示这个用户被移出了这个租户
        int deleted = tenantMemberMapper.deleteById(targetMembership.getId());
        if (deleted != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "删除成员失败");
        }
        // 若删除的是 ownerUserId 指向用户，则同步 owner 字段到现存 OWNER。
        if (tenant.getOwnerUserId() != null && tenant.getOwnerUserId().equals(userId)) {
            TenantMember anotherOwner = tenantMemberMapper.selectOne(
                    new LambdaQueryWrapper<TenantMember>()
                            .eq(TenantMember::getTenantId, tenantId)
                            .eq(TenantMember::getRole, "OWNER")
                            .last("LIMIT 1"));
            if (anotherOwner != null) {
                tenant.setOwnerUserId(anotherOwner.getUserId());
                tenantMapper.updateById(tenant);
            }
        }

        //最后返回成功的Result对象，表示删除成功
        return Result.success();
    }

    private TenantMember findMembership(Long tenantId, Long userId) {
        return tenantMemberMapper.selectOne(
                new LambdaQueryWrapper<TenantMember>()
                        .eq(TenantMember::getTenantId, tenantId)
                        .eq(TenantMember::getUserId, userId));
    }

    private static String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            return null;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return ALL_ROLES.contains(normalized) ? normalized : null;
    }

    private static boolean canAddRole(String operatorRole, String targetRole) {
        if ("OWNER".equals(operatorRole)) {
            return true;
        }
        if ("ADMIN".equals(operatorRole)) {
            return "MEMBER".equals(targetRole) || "GUEST".equals(targetRole);
        }
        return false;
    }

    private static boolean canDeleteMember(String operatorRole, String targetRole) {
        if ("OWNER".equals(operatorRole)) {
            return true;
        }
        if ("ADMIN".equals(operatorRole)) {
            return !"OWNER".equals(targetRole);
        }
        return false;
    }

    private static boolean isTenantUsable(Tenant t) {
        if (t == null || t.getStatus() == null || t.getStatus() != TENANT_STATUS_ACTIVE) {
            return false;
        }
        return t.getExpireTime() == null || t.getExpireTime().isAfter(LocalDateTime.now());
    }
}
