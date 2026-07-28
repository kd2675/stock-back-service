package stock.back.service.market.biz;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAutoParticipant;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.repository.StockAutoParticipantRepository;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.market.vo.AutoParticipantSymbolConfigRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoParticipantSymbolConfigServiceTest {

    @Mock
    private StockAutoParticipantRepository stockAutoParticipantRepository;

    @Mock
    private StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository;

    @Mock
    private StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;

    private AutoParticipantSymbolConfigService service;

    @BeforeEach
    void setUp() {
        service = new AutoParticipantSymbolConfigService(
                stockAutoParticipantRepository,
                stockAutoParticipantSymbolConfigRepository,
                stockOrderBookInstrumentRepository
        );
    }

    @Test
    void updateAutoParticipantSymbolConfig_validRequest_savesParticipantSymbolStrategy() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoParticipantSymbolConfigRepository.findById(any())).thenReturn(Optional.empty());
        when(stockAutoParticipantSymbolConfigRepository.save(any(StockAutoParticipantSymbolConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateAutoParticipantSymbolConfig(
                "stock-auto-001",
                "zq001",
                new AutoParticipantSymbolConfigRequest(true, 10)
        );

        ArgumentCaptor<StockAutoParticipantSymbolConfig> configCaptor = ArgumentCaptor.forClass(StockAutoParticipantSymbolConfig.class);
        verify(stockAutoParticipantSymbolConfigRepository).save(configCaptor.capture());
        assertThat(response.userKey()).isEqualTo("stock-auto-001");
        assertThat(response.symbol()).isEqualTo("ZQ001");
        assertThat(response.intensity()).isEqualTo(10);
        assertThat(configCaptor.getValue().getIntensity()).isEqualTo(10);
    }

    @Test
    void updateAutoParticipantSymbolConfig_nullIntensity_usesNeutralDefault() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        when(stockAutoParticipantSymbolConfigRepository.findById(any())).thenReturn(Optional.empty());
        when(stockAutoParticipantSymbolConfigRepository.save(any(StockAutoParticipantSymbolConfig.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateAutoParticipantSymbolConfig(
                "stock-auto-001",
                "zq001",
                new AutoParticipantSymbolConfigRequest(true, null)
        );

        assertThat(response.intensity()).isEqualTo(5);
    }

    @Test
    void updateAutoParticipantSymbolConfig_invalidIntensity_throwsBadRequest() {
        StockAutoParticipant participant = StockAutoParticipant.create(
                "stock-auto-001",
                "자동 참여자 1",
                true
        );
        when(stockAutoParticipantRepository.findById("stock-auto-001")).thenReturn(Optional.of(participant));
        when(stockOrderBookInstrumentRepository.existsById("ZQ001")).thenReturn(true);
        assertThatThrownBy(() -> service.updateAutoParticipantSymbolConfig(
                "stock-auto-001",
                "zq001",
                new AutoParticipantSymbolConfigRequest(true, 11)
        ))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Intensity must be between 1 and 10");

        verify(stockAutoParticipantSymbolConfigRepository, never()).save(any());
    }
}
