package stock.back.service.market.biz;

record AdminTotalAssetHistoryPageRequest(
        int page,
        int offset
) {
    private static final int PAGE_SIZE = 7;

    static AdminTotalAssetHistoryPageRequest of(int page) {
        int normalizedPage = Math.max(0, page);
        int offset = normalizedPage > Integer.MAX_VALUE / PAGE_SIZE
                ? Integer.MAX_VALUE
                : normalizedPage * PAGE_SIZE;
        return new AdminTotalAssetHistoryPageRequest(normalizedPage, offset);
    }

    int querySize() {
        return PAGE_SIZE + 1;
    }

    int totalPages(long totalElements) {
        return totalElements == 0L ? 0 : (int) Math.ceil((double) totalElements / PAGE_SIZE);
    }

    boolean hasPrevious(int totalPages) {
        return page > 0 && totalPages > 0;
    }

    boolean hasNext(int totalPages) {
        return page + 1 < totalPages;
    }
}
