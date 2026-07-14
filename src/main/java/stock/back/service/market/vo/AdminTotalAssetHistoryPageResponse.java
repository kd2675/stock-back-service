package stock.back.service.market.vo;

import java.util.List;

public record AdminTotalAssetHistoryPageResponse(
        List<AdminTotalAssetHistoryPointResponse> content,
        AdminTotalAssetPeriodSummaryResponse summary,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext
) {
}
