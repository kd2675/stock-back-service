package stock.back.service.market.vo;

import java.time.LocalDate;
import java.util.List;

public record AdminTotalAssetHistoryPageResponse(
        AdminParticipantScope participantScope,
        LocalDate roleFrozenFrom,
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
