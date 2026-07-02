package stock.back.service.market.biz;

record AdminCashFlowPageRequest(
        int page,
        int size,
        int offset
) {
    static AdminCashFlowPageRequest of(int page, int size) {
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.clamp(size, 1, 100);
        int offset = normalizedPage > Integer.MAX_VALUE / normalizedSize
                ? Integer.MAX_VALUE
                : normalizedPage * normalizedSize;
        return new AdminCashFlowPageRequest(normalizedPage, normalizedSize, offset);
    }

    int totalPages(long totalElements) {
        return totalElements == 0L ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    boolean hasPrevious(int totalPages) {
        return page > 0 && totalPages > 0;
    }

    boolean hasNext(int totalPages) {
        return page + 1 < totalPages;
    }
}
