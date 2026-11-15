package com.hospital.eval.algo.sandbox;

/**
 * SQL 注释剥离器（04 §7.4 状态机的 Java 版，与 migration/common/excel_utils.py
 * 的 strip_sql_comments 逐行为对应，一致性由 SqlCommentStripperConsistencyTest 保证）。
 *
 * 状态机：普通 / 单引号字符串 / 双引号标识符 / 行注释 / 块注释；
 * 字符串内 '' 为转义；行注释到换行为止但保留换行符；块注释整体替换为一个空格；
 * 字符串与标识符中的 -- 与 // * / 原样保留；输出去首尾空白并去掉末尾分号。
 */
public final class SqlCommentStripper {

    private static final int NORMAL = 0;
    private static final int STRING = 1;
    private static final int IDENT = 2;
    private static final int LINE = 3;
    private static final int BLOCK = 4;

    private SqlCommentStripper() {
    }

    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        int state = NORMAL;
        while (i < n) {
            char ch = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            switch (state) {
                case NORMAL:
                    if (ch == '\'') {
                        state = STRING;
                        out.append(ch);
                        i++;
                    } else if (ch == '"') {
                        state = IDENT;
                        out.append(ch);
                        i++;
                    } else if (ch == '-' && next == '-') {
                        state = LINE;
                        i += 2;
                    } else if (ch == '/' && next == '*') {
                        state = BLOCK;
                        i += 2;
                    } else {
                        out.append(ch);
                        i++;
                    }
                    break;
                case STRING:
                    if (ch == '\'') {
                        if (next == '\'') { // 字符串内 '' 转义，原样保留
                            out.append("''");
                            i += 2;
                            continue;
                        }
                        state = NORMAL;
                    }
                    out.append(ch);
                    i++;
                    break;
                case IDENT:
                    if (ch == '"') {
                        state = NORMAL;
                    }
                    out.append(ch);
                    i++;
                    break;
                case LINE:
                    if (ch == '\n') { // 行注释结束，保留换行符
                        out.append(ch);
                        state = NORMAL;
                    }
                    i++;
                    break;
                default: // BLOCK：整块丢弃，结束时替换为一个空格
                    if (ch == '*' && next == '/') {
                        out.append(' ');
                        state = NORMAL;
                        i += 2;
                    } else {
                        i++;
                    }
                    break;
            }
        }
        String result = out.toString().strip();
        if (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
