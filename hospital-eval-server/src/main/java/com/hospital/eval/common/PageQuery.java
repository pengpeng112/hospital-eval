package com.hospital.eval.common;

/**
 * 分页请求：page 从 1 开始、size 默认 20 最大 200（04 §11）。
 */
public class PageQuery {

    private int page = 1;
    private int size = 20;

    public int getPage() {
        return Math.max(page, 1);
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        int s = size <= 0 ? 20 : size;
        return Math.min(s, 200);
    }

    public void setSize(int size) {
        this.size = size;
    }

    public int offset() {
        return (getPage() - 1) * getSize();
    }
}
