package com.hospital.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson 配置：时间按 application.yml（Asia/Shanghai / yyyy-MM-dd HH:mm:ss）；
 * Long 序列化为字符串，避免前端 JS 对 BIGINT 精度丢失。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper mapper = builder.build();
        var simple = new com.fasterxml.jackson.databind.module.SimpleModule();
        simple.addSerializer(Long.class, ToStringSerializer.instance);
        simple.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper.registerModule(simple);
        return mapper;
    }
}
