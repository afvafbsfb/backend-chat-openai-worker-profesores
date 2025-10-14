package com.workers.profesores.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaginationInfo {
    private Integer page;
    private Integer size;
    private Integer returned;
    private Boolean hasMore;
    private Integer nextPage;
    private Integer prevPage;
    private Integer total;

    public PaginationInfo() {}
    public PaginationInfo(Integer page, Integer size, Integer returned, Boolean hasMore, Integer nextPage, Integer prevPage, Integer total) {
        this.page = page; this.size = size; this.returned = returned; this.hasMore = hasMore; this.nextPage = nextPage; this.prevPage = prevPage; this.total = total;
    }
    public static PaginationInfo of(Integer page, Integer size, Integer returned, Boolean hasMore, Integer nextPage, Integer prevPage, Integer total) {
        return new PaginationInfo(page,size,returned,hasMore,nextPage,prevPage,total);
    }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public Integer getSize() { return size; }
    public void setSize(Integer size) { this.size = size; }
    public Integer getReturned() { return returned; }
    public void setReturned(Integer returned) { this.returned = returned; }
    public Boolean getHasMore() { return hasMore; }
    public void setHasMore(Boolean hasMore) { this.hasMore = hasMore; }
    public Integer getNextPage() { return nextPage; }
    public void setNextPage(Integer nextPage) { this.nextPage = nextPage; }
    public Integer getPrevPage() { return prevPage; }
    public void setPrevPage(Integer prevPage) { this.prevPage = prevPage; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
}
