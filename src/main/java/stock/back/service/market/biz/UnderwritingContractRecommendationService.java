package stock.back.service.market.biz;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.UnderwritingContractRecommendationResponse;

@Service
public class UnderwritingContractRecommendationService {

    private static final BigDecimal RECOMMENDED_SUPPLY_RATE = new BigDecimal("0.100000");
    private static final int RECOMMENDED_SUPPLY_DURATION_DAYS = 20;

    private final JdbcClient jdbcClient;

    public UnderwritingContractRecommendationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public UnderwritingContractRecommendationResponse getRecommendation() {
        List<UnderwritingContractRecommendationResponse.Symbol> symbols = jdbcClient.sql(
                        """
                        select instrument.symbol,
                               instrument.name as instrument_name,
                               instrument.issued_shares,
                               instrument.tradable_shares,
                               instrument.initial_price as issue_price,
                               allocation.corporate_action_id,
                               allocation.destination_account_id
                                   as float_custody_account_id,
                               greatest(
                                   coalesce(holding.quantity, 0)
                                   - coalesce(holding.reserved_quantity, 0),
                                   0
                               ) as float_custody_available_quantity,
                               exists(
                                   select 1
                                     from stock_underwriting_contract contract
                                    where contract.symbol = instrument.symbol
                               ) as existing_contract,
                               market.enabled as market_enabled,
                               market.market_status,
                               allocation.allocation_reason
                          from stock_order_book_instrument instrument
                          join stock_order_book_market_config market
                            on market.symbol = instrument.symbol
                          left join stock_security_allocation_ledger allocation
                            on allocation.symbol = instrument.symbol
                           and allocation.event_type = 'INITIAL_ISSUE'
                           and allocation.source_account_id is null
                           and allocation.tradability_status = 'TRADABLE'
                           and allocation.allocation_reason in (
                               'INITIAL_FLOAT_CUSTODY',
                               'INITIAL_FLOAT_UNDERWRITER'
                           )
                          left join stock_holding holding
                            on holding.account_id = allocation.destination_account_id
                           and holding.symbol = instrument.symbol
                         where instrument.enabled = true
                         order by instrument.symbol
                        """
                )
                .query((rs, rowNum) -> {
                    long issuedShares = rs.getLong("issued_shares");
                    long tradableShares = rs.getLong("tradable_shares");
                    boolean existingContract = rs.getBoolean("existing_contract");
                    Long actionId = nullableLong(rs.getObject("corporate_action_id"));
                    Long custodyAccountId = nullableLong(
                            rs.getObject("float_custody_account_id")
                    );
                    long availableQuantity = rs.getLong(
                            "float_custody_available_quantity"
                    );
                    boolean pendingMarket = !rs.getBoolean("market_enabled")
                            && "CLOSED".equals(rs.getString("market_status"));
                    String allocationReason = rs.getString("allocation_reason");
                    String eligibilityReason;
                    if (existingContract) {
                        eligibilityReason = "ALREADY_CREATED";
                    } else if (!pendingMarket) {
                        eligibilityReason = "PENDING_LISTING_REQUIRED";
                    } else if (!"INITIAL_FLOAT_CUSTODY".equals(allocationReason)
                            || actionId == null
                            || custodyAccountId == null) {
                        eligibilityReason = "FLOAT_CUSTODY_REQUIRED";
                    } else if (availableQuantity < tradableShares) {
                        eligibilityReason = "FLOAT_CUSTODY_SHORTAGE";
                    } else {
                        eligibilityReason = "READY";
                    }
                    return new UnderwritingContractRecommendationResponse.Symbol(
                            rs.getString("symbol"),
                            rs.getString("instrument_name"),
                            issuedShares,
                            tradableShares,
                            Math.max(0L, issuedShares - tradableShares),
                            rs.getBigDecimal("issue_price"),
                            actionId,
                            custodyAccountId,
                            availableQuantity,
                            existingContract,
                            "READY".equals(eligibilityReason),
                            eligibilityReason
                    );
                })
                .list();
        long organizationCount = jdbcClient.sql(
                        """
                        select count(*)
                          from stock_market_participant
                         where participant_type = 'ISSUE_UNDERWRITER'
                           and status = 'ACTIVE'
                        """
                )
                .query(Long.class)
                .single();
        long contractCount = symbols.stream()
                .filter(UnderwritingContractRecommendationResponse.Symbol::existingContract)
                .count();
        long eligibleCount = symbols.stream()
                .filter(UnderwritingContractRecommendationResponse.Symbol::creationEligible)
                .count();
        return new UnderwritingContractRecommendationResponse(
                symbols.isEmpty() ? 0 : 1,
                organizationCount,
                1,
                contractCount,
                eligibleCount,
                RECOMMENDED_SUPPLY_RATE,
                RECOMMENDED_SUPPLY_DURATION_DAYS,
                symbols
        );
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
