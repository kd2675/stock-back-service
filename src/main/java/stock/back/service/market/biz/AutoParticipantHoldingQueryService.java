package stock.back.service.market.biz;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.market.vo.AutoParticipantHoldingGroupResponse;
import stock.back.service.market.vo.AutoParticipantHoldingResponse;

import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AutoParticipantHoldingQueryService {

    private final JdbcClient jdbcClient;

    public AutoParticipantHoldingQueryService(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = JdbcClient.create(namedParameterJdbcTemplate);
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantHoldingGroupResponse> getAutoParticipantHoldings(List<String> userKeys) {
        List<String> normalizedUserKeys = AutoParticipantQuerySupport.normalizeUserKeys(userKeys);
        if (normalizedUserKeys.isEmpty()) {
            return List.of();
        }
        List<AutoParticipantHoldingGroupResponse> groups = jdbcClient.sql("""
                select p.user_key,
                       a.id as account_id,
                       h.symbol,
                       h.quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       case
                           when h.quantity - coalesce(h.reserved_quantity, 0) > 0 then h.quantity - coalesce(h.reserved_quantity, 0)
                           else 0
                       end as available_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(sp.current_price, h.average_price, 0) as current_price,
                       coalesce(sp.current_price, h.average_price, 0) * h.quantity as market_value,
                       (coalesce(sp.current_price, h.average_price, 0) - coalesce(h.average_price, 0)) * h.quantity as unrealized_profit
                  from stock_auto_participant p
                  left join stock_account a on a.user_key = p.user_key
                  left join stock_holding h
                    on h.account_id = a.id
                   and (h.quantity > 0 or coalesce(h.reserved_quantity, 0) > 0)
                  left join stock_price sp on sp.symbol = h.symbol
                 where p.withdrawn_at is null
                   and p.user_key in (:userKeys)
                 order by p.user_key asc, h.symbol asc
                """)
                .param("userKeys", normalizedUserKeys)
                .query(AutoParticipantOverviewResponseMapper::toHoldingGroups);
        Map<String, AutoParticipantHoldingGroupResponse> groupByUserKey = groups.stream()
                .collect(Collectors.toMap(
                        AutoParticipantHoldingGroupResponse::userKey,
                        Function.identity(),
                        (first, ignored) -> first
                ));
        return normalizedUserKeys.stream()
                .map(groupByUserKey::get)
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, List<AutoParticipantHoldingResponse>> findHoldingsByAccountIds(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                select h.account_id,
                       h.symbol,
                       h.quantity,
                       coalesce(h.reserved_quantity, 0) as reserved_quantity,
                       case
                           when h.quantity - coalesce(h.reserved_quantity, 0) > 0 then h.quantity - coalesce(h.reserved_quantity, 0)
                           else 0
                       end as available_quantity,
                       coalesce(h.average_price, 0) as average_price,
                       coalesce(sp.current_price, h.average_price, 0) as current_price,
                       coalesce(sp.current_price, h.average_price, 0) * h.quantity as market_value,
                       (coalesce(sp.current_price, h.average_price, 0) - coalesce(h.average_price, 0)) * h.quantity as unrealized_profit
                from stock_holding h
                left join stock_price sp on sp.symbol = h.symbol
                where h.account_id in (:accountIds)
                  and (h.quantity > 0 or coalesce(h.reserved_quantity, 0) > 0)
                order by h.account_id asc, h.symbol asc
                """;
        return jdbcClient.sql(sql)
                .param("accountIds", accountIds)
                .query((rs, rowNum) -> new AutoParticipantHoldingLedger(
                        rs.getLong("account_id"),
                        AutoParticipantOverviewResponseMapper.toHolding(rs)
                ))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        AutoParticipantHoldingLedger::accountId,
                        Collectors.mapping(AutoParticipantHoldingLedger::holding, Collectors.toList())
                ));
    }

    private record AutoParticipantHoldingLedger(
            Long accountId,
            AutoParticipantHoldingResponse holding
    ) {
    }
}
