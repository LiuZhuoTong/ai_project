package com.example.aiworkshop.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分页响应DTO
 * 
 * <p>用于封装分页查询结果，包含任务列表和分页信息。</p>
 */
@Data
@Builder
public class PageResponse<T> {

    /** 任务列表 */
    private List<T> content;

    /** 当前页码（从0开始） */
    private int pageNumber;

    /** 每页大小 */
    private int pageSize;

    /** 总元素数量 */
    private long totalElements;

    /** 总页数 */
    private int totalPages;

    /** 是否是第一页 */
    private boolean first;

    /** 是否是最后一页 */
    private boolean last;

    /** 是否有下一页 */
    private boolean hasNext;

    /** 是否有上一页 */
    private boolean hasPrevious;

    /** 统计信息 */
    private Statistics statistics;

    /**
     * 统计信息
     */
    @Data
    @Builder
    public static class Statistics {
        /** 全部任务数量 */
        private long total;
        /** 已完成数量 */
        private long success;
        /** 进行中数量 */
        private long processing;
        /** 排队中数量 */
        private long pending;
        /** 失败数量 */
        private long failed;
    }
}