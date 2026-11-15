package com.hospital.eval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.eval.common.R;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R.ok / R.fail 序列化结构（{"code":0,"msg":"ok","data":...}）——不连数据库。
 */
class RTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void okWithData() throws Exception {
        String json = mapper.writeValueAsString(R.ok("hello"));
        JsonNode node = mapper.readTree(json);
        assertEquals(0, node.get("code").asInt());
        assertEquals("ok", node.get("msg").asText());
        assertEquals("hello", node.get("data").asText());
    }

    @Test
    void okWithoutData() throws Exception {
        String json = mapper.writeValueAsString(R.ok());
        JsonNode node = mapper.readTree(json);
        assertEquals(0, node.get("code").asInt());
        assertEquals("ok", node.get("msg").asText());
        assertTrue(node.get("data").isNull(), "data 应序列化为 JSON null");
    }

    @Test
    void failWithCodeAndMsg() throws Exception {
        String json = mapper.writeValueAsString(R.fail(400, "用户名或密码错误"));
        JsonNode node = mapper.readTree(json);
        assertEquals(400, node.get("code").asInt());
        assertEquals("用户名或密码错误", node.get("msg").asText());
        assertTrue(node.get("data").isNull(), "data 应序列化为 JSON null");
    }
}
