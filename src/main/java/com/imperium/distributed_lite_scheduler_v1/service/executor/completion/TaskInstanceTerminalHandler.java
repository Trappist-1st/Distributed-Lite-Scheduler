package com.imperium.distributed_lite_scheduler_v1.service.executor.completion;

import com.imperium.distributed_lite_scheduler_v1.model.entity.TaskInstance;
import com.imperium.distributed_lite_scheduler_v1.service.ResourceSlotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务进入终态后的系统级收尾（资源释放等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskInstanceTerminalHandler {

    private final ResourceSlotService resourceSlotService;

    public void afterTerminal(TaskInstance taskInstance, String terminalStatus) {
        if (taskInstance == null || taskInstance.getId() == null) {
            return;
        }
        String releaseReason = mapReleaseReason(terminalStatus);
        if (releaseReason == null) {
            return;
        }
        try {
            resourceSlotService.releaseForTaskInstanceSystem(taskInstance.getId(), releaseReason);
        } catch (Exception e) {
            log.error(
                    "任务终态资源释放异常 taskInstanceId={} status={}",
                    taskInstance.getId(),
                    terminalStatus,
                    e);
        }
    }

    private static String mapReleaseReason(String terminalStatus) {
        if (terminalStatus == null) {
            return null;
        }
        return switch (terminalStatus.toUpperCase()) {
            case "SUCCESS" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            case "TIMEOUT" -> "TIMEOUT";
            case "CANCELLED" -> "CANCELLED";
            default -> null;
        };
    }
}
