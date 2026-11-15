package com.hospital.eval.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（04 §11 / TASK-05 步骤 3）：
 * BizException→对应 code；NotLogin→401；NotRole/NotPermission→403；
 * 参数校验失败→400 取第一个字段错误；其他→500"系统错误"并记日志堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(401, "未登录或登录已过期"));
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<R<Void>> handleNoPermission(Exception e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(403, "无权限"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "参数校验失败" : fe.getDefaultMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        log.error("系统错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail(500, "系统错误"));
    }
}
