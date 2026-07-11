package com.imperium.distributed_lite_scheduler_v1.config;

import com.imperium.distributed_lite_scheduler_v1.service.workflow.listener.TaskCompletionStreamListener;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.stream.TaskCompletionStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.net.InetAddress;
import java.time.Duration;

/**
 * 任务完成 Redis Stream 消费配置（Spring Data Redis {@link StreamMessageListenerContainer}）。
 */
@Configuration
@Slf4j
public class TaskCompletionRedisStreamConfig {

    /**
     * 使用稳定的消费者名（hostname + 进程 PID），保证 JVM 重启后消费者身份不变，
     * 使得 Redis Stream PEL 里的未 ACK 消息能在重启后被本实例继续认领，不会永久孤儿化。
     * K8s 环境优先使用 POD_NAME 环境变量。
     */
    @Bean
    String taskCompletionConsumerName() {
        String podName = System.getenv("POD_NAME");
        if (podName != null && !podName.isBlank()) {
            return TaskCompletionStreamConstants.CONSUMER_NAME_PREFIX + podName;
        }
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String pid = ProcessHandle.current().pid() + "";
            return TaskCompletionStreamConstants.CONSUMER_NAME_PREFIX + hostname + "-" + pid;
        } catch (Exception e) {
            log.warn("获取 hostname 失败，使用随机后缀", e);
            return TaskCompletionStreamConstants.CONSUMER_NAME_PREFIX
                    + Long.toHexString(System.currentTimeMillis());
        }
    }

    @Bean(destroyMethod = "stop")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> taskCompletionStreamContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate stringRedisTemplate,
            TaskCompletionStreamListener listener,
            String taskCompletionConsumerName) {

        ensureConsumerGroup(stringRedisTemplate);

        // 启动时认领孤儿 PEL 消息（超过 30 秒未 ACK 的），避免 DAG 因消费者宕机而卡死
        reclaimOrphanedPelMessages(stringRedisTemplate, taskCompletionConsumerName);

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .batchSize(1)
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(
                Consumer.from(TaskCompletionStreamConstants.CONSUMER_GROUP, taskCompletionConsumerName),
                StreamOffset.create(TaskCompletionStreamConstants.STREAM_KEY, ReadOffset.lastConsumed()),
                listener);
        container.start();

        log.info("任务完成 Stream 监听已启动 stream={} group={} consumer={}",
                TaskCompletionStreamConstants.STREAM_KEY,
                TaskCompletionStreamConstants.CONSUMER_GROUP,
                taskCompletionConsumerName);

        return container;
    }

    /**
     * 认领消费者组内所有空闲超过 30 秒的 PEL 消息到当前消费者。
     * 场景：其他消费者宕机后未 ACK 的消息会永久滞留在 PEL；本方法在启动时将它们转移给当前实例重新处理。
     * 配合 handler 的幂等检查，重复投递不会造成二次副作用。
     */
    private void reclaimOrphanedPelMessages(StringRedisTemplate stringRedisTemplate, String consumerName) {
        try {
            PendingMessages pendingMessages = stringRedisTemplate.opsForStream()
                    .pending(TaskCompletionStreamConstants.STREAM_KEY,
                            TaskCompletionStreamConstants.CONSUMER_GROUP,
                            Range.unbounded(), 100L);

            if (pendingMessages == null || !pendingMessages.iterator().hasNext()) {
                return;
            }

            int claimedCount = 0;
            for (PendingMessage msg : pendingMessages) {
                if (msg.getElapsedTimeSinceLastDelivery().toSeconds() >= 30) {
                    try {
                        stringRedisTemplate.opsForStream().claim(
                                TaskCompletionStreamConstants.STREAM_KEY,
                                TaskCompletionStreamConstants.CONSUMER_GROUP,
                                consumerName,
                                Duration.ofSeconds(30),
                                msg.getId());
                        claimedCount++;
                    } catch (Exception claimEx) {
                        log.warn("认领 PEL 消息失败 messageId={}", msg.getId(), claimEx);
                    }
                }
            }
            if (claimedCount > 0) {
                log.info("启动时成功认领孤儿 PEL 消息 count={} consumer={}", claimedCount, consumerName);
            }
        } catch (Exception e) {
            log.warn("PEL 消息扫描异常，跳过认领流程", e);
        }
    }

    private static void ensureConsumerGroup(StringRedisTemplate stringRedisTemplate) {
        try {
            stringRedisTemplate.opsForStream().createGroup(
                    TaskCompletionStreamConstants.STREAM_KEY,
                    TaskCompletionStreamConstants.CONSUMER_GROUP);
            log.info("创建 Stream 消费者组成功 stream={} group={}",
                    TaskCompletionStreamConstants.STREAM_KEY,
                    TaskCompletionStreamConstants.CONSUMER_GROUP);
        } catch (Exception e) {
            log.debug("消费者组已存在或创建跳过 stream={} group={}: {}",
                    TaskCompletionStreamConstants.STREAM_KEY,
                    TaskCompletionStreamConstants.CONSUMER_GROUP,
                    e.getMessage());
        }
    }
}
