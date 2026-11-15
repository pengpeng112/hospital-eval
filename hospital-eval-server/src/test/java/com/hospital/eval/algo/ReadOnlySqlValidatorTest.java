package com.hospital.eval.algo;

import com.hospital.eval.algo.sandbox.ReadOnlySqlValidator;
import com.hospital.eval.algo.sandbox.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读沙箱校验器用例（TASK-06 步骤 5，全部必须通过；不连数据库）。
 */
class ReadOnlySqlValidatorTest {

    private final ReadOnlySqlValidator validator = new ReadOnlySqlValidator();

    private ValidationResult validate(String sql) {
        return validator.validate(sql);
    }

    @Test
    void validSelect() {
        assertTrue(validate("SELECT 1 FROM dual").isValid());
    }

    @Test
    void validWithCte() {
        assertTrue(validate("WITH a AS (SELECT 1 x FROM dual) SELECT * FROM a").isValid());
    }

    @Test
    void validSemicolonInsideString() {
        // 字符串中的 -- 与 ; 不算注释/多语句
        assertTrue(validate("SELECT '--x;' AS a FROM dual -- 注释").isValid());
    }

    @Test
    void validUnionAll() {
        assertTrue(validate("SELECT a FROM t UNION ALL SELECT b FROM t2").isValid());
    }

    @Test
    void validDbmsLobSubstr() {
        assertTrue(validate("SELECT DBMS_LOB.SUBSTR(c, 200, 1) FROM t").isValid());
    }

    @Test
    void validKeywordInsideString() {
        assertTrue(validate("SELECT 'DELETE' AS a FROM dual").isValid());
    }

    @Test
    void invalidDelete() {
        assertFalse(validate("DELETE FROM t").isValid());
    }

    @Test
    void invalidMultiStatement() {
        assertFalse(validate("SELECT 1 FROM dual; DELETE FROM t").isValid());
    }

    @Test
    void invalidForUpdate() {
        assertFalse(validate("SELECT * FROM t FOR UPDATE").isValid());
    }

    @Test
    void invalidSelectInto() {
        assertFalse(validate("SELECT a INTO b FROM t").isValid());
    }

    @Test
    void invalidBlockCommentThenUpdate() {
        assertFalse(validate("/* x */ UPDATE t SET a=1").isValid());
    }

    @Test
    void invalidPlsqlBlock() {
        assertFalse(validate("BEGIN NULL; END;").isValid());
    }

    @Test
    void invalidDbmsRandom() {
        assertFalse(validate("SELECT DBMS_RANDOM.VALUE FROM dual").isValid());
    }

    @Test
    void invalidCallAfterComment() {
        assertFalse(validate("--注释\nCALL p()").isValid());
    }
}
