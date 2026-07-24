package stock.back.service.market.biz;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.back.service.market.vo.AutoParticipantLifecycleScope;
import stock.back.service.market.vo.AutoParticipantSymbolConfigResponse;

@Service
public class AutoParticipantSymbolConfigQueryService {

    private final JdbcClient jdbcClient;

    public AutoParticipantSymbolConfigQueryService(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = JdbcClient.create(namedParameterJdbcTemplate);
    }

    @Transactional(readOnly = true)
    public List<AutoParticipantSymbolConfigResponse> getAutoParticipantSymbolConfigs(
            AutoParticipantLifecycleScope lifecycleScope,
            List<String> userKeys
    ) {
        AutoParticipantLifecycleScope effectiveScope =
                AutoParticipantLifecycleScope.effective(lifecycleScope);
        List<String> normalizedUserKeys = AutoParticipantQuerySupport.normalizeUserKeys(userKeys);
        String lifecyclePredicate = effectiveScope == AutoParticipantLifecycleScope.WITHDRAWN
                ? "is not null"
                : "is null";
        String userKeyPredicate = normalizedUserKeys.isEmpty()
                ? ""
                : " and c.user_key in (:userKeys)";
        String participantOrder = effectiveScope == AutoParticipantLifecycleScope.WITHDRAWN
                ? "p.withdrawn_at desc, c.user_key asc, c.symbol asc"
                : "c.user_key asc, c.symbol asc";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                select c.user_key,
                       c.symbol,
                       c.enabled,
                       c.intensity,
                       c.updated_at
                  from stock_auto_participant_symbol_config c
                  join stock_auto_participant p on p.user_key = c.user_key
                 where p.withdrawn_at %s
                 %s
                 order by %s
                """.formatted(lifecyclePredicate, userKeyPredicate, participantOrder));
        if (!normalizedUserKeys.isEmpty()) {
            statement = statement.param("userKeys", normalizedUserKeys);
        }
        return statement
                .query((rs, rowNum) -> new AutoParticipantSymbolConfigResponse(
                        rs.getString("user_key"),
                        rs.getString("symbol"),
                        rs.getBoolean("enabled"),
                        rs.getInt("intensity"),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .list();
    }
}
