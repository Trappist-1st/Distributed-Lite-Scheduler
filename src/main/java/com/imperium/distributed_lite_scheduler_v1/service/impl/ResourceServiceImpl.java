package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.imperium.distributed_lite_scheduler_v1.mapper.ResourceNodeMapper;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ListResourceNodesRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.RegisterResourceNodeRequest;
import com.imperium.distributed_lite_scheduler_v1.model.dto.ResourceHeartbeatRequest;
import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceService;
import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ResourceServiceImpl extends ServiceImpl<ResourceNodeMapper, ResourceNode> implements ResourceService {

    private static final Logger log = LoggerFactory.getLogger(ResourceServiceImpl.class);

    /** 设计稿约定：可被调度器纳入候选池 */
    private static final String STATUS_ONLINE = "ONLINE";
    /** 设计稿约定：超时或主动下线后不可调度 */
    private static final String STATUS_OFFLINE = "OFFLINE";

    /** 注册/心跳在 @Version 冲突时的有限重试次数，贴合设计稿「幂等与最后写入为准」 */
    private static final int OPTIMISTIC_UPDATE_RETRY = 5;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ResourceNode> registerNode(RegisterResourceNodeRequest request) {
        // node_host + node_port 为唯一业务键：存在则走更新（重注册），否则插入
        String host = request.nodeHost().trim();
        int port = request.nodePort();

        ResourceNode existing = findByHostPort(host, port);
        LocalDateTime now = LocalDateTime.now();

        if (existing != null) {
            // 分支 1：已存在 —— upsert 语义下重复注册走更新（设计稿 4.1、6.1）
            for (int attempt = 0; attempt < OPTIMISTIC_UPDATE_RETRY; attempt++) {
                ResourceNode row = findByHostPort(host, port);
                if (row == null) {
                    break;
                }
                fillRegisterStaticFields(row, request);
                row.setTotalCpu(request.totalCpu());
                row.setTotalMemoryMb(request.totalMemoryMb());
                row.setTotalGpu(request.totalGpu());
                // 重注册视为 Worker 重新声明整机容量，可用量与总量对齐，避免残留旧快照
                row.setAvailableCpu(request.totalCpu());
                row.setAvailableMemoryMb(request.totalMemoryMb());
                row.setAvailableGpu(request.totalGpu());
                row.setStatus(STATUS_ONLINE);
                row.setLastHeartbeatTime(now);

                int updated = baseMapper.updateById(row);
                if (updated == 1) {
                    return Result.success(baseMapper.selectById(row.getId()));
                }
            }
            return Result.failure(ResultCode.CONFLICT, "节点信息并发更新冲突，请重试");
        }

        // 分支 2：新建 —— 初始 available = total，状态 ONLINE（设计稿 4.1）
        ResourceNode created = new ResourceNode();
        fillRegisterStaticFields(created, request);
        created.setTotalCpu(request.totalCpu());
        created.setTotalMemoryMb(request.totalMemoryMb());
        created.setTotalGpu(request.totalGpu());
        created.setAvailableCpu(request.totalCpu());
        created.setAvailableMemoryMb(request.totalMemoryMb());
        created.setAvailableGpu(request.totalGpu());
        created.setStatus(STATUS_ONLINE);
        created.setLastHeartbeatTime(now);

        int inserted = baseMapper.insert(created);
        if (inserted != 1) {
            return Result.failure(ResultCode.INTERNAL_ERROR, "注册节点失败");
        }
        return Result.success(created);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<ResourceNode> heartbeat(ResourceHeartbeatRequest request) {
        String host = request.nodeHost().trim();
        int port = request.nodePort();

        // 设计稿 4.2：先采用严格模式，找不到则 NOT_FOUND（不隐式自动注册）
        for (int attempt = 0; attempt < OPTIMISTIC_UPDATE_RETRY; attempt++) {
            ResourceNode node = findByHostPort(host, port);
            if (node == null) {
                return Result.failure(ResultCode.NOT_FOUND, "节点不存在，请先注册");
            }

            LocalDateTime now = LocalDateTime.now();
            node.setLastHeartbeatTime(now);

            // 可用资源为可选字段：未传则保留库中旧值，避免空值覆盖有效快照
            if (request.availableCpu() != null) {
                node.setAvailableCpu(request.availableCpu());
            }
            if (request.availableMemoryMb() != null) {
                node.setAvailableMemoryMb(request.availableMemoryMb());
            }
            if (request.availableGpu() != null) {
                node.setAvailableGpu(request.availableGpu());
            }

            // 状态：显式传入则采纳；否则 OFFLINE 节点通过心跳自动拉回 ONLINE，MAINTENANCE 保持不变（设计稿 4.2）
            if (StringUtils.hasText(request.status())) {
                node.setStatus(request.status().trim());
            } else if (STATUS_OFFLINE.equals(node.getStatus())) {
                node.setStatus(STATUS_ONLINE);
            }

            int updated = baseMapper.updateById(node);
            if (updated == 1) {
                return Result.success(baseMapper.selectById(node.getId()));
            }
            // updated == 0：通常为 @Version 乐观锁不匹配，重读再合并，保证最后写入可见
        }
        return Result.failure(ResultCode.CONFLICT, "心跳并发更新冲突，请重试");
    }

    @Override
    public Result<List<ResourceNode>> listNodes(ListResourceNodesRequest request) {
        int page = request.page();
        int size = request.size();
        int offset = (page - 1) * size;

        LambdaQueryWrapper<ResourceNode> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(request.status())) {
            qw.eq(ResourceNode::getStatus, request.status().trim());
        }
        if (StringUtils.hasText(request.nodeType())) {
            qw.eq(ResourceNode::getNodeType, request.nodeType().trim());
        }
        if (StringUtils.hasText(request.keyword())) {
            String keyword = request.keyword().trim();
            // 关键词同时匹配节点名与主机地址，便于控制台检索（设计稿 4.3）
            qw.and(w -> w.like(ResourceNode::getNodeName, keyword).or().like(ResourceNode::getNodeHost, keyword));
        }
        qw.orderByDesc(ResourceNode::getLastHeartbeatTime)
                .orderByDesc(ResourceNode::getId)
                .last("LIMIT " + offset + ", " + size);

        return Result.success(baseMapper.selectList(qw));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Integer> offlineTimeoutNodes(Integer heartbeatTimeoutSeconds) {
        if (heartbeatTimeoutSeconds == null || heartbeatTimeoutSeconds < 1) {
            return Result.failure(ResultCode.BAD_REQUEST, "heartbeatTimeoutSeconds 须为正整数");
        }

        LocalDateTime threshold = LocalDateTime.now().minusSeconds(heartbeatTimeoutSeconds);

        // 仅扫描当前仍为 ONLINE 的节点；last_heartbeat 为空视为从未心跳，一并下线（设计稿 5.2）
        List<ResourceNode> candidates = baseMapper.selectList(
                new LambdaQueryWrapper<ResourceNode>()
                        .eq(ResourceNode::getStatus, STATUS_ONLINE)
                        .and(w -> w.isNull(ResourceNode::getLastHeartbeatTime)
                                .or()
                                .lt(ResourceNode::getLastHeartbeatTime, threshold)));

        if (candidates.isEmpty()) {
            return Result.success(0);
        }

        // 设计稿 5.2：记录系统日志，便于排查「何时、因何」被判定超时
        LocalDateTime offlineAt = LocalDateTime.now();
        for (ResourceNode n : candidates) {
            log.info(
                    "Resource node offline by heartbeat timeout: id={}, host={}, port={}, lastHeartbeatTime={}, offlineAt={}, timeoutSeconds={}",
                    n.getId(), n.getNodeHost(), n.getNodePort(), n.getLastHeartbeatTime(), offlineAt, heartbeatTimeoutSeconds);
        }

        // 批量将仍满足条件的节点标为 OFFLINE（二次带 status 条件，降低与心跳并发时的误伤）
        LambdaUpdateWrapper<ResourceNode> uw = new LambdaUpdateWrapper<>();
        uw.set(ResourceNode::getStatus, STATUS_OFFLINE)
                .eq(ResourceNode::getStatus, STATUS_ONLINE)
                .in(ResourceNode::getId, candidates.stream().map(ResourceNode::getId).toList())
                .and(w -> w.isNull(ResourceNode::getLastHeartbeatTime).or().lt(ResourceNode::getLastHeartbeatTime, threshold));

        int affected = baseMapper.update(null, uw);
        return Result.success(affected);
    }

    private ResourceNode findByHostPort(String host, int port) {
        return baseMapper.selectOne(
                new LambdaQueryWrapper<ResourceNode>()
                        .eq(ResourceNode::getNodeHost, host)
                        .eq(ResourceNode::getNodePort, port));
    }

    private static void fillRegisterStaticFields(ResourceNode target, RegisterResourceNodeRequest request) {
        target.setNodeName(request.nodeName().trim());
        target.setNodeHost(request.nodeHost().trim());
        target.setNodePort(request.nodePort());
        target.setNodeType(request.nodeType().trim());
        target.setGpuModel(StringUtils.hasText(request.gpuModel()) ? request.gpuModel().trim() : null);
        target.setLabels(StringUtils.hasText(request.labels()) ? request.labels().trim() : null);
    }
}
