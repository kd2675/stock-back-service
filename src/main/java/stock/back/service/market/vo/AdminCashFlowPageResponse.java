package stock.back.service.market.vo;

import java.util.List;

public record AdminCashFlowPageResponse(
        List<AdminRecentCashFlowResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext
) {
}
