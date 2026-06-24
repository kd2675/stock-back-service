package stock.back.service.market.vo;

import stock.back.service.database.entity.StockInstrumentReportEventType;

import java.time.LocalDateTime;

public record InstrumentReportResponse(
        Long id,
        String symbol,
        StockInstrumentReportEventType eventType,
        String title,
        String summary,
        Integer score,
        String riseReason,
        String fallReason,
        String deleteReason,
        String createdBy,
        LocalDateTime createdAt
) {
}
