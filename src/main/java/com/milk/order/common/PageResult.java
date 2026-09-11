package com.milk.order.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 统一分页返回结构
 */
@Data
public class PageResult<T> implements Serializable {

    private Long total;
    private Long pageNum;
    private Long pageSize;
    private List<T> list;

    private PageResult() {
    }

    private PageResult(Long total, Long pageNum, Long pageSize, List<T> list) {
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.list = list;
    }

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }

    public static <T> PageResult<T> of(Long total, Long pageNum, Long pageSize, List<T> list) {
        return new PageResult<>(total, pageNum, pageSize, list);
    }
}
