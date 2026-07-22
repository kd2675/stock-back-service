package stock.back.service.market.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AutoParticipantPerformanceSummaryResponse(
        AutoParticipantPerformanceBasis basis,
        LocalDate businessDate,
        LocalDateTime calculatedAt,
        String calculationMethod,
        Long closeCycleId,
        AutoParticipantPerformanceMetricResponse total
) {
}
