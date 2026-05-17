package com.imperium.distributed_lite_worker.executor;

import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ParameterTemplateResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_.-]+)}");

    private ParameterTemplateResolver() {
    }

    public static String resolve(String template, Map<String, Object> parameters) {
        if (!StringUtils.hasText(template) || parameters == null || parameters.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = parameters.get(key);
            String replacement = value == null ? matcher.group(0) : String.valueOf(value);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
