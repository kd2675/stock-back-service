package stock.back.service.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "stock_instrument_report_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockInstrumentReportEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private StockInstrumentReportEventType eventType;

    @Column(name = "title", length = 120)
    private String title;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "score")
    private Integer score;

    @Column(name = "rise_reason", length = 500)
    private String riseReason;

    @Column(name = "fall_reason", length = 500)
    private String fallReason;

    @Column(name = "delete_reason", length = 255)
    private String deleteReason;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static StockInstrumentReportEvent publish(
            String symbol,
            String title,
            String summary,
            int score,
            String riseReason,
            String fallReason,
            String createdBy
    ) {
        return report(symbol, StockInstrumentReportEventType.PUBLISH, title, summary, score, riseReason, fallReason, createdBy);
    }

    public static StockInstrumentReportEvent update(
            String symbol,
            String title,
            String summary,
            int score,
            String riseReason,
            String fallReason,
            String createdBy
    ) {
        return report(symbol, StockInstrumentReportEventType.UPDATE, title, summary, score, riseReason, fallReason, createdBy);
    }

    public static StockInstrumentReportEvent delete(String symbol, String deleteReason, String createdBy) {
        StockInstrumentReportEvent event = new StockInstrumentReportEvent();
        event.symbol = symbol;
        event.eventType = StockInstrumentReportEventType.DELETE;
        event.deleteReason = deleteReason;
        event.createdBy = createdBy;
        event.createdAt = LocalDateTime.now();
        return event;
    }

    private static StockInstrumentReportEvent report(
            String symbol,
            StockInstrumentReportEventType eventType,
            String title,
            String summary,
            int score,
            String riseReason,
            String fallReason,
            String createdBy
    ) {
        StockInstrumentReportEvent event = new StockInstrumentReportEvent();
        event.symbol = symbol;
        event.eventType = eventType;
        event.title = title;
        event.summary = summary;
        event.score = score;
        event.riseReason = riseReason;
        event.fallReason = fallReason;
        event.createdBy = createdBy;
        event.createdAt = LocalDateTime.now();
        return event;
    }
}
