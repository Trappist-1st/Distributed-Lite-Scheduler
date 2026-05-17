package com.imperium.distributed_lite_scheduler_v1.constant;

import lombok.Getter;

/**
 * 触发类型枚举
 * 
 * 定义工作流实例是如何被触发执行的
 */
@Getter
public enum TriggerType {
    
    /**
     * 手动触发
     * 用户在界面或命令行中主动触发
     */
    MANUAL("manual", "手动触发"),
    
    /**
     * 定时触发
     * 通过Cron表达式定时自动触发
     */
    SCHEDULED("scheduled", "定时触发"),
    
    /**
     * API触发
     * 通过REST API调用触发
     */
    API("api", "API触发");
    
    private final String code;
    private final String description;
    
    TriggerType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 从代码获取枚举
     */
    public static TriggerType fromCode(String code) {
        for (TriggerType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown trigger type code: " + code);
    }
}
