package com.imperium.distributed_lite_scheduler_v1.constant;

import lombok.Getter;

/**
 * 工作流实例状态枚举
 * 
 * 状态流转说明：
 * PENDING -> PREPARING -> RUNNING -> SUCCESS/FAILED/PARTIAL_SUCCESS
 *                            ↓
 *                         PAUSED -> RUNNING (恢复)
 *                            ↓
 *                        CANCELLED
 */
@Getter
public enum WorkflowInstanceStatus {
    
    /**
     * 等待执行
     * 实例已创建，等待被执行引擎调度
     */
    PENDING("pending", "等待执行"),
    
    /**
     * 准备中
     * 正在初始化任务实例、构建执行计划
     */
    PREPARING("preparing", "准备中"),
    
    /**
     * 执行中
     * 工作流正在执行，至少有一个任务在运行
     */
    RUNNING("running", "执行中"),
    
    /**
     * 已暂停
     * 用户主动暂停，可以恢复执行
     */
    PAUSED("paused", "已暂停"),
    
    /**
     * 全部成功
     * 所有任务都执行成功
     */
    SUCCESS("success", "全部成功"),
    
    /**
     * 失败
     * 有任务失败且采用STOP_ON_FAILURE策略，工作流终止
     */
    FAILED("failed", "失败"),
    
    /**
     * 部分成功
     * 有任务失败但采用CONTINUE_ON_FAILURE策略，继续执行到结束
     */
    PARTIAL_SUCCESS("partial_success", "部分成功"),
    
    /**
     * 已取消
     * 用户主动取消，不可恢复
     */
    CANCELLED("cancelled", "已取消");
    
    private final String code;
    private final String description;
    
    WorkflowInstanceStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 从代码获取枚举
     */
    public static WorkflowInstanceStatus fromCode(String code) {
        for (WorkflowInstanceStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown workflow instance status code: " + code);
    }
    
    /**
     * 判断是否为终止状态
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == PARTIAL_SUCCESS || this == CANCELLED;
    }
    
    /**
     * 判断是否可以暂停
     */
    public boolean canPause() {
        return this == RUNNING;
    }
    
    /**
     * 判断是否可以恢复
     */
    public boolean canResume() {
        return this == PAUSED;
    }
    
    /**
     * 判断是否可以取消
     */
    public boolean canCancel() {
        return this == PENDING || this == PREPARING || this == RUNNING || this == PAUSED;
    }
}
