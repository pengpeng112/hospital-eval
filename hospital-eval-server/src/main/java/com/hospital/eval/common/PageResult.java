package com.hospital.eval.common;

import java.util.List;

/**
 * 分页返回：{"total":0,"list":[]}（04 §11）。
 */
public class PageResult<T> {

    private long total;
    private List<T> list;

    public PageResult() {
    }

    public PageResult(long total, List<T> list) {
        this.total = total;
        this.list = list;
    }

    public static <T> PageResult<T> of(long total, List<T> list) {
        return new PageResult<>(total, list);
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }
}
