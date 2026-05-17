package com.imperium.distributed_lite_worker.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ExecutorConfigSupport {

    private final ObjectMapper objectMapper;

    public ExecutorConfigSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    public String readText(JsonNode root, String field) {
        if (root == null || !root.hasNonNull(field)) {
            return null;
        }
        return root.get(field).asText();
    }

    public List<String> readStringList(JsonNode root, String field) {
        if (root == null || !root.has(field) || !root.get(field).isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode node : root.get(field)) {
            list.add(node.asText());
        }
        return list;
    }

    public Map<String, String> readStringMap(JsonNode root, String field) {
        if (root == null || !root.has(field) || !root.get(field).isObject()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        JsonNode objectNode = root.get(field);
        objectNode.fieldNames().forEachRemaining(name -> map.put(name, objectNode.get(name).asText()));
        return map;
    }
}
