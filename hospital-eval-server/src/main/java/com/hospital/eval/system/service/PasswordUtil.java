package com.hospital.eval.system.service;

import java.util.regex.Pattern;

/**
 * 密码规则（04 §12.1）：新密码 ≥ 8 位且必须同时含字母和数字。
 */
public final class PasswordUtil {

    private static final Pattern LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern DIGIT = Pattern.compile(".*[0-9].*");

    private PasswordUtil() {
    }

    public static boolean isValidNewPassword(String pwd) {
        return pwd != null && pwd.length() >= 8 && LETTER.matcher(pwd).matches() && DIGIT.matcher(pwd).matches();
    }
}
