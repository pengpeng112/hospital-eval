package com.hospital.eval.algo.sandbox;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 SQL 沙箱校验器（TASK-06 步骤 2，04 §8.4）。
 *
 * 校验顺序（任一步失败即 valid=false 并记录原因）：
 * 1) 去注释；2) 字符串常量之外出现分号 → 多语句；
 * 3) 去前导空白与左括号后必须以 SELECT/WITH 开头；
 * 4) 字符串内容置空后按单词边界匹配关键字黑名单（DBMS_ 排除 DBMS_LOB 的
 *    SUBSTR/GETLENGTH/INSTR 三个 CLOB 读取函数）；5) JSqlParser 解析——
 *    解析成功必须是 Select，解析失败不判非法、只记 warning。
 */
@Component
public class ReadOnlySqlValidator {

    private static final Pattern START_RE = Pattern.compile("^(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);
    private static final String[] KEYWORDS = {
            "INSERT", "UPDATE", "DELETE", "MERGE", "DROP", "CREATE", "ALTER", "TRUNCATE",
            "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT", "EXECUTE", "EXEC", "CALL",
            "BEGIN", "DECLARE", "LOCK", "RENAME", "COMMENT", "PURGE", "FLASHBACK"
    };
    private static final Pattern DBMS_RE = Pattern.compile("\\bDBMS_[A-Za-z0-9_.]*");
    private static final Pattern UTL_RE = Pattern.compile("\\bUTL_[A-Za-z0-9_.]*");
    private static final Pattern FOR_UPDATE_RE = Pattern.compile("\\bFOR\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTO_RE = Pattern.compile("\\bINTO\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> DBMS_ALLOWED = Set.of(
            "DBMS_LOB.SUBSTR", "DBMS_LOB.GETLENGTH", "DBMS_LOB.INSTR");

    public ValidationResult validate(String sqlAnnotated) {
        ValidationResult r = new ValidationResult();
        String exec = SqlCommentStripper.strip(sqlAnnotated);
        r.setExecutableSql(exec);
        if (exec.isBlank()) {
            r.addError("SQL为空");
            return finish(r);
        }
        // 2) 多语句：字符串常量之外出现分号
        if (hasSemicolonOutsideStrings(exec)) {
            r.addError("禁止多条语句");
            return finish(r);
        }
        // 3) 开头检查（允许前导空白与左括号）
        String core = exec.replaceAll("^[\\s(]+", "");
        if (!START_RE.matcher(core).find()) {
            r.addError("只允许 SELECT / WITH 查询");
            return finish(r);
        }
        // 4) 关键字黑名单（字符串内容替换为空格）
        String masked = maskStrings(exec);
        for (String kw : KEYWORDS) {
            Matcher m = Pattern.compile("\\b" + kw + "\\b", Pattern.CASE_INSENSITIVE).matcher(masked);
            if (m.find()) {
                r.addError("检测到禁止关键字: " + m.group().toUpperCase());
                return finish(r);
            }
        }
        if (FOR_UPDATE_RE.matcher(masked).find()) {
            r.addError("检测到禁止关键字: FOR UPDATE");
            return finish(r);
        }
        if (INTO_RE.matcher(masked).find()) {
            r.addError("检测到禁止关键字: INTO");
            return finish(r);
        }
        String dbmsErr = checkPrefixFunctions(masked, DBMS_RE, DBMS_ALLOWED);
        if (dbmsErr != null) {
            r.addError(dbmsErr);
            return finish(r);
        }
        String utlErr = checkPrefixFunctions(masked, UTL_RE, Set.of());
        if (utlErr != null) {
            r.addError(utlErr);
            return finish(r);
        }
        if (!r.getErrors().isEmpty()) {
            return finish(r);
        }
        // 5) JSqlParser 解析（失败不判非法，记 warning）
        try {
            net.sf.jsqlparser.statement.Statement st = CCJSqlParserUtil.parse(exec);
            if (!(st instanceof Select)) {
                r.addError("不是查询语句");
            }
        } catch (Exception e) {
            r.addWarning("JSqlParser 无法解析，已按关键字规则校验");
        }
        return finish(r);
    }

    private ValidationResult finish(ValidationResult r) {
        r.setValid(r.getErrors().isEmpty());
        return r;
    }

    /** 字符串/标识符常量之外出现分号 → true。 */
    private boolean hasSemicolonOutsideStrings(String sql) {
        int state = 0; // 0 普通 1 字符串 2 标识符
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (state == 0) {
                if (ch == '\'') {
                    state = 1;
                } else if (ch == '"') {
                    state = 2;
                } else if (ch == ';') {
                    return true;
                }
            } else if (state == 1) {
                if (ch == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        state = 0;
                    }
                }
            } else if (ch == '"') {
                state = 0;
            }
        }
        return false;
    }

    /** 把字符串与标识符的内容替换为空格（保留引号本身），供关键字黑名单检查。 */
    private String maskStrings(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int state = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (state == 0) {
                if (ch == '\'') {
                    state = 1;
                    sb.append(ch);
                } else if (ch == '"') {
                    state = 2;
                    sb.append(ch);
                } else {
                    sb.append(ch);
                }
            } else if (state == 1) {
                if (ch == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        sb.append("  ");
                        i++;
                    } else {
                        state = 0;
                        sb.append(ch);
                    }
                } else {
                    sb.append(' ');
                }
            } else {
                if (ch == '"') {
                    state = 0;
                    sb.append(ch);
                } else {
                    sb.append(' ');
                }
            }
        }
        return sb.toString();
    }

    /** DBMS_/UTL_ 前缀检查；allowed 中的完整标识符（及以其为前缀者）放行。返回 null=通过。 */
    private String checkPrefixFunctions(String masked, Pattern prefixRe, Set<String> allowed) {
        Matcher m = prefixRe.matcher(masked);
        while (m.find()) {
            String id = m.group().toUpperCase();
            while (id.endsWith(".")) {
                id = id.substring(0, id.length() - 1);
            }
            String finalId = id;
            boolean ok = allowed.stream().anyMatch(a -> finalId.equals(a) || finalId.startsWith(a + "."));
            if (!ok) {
                return "检测到禁止关键字: " + id;
            }
        }
        return null;
    }
}
