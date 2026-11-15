package com.hospital.eval.algo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.eval.algo.sandbox.ReadOnlySqlValidator;
import com.hospital.eval.algo.sandbox.SqlCommentStripper;
import com.hospital.eval.algo.sandbox.ValidationResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Java 剥离器与 Python 产物一致性 + 沙箱合法比例（TASK-06 步骤 5）。
 *
 * 读取 ../migration/dist/sqls.json（Docker 构建上下文里不存在 → assumeTrue 跳过；
 * 本地验收命令挂载整个项目目录运行时生效）。固定随机种子 42 取 200 条。
 */
class SqlCommentStripperConsistencyTest {

    @Test
    void stripConsistencyAndValidRatio() throws Exception {
        Path path = Paths.get("../migration/dist/sqls.json");
        Assumptions.assumeTrue(Files.exists(path), "migration/dist/sqls.json 不存在（Docker 构建上下文），跳过");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readString(path));
        List<JsonNode> sqls = new ArrayList<>();
        root.get("sqls").forEach(sqls::add);
        Assertions.assertFalse(sqls.isEmpty(), "sqls.json 为空");
        Collections.shuffle(sqls, new Random(42));

        int n = Math.min(200, sqls.size());
        ReadOnlySqlValidator validator = new ReadOnlySqlValidator();
        int mismatch = 0;
        int validCount = 0;
        List<String> invalidSamples = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            JsonNode item = sqls.get(i);
            String annotated = item.get("sql_annotated").asText();
            String expected = item.get("sql_executable").asText();
            String actual = SqlCommentStripper.strip(annotated);
            if (!actual.equals(expected)) {
                mismatch++;
                if (mismatch <= 3) {
                    System.out.println("MISMATCH #" + i
                            + " expected=[" + preview(expected) + "]"
                            + " actual=[" + preview(actual) + "]");
                }
            }
            ValidationResult vr = validator.validate(actual);
            if (vr.isValid()) {
                validCount++;
            } else if (invalidSamples.size() <= 20) {
                invalidSamples.add("#" + i + " " + String.join("；", vr.getErrors())
                        + " | " + preview(actual));
            }
        }
        System.out.println("一致性：mismatch=" + mismatch + " / " + n);
        double ratio = n == 0 ? 0 : (double) validCount / n;
        System.out.println("validate 合法比例=" + String.format("%.4f", ratio) + "（" + validCount + "/" + n + "）");
        if (!invalidSamples.isEmpty()) {
            System.out.println("不合法清单：");
            invalidSamples.forEach(System.out::println);
        }
        Assertions.assertEquals(0, mismatch, "Java strip 与 Python sql_executable 不一致");
        Assertions.assertTrue(ratio >= 0.99, "合法比例低于 99%：" + ratio);
    }

    private String preview(String s) {
        s = s.replaceAll("\\s+", " ");
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
    }
}
