package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.market.vo.AutoParticipantActivityScope;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoParticipantTransactionContractTest {

    @Test
    void participantOverviewAggregateEntryPoint_isReadOnlyTransaction() throws NoSuchMethodException {
        Transactional participantOverview = AutoParticipantOverviewQueryService.class
                .getMethod(
                        "getAutoParticipantOverviews",
                        boolean.class,
                        List.class,
                        AutoParticipantActivityScope.class
                )
                .getAnnotation(Transactional.class);
        assertThat(participantOverview)
                .isNotNull()
                .extracting(Transactional::readOnly)
                .isEqualTo(true);
    }

    @Test
    void profileOverviewAggregateEntryPoint_isReadOnlyTransaction() throws NoSuchMethodException {
        Transactional profileOverview = AutoParticipantProfileOverviewQueryService.class
                .getMethod(
                        "getAutoParticipantProfileOverviews",
                        AutoParticipantActivityScope.class,
                        List.class
                )
                .getAnnotation(Transactional.class);

        assertThat(profileOverview)
                .isNotNull()
                .extracting(Transactional::readOnly)
                .isEqualTo(true);
    }
}
