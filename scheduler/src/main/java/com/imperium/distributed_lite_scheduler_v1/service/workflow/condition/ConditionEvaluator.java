package com.imperium.distributed_lite_scheduler_v1.service.workflow.condition;

import com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.condition.ConditionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 条件表达式求值器（P4-4）。使用 Spring SpEL + {@link SimpleEvaluationContext}（只读绑定，禁类型解析等）。
 *
 * @see com.imperium.distributed_lite_scheduler_v1.model.dto.workflow.WorkflowDependency#getCondition()
 */
@Service
@Slf4j
public class ConditionEvaluator {

    private static final Pattern TYPE_REF_T = Pattern.compile("(?<![\\w$])T\\(");

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentMap<String, Expression> parsedCache = new ConcurrentHashMap<>();

    /**
     * 判断依赖边条件是否成立。
     *
     * @param conditionExpr null 或空白表示恒为 true
     */
    public boolean evaluate(String conditionExpr, ConditionContext context) {
        if (!StringUtils.hasText(conditionExpr)) {
            return true;
        }
        assertSafeExpression(conditionExpr);
        String normalized = normalizeExpression(conditionExpr.trim());
        long t0 = System.nanoTime();
        try {
            SimpleEvaluationContext evalContext =
                    SimpleEvaluationContext.forReadOnlyDataBinding().build();
            evalContext.setVariable("tasks", context.getTasks());
            evalContext.setVariable("context", context.getContext());
            evalContext.setVariable("system", context.getSystem());

            Expression exp = parsedCache.computeIfAbsent(normalized, parser::parseExpression);
            Object value = exp.getValue(evalContext);
            boolean result = coerceToBoolean(value);
            if (log.isDebugEnabled()) {
                log.debug(
                        "P4-4 SpEL evaluated instanceContext in {}ms result={} expr={}",
                        (System.nanoTime() - t0) / 1_000_000,
                        result,
                        truncateForLog(normalized));
            }
            return result;
        } catch (Exception e) {
            log.warn(
                    "P4-4 条件求值失败，按 false 处理: expr={}",
                    truncateForLog(normalized),
                    e);
            return false;
        }
    }

    /**
     * 校验表达式语法（解析即可）；用于 DAG 保存时快速失败。
     */
    public void validateSyntax(String conditionExpr) {
        if (!StringUtils.hasText(conditionExpr)) {
            return;
        }
        assertSafeExpression(conditionExpr);
        String normalized = normalizeExpression(conditionExpr.trim());
        try {
            parser.parseExpression(normalized);
        } catch (ParseException e) {
            throw new IllegalArgumentException(
                    "条件表达式语法无效: " + truncateForLog(normalized), e);
        }
    }

    /** 兼容占位符写法 {@code ${ #tasks['a'].status }}
     * ，去掉最外层 {@code ${...}} 后 trimmed。 */
    private static String normalizeExpression(String expr) {
        String e = expr.trim();
        if (e.startsWith("${") && e.endsWith("}") && e.length() > 3) {
            return e.substring(2, e.length() - 1).trim();
        }
        return e;
    }

    /**
     * 阻断常见高风险 SpEL 片段（不改变合法 {@code #tasks[...]} 访问）。
     */
    private static void assertSafeExpression(String expr) {
        String lower = expr.toLowerCase(Locale.ROOT);
        if (expr.contains("\n") || expr.contains("\r")) {
            throw new IllegalArgumentException("条件表达式不允许换行");
        }
        // 不允许 SpEL 类型引用 T(com.foo.Bar)
        if (TYPE_REF_T.matcher(expr).find()) {
            throw new IllegalArgumentException(
                    "条件表达式不允许类型引用语法 T(...)，请仅用 #tasks / #context / #system");
        }
        if (expr.contains("@")) {
            throw new IllegalArgumentException("条件表达式不允许 @Bean 引用");
        }
        if (lower.contains(".getclass(")) {
            throw new IllegalArgumentException("条件表达式不允许反射类探测");
        }
        if (lower.contains("runtime") && lower.matches(".*java\\.lang\\.runtime.*")) {
            throw new IllegalArgumentException("条件表达式不安全");
        }
        if (lower.contains(" new ")) {
            throw new IllegalArgumentException("条件表达式不允许使用 new");
        }
    }

    private static boolean coerceToBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0d;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s) || "1".equals(s);
        }
        return true;
    }

    private static String truncateForLog(String expr) {
        if (expr == null) {
            return "";
        }
        return expr.length() > 200 ? expr.substring(0, 200) + "..." : expr;
    }
}
