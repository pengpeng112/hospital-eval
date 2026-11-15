package com.hospital.eval.system;

import com.hospital.eval.system.service.PasswordUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新密码规则（≥8 位且含字母和数字）正反用例（TASK-05 步骤 5；不连数据库）。
 */
class PasswordRuleTest {

    @Test
    void validPasswords() {
        assertTrue(PasswordUtil.isValidNewPassword("abcd1234"));
        assertTrue(PasswordUtil.isValidNewPassword("Abcd12345678"));
        assertTrue(PasswordUtil.isValidNewPassword("12345678a"));
        assertTrue(PasswordUtil.isValidNewPassword("a1b2c3d4e5"));
    }

    @Test
    void invalidPasswords() {
        assertFalse(PasswordUtil.isValidNewPassword(null));
        assertFalse(PasswordUtil.isValidNewPassword(""));
        assertFalse(PasswordUtil.isValidNewPassword("abc123"));       // 少于 8 位
        assertFalse(PasswordUtil.isValidNewPassword("abcdefgh"));     // 无数字
        assertFalse(PasswordUtil.isValidNewPassword("12345678"));     // 无字母
        assertFalse(PasswordUtil.isValidNewPassword("!!!!!!!!"));     // 无字母无数字
        assertFalse(PasswordUtil.isValidNewPassword("!!!!!!1!"));     // 有数字但无字母
    }

    @Test
    void exactlyEightCharsMixed() {
        assertTrue(PasswordUtil.isValidNewPassword("zzzzzzz1"));
        assertFalse(PasswordUtil.isValidNewPassword("zzzzzz1"));     // 7 位
    }
}
