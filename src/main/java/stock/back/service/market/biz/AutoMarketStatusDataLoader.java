package stock.back.service.market.biz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockAutoParticipantSymbolConfig;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.repository.StockAutoParticipantSymbolConfigRepository;
import stock.back.service.market.vo.AutoParticipantResponse;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;
import stock.back.service.market.vo.ListingAutoAccountResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AutoMarketStatusDataLoader {

    private final JdbcClient jdbcClient;
    private final StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository;
    private final ListingAutoAccountLedgerQueryService listingAutoAccountLedgerQueryService;

    public AutoMarketStatusDataLoader(
            JdbcTemplate jdbcTemplate,
            StockAutoParticipantSymbolConfigRepository stockAutoParticipantSymbolConfigRepository,
            ListingAutoAccountLedgerQueryService listingAutoAccountLedgerQueryService
    ) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.stockAutoParticipantSymbolConfigRepository = stockAutoParticipantSymbolConfigRepository;
        this.listingAutoAccountLedgerQueryService = listingAutoAccountLedgerQueryService;
    }

    List<AutoParticipantResponse> loadAutoParticipantStatusResponses() {
        String sql = """
                select p.user_key,
                       p.display_name,
                       p.enabled,
                       p.profile_type,
                       p.recurring_cash_amount,
                       p.recurring_cash_interval_value,
                       p.recurring_cash_interval_unit,
                       p.created_at,
                       p.updated_at,
                       p.withdrawn_at,
                       a.id as account_id,
                       a.status as account_status,
                       a.cash_balance
                  from stock_auto_participant p
                  left join stock_account a on a.user_key = p.user_key
	                 where p.withdrawn_at is null
	                 order by p.user_key asc
	                """;
        return jdbcClient.sql(sql)
                .query((rs, rowNum) -> AutoMarketStatusResponseMapper.toParticipant(rs))
                .list();
    }

    List<AutoParticipantSymbolConfigTarget> resolveAutoParticipantSymbolConfigTargets(
            List<AutoParticipantResponse> participants,
            String participantSymbolConfigUserKey
    ) {
        if (participantSymbolConfigUserKey == null) {
            return participants.stream()
                    .map(participant -> new AutoParticipantSymbolConfigTarget(participant.userKey(), participant.updatedAt()))
                    .toList();
        }
        String sql = """
                select p.user_key,
                       p.updated_at
                  from stock_auto_participant p
	                 where p.withdrawn_at is null
	                   and p.user_key = ?
	                """;
        return jdbcClient.sql(sql)
                .param(participantSymbolConfigUserKey)
                .query((rs, rowNum) -> new AutoParticipantSymbolConfigTarget(
                        rs.getString("user_key"),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .list();
    }

    List<AutoParticipantSymbolConfigResponse> loadEffectiveAutoParticipantSymbolConfigs(
            List<AutoParticipantSymbolConfigTarget> participantTargets,
            List<StockAutoMarketConfig> configEntities
    ) {
        if (participantTargets.isEmpty() || configEntities.isEmpty()) {
            return List.of();
        }
        List<String> userKeys = participantTargets.stream()
                .map(AutoParticipantSymbolConfigTarget::userKey)
                .toList();
        Map<String, StockAutoParticipantSymbolConfig> savedParticipantSymbolConfigs = stockAutoParticipantSymbolConfigRepository.findByUserKeyInOrderByUserKeyAscSymbolAsc(userKeys)
                .stream()
                .collect(Collectors.toMap(
                        config -> autoParticipantSymbolConfigKey(config.getUserKey(), config.getSymbol()),
                        Function.identity(),
                        (left, right) -> left
                ));
        return participantTargets.stream()
                .flatMap(participantTarget -> configEntities.stream()
                        .map(config -> AutoMarketStatusResponseMapper.toEffectiveParticipantSymbolConfig(
                                participantTarget.userKey(),
                                participantTarget.updatedAt(),
                                config,
                                savedParticipantSymbolConfigs.get(autoParticipantSymbolConfigKey(participantTarget.userKey(), config.getSymbol()))
                        )))
                .toList();
    }

    List<ListingAutoAccountResponse> toListingAutoAccountResponses(List<StockListingAutoAccountConfig> configs) {
        if (configs.isEmpty()) {
            return List.of();
        }
        Map<String, ListingAutoAccountLedger> ledgersBySymbol = listingAutoAccountLedgerQueryService.findLedgersBySymbol();
        return configs.stream()
                .map(config -> AutoMarketStatusResponseMapper.toListingAutoAccount(
                        config,
                        ledgersBySymbol.getOrDefault(config.getSymbol(), ListingAutoAccountLedger.empty())
                ))
                .toList();
    }

    private String autoParticipantSymbolConfigKey(String userKey, String symbol) {
        return userKey + "\n" + symbol;
    }
}
