package com.imperium.distributed_lite_scheduler_v1.config;

import com.imperium.distributed_lite_scheduler_v1.service.workflow.listener.TaskCompletionStreamListener;
import com.imperium.distributed_lite_scheduler_v1.service.workflow.stream.TaskCompletionStreamConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

import java.time.Duration;
import java.util.UUID;

/**
 * 任务完成 Redis Stream 消费配置（Spring Data Redis {@link StreamMessageListenerContainer}）。
 */
@Configuration
@Slf4j
public class TaskCompletionRedisStreamConfig {

    @Bean
    String taskCompletionConsumerName() {
        return TaskCompletionStreamConstants.CONSUMER_NAME_PREFIX
                + UUID.randomUUID().toString().substring(0, 8);
    }

    @Bean(destroyMethod = "stop")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> taskCompletionStreamContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate stringRedisTemplate,
            TaskCompletionStreamListener listener,
            String taskCompletionConsumerName) {

        ensureConsumerGroup(stringRedisTemplate);

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
