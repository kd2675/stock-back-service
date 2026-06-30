package stock.back.service.market.biz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockInstrumentReportEvent;
import stock.back.service.database.entity.StockInstrumentReportEventType;
import stock.back.service.database.repository.StockInstrumentReportEventRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.InstrumentReportRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentReportServiceTest {

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    @Mock
    private StockInstrumentReportEventRepository stockInstrumentReportEventRepository;

    private InstrumentReportService service;

    @BeforeEach
    void setUp() {
        service = new InstrumentReportService(
                stockOrderBookInstrumentRepository,
                stockInstrumentReportEventRepository
        );
    }

    @Test
    void publishInstrumentReport_validRequest_recordsNormalizedPublishEvent() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockInstrumentReportEventRepository.save(any(StockInstrumentReportEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.publishInstrumentReport(
                " zq001 ",
                new InstrumentReportRequest(
                        " 실적 개선 보고서 ",
                        " 수요 회복과 비용 절감이 동시에 반영됐습니다. ",
                        8,
                        " 수요 회복으로 매수세가 강합니다. ",
                        " "
                ),
                " admin-user "
        );

        ArgumentCaptor<StockInstrumentReportEvent> eventCaptor = ArgumentCaptor.forClass(StockInstrumentReportEvent.class);
        verify(stockInstrumentReportEventRepository).save(eventCaptor.capture());
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.eventType()).isEqualTo(StockInstrumentReportEventType.PUBLISH);
        assertThat(response.title()).isEqualTo("실적 개선 보고서");
        assertThat(response.fallReason()).isNull();
        assertThat(eventCaptor.getValue().getCreatedBy()).isEqualTo("admin-user");
    }

    @Test
    void updateInstrumentReport_withoutActiveLatestReport_throwsNotFoundAndDoesNotSave() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc("ZQ001"))
                .thenReturn(Optional.of(StockInstrumentReportEvent.delete("ZQ001", "deleted", "admin-user")));

        assertThatThrownBy(() -> service.updateInstrumentReport(
                "zq001",
                new InstrumentReportRequest("수정 보고서", "요약", 7, "상승 이유", "하락 이유"),
                "admin-user"
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Instrument report not found");

        verify(stockInstrumentReportEventRepository, never()).save(any());
    }

    @Test
    void getLatestInstrumentReport_latestDeleteEvent_returnsNull() {
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockInstrumentReportEventRepository.findTopBySymbolOrderByCreatedAtDescIdDesc("ZQ001"))
                .thenReturn(Optional.of(StockInstrumentReportEvent.delete("ZQ001", "deleted", "admin-user")));

        var response = service.getLatestInstrumentReport("zq001");

        assertThat(response).isNull();
    }
}
