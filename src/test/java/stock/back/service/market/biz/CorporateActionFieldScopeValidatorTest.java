package stock.back.service.market.biz;

import org.junit.jupiter.api.Test;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.market.vo.CorporateActionRequest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorporateActionFieldScopeValidatorTest {

    @Test
    void validate_cashDividendWithListingDate_throwsBadRequest() {
        CorporateActionRequest request = new CorporateActionRequest(
                StockCorporateActionType.CASH_DIVIDEND,
                null,
                null,
                null,
                null,
                LocalDate.now(),
                LocalDate.now(),
                LocalDate.now(),
                new BigDecimal("1000.00"),
                "현금배당"
        );

        assertThatThrownBy(() -> CorporateActionFieldScopeValidator.validate(request))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Cash dividend does not use listingDate");
    }

    @Test
    void validate_initialIssue_throwsBadRequest() {
        CorporateActionRequest request = new CorporateActionRequest(
                StockCorporateActionType.INITIAL_ISSUE,
                100000L,
                new BigDecimal("70000.00"),
                null,
                null,
                null,
                null,
                null,
                null,
                "초기 발행"
        );

        assertThatThrownBy(() -> CorporateActionFieldScopeValidator.validate(request))
                .isInstanceOf(StockException.class)
                .hasMessageContaining("Initial issue is only allowed when creating an instrument");
    }
}
