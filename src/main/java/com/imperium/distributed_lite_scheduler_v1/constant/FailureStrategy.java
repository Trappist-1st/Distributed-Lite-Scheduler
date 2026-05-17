package com.imperium.distributed_lite_scheduler_v1.constant;

/**
 * 失败处理策略枚举
 * 
 * 定义工作流遇到任务失败时的处理行为
 */
public enum FailureStrategy {
    
    /**
     * 遇到失败立即停止
     * 
     * 优点：
     * - 及时发现问题，节省资源
     * - 避免错误扩散
     * 
     * 缺点：
     * - 可能导致部分可成功的任务未执行
     * 
     * 适用场景：
     * - 严格依赖的工作流
     * - 生产环境关键任务
     * - 故障影响范围大的场景
     */
    STOP_ON_FAILURE("stop_on_failure", "遇到失败立即停止"),
    
    /**
     * 遇到失败继续执行
     * 
     * 优点：
     * - 尽可能完成更多任务
     * - 获取完整的执行结果
     * 
     * 缺点：
     * - 可能浪费资源执行注定失败的任务
     * 
     * 适用场景：
     * - 任务独立性强的工作流
     * - 数据分析类工作流
     * - 希望看到全部执行结果
     */
    CONTINUE_ON_FAILURE("continue_on_failure", "遇到失败继续执行");
    
    private final String code;
    private final String description;
    
    FailureStrategy(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 从代码获取枚举
     */
    public static FailureStrategy fromCode(String code) {
        for (FailureStrategy strategy : values()) {
            if (strategy.code.equals(code)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unknown failure strategy code: " + code);
    }
}
