package com.hospital.eval.common;

/**
 * 业务异常：携带业务 code（400 业务错误等，04 §11）。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public BizException(String msg) {
        this(400, msg);
    }

    public int getCode() {
        return code;
    }
}
