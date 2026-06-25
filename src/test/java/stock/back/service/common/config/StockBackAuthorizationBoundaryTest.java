package stock.back.service.common.config;

import auth.common.core.context.RequirePrincipalRoleFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class StockBackAuthorizationBoundaryTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterRegistrationBean<RequirePrincipalRoleFilter> requirePrincipalRoleFilter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requirePrincipalRoleFilter.getFilter())
                .build();
        cleanAdminMarketTestData();
        ensureDefaultVirtualMarketData();
    }

    private void cleanAdminMarketTestData() {
        jdbcTemplate.update("delete from stock_corporate_action where symbol like 'ZQAUTH%'");
        jdbcTemplate.update("delete from stock_price_tick where symbol like 'ZQAUTH%'");
        jdbcTemplate.update("delete from stock_holding where symbol like 'ZQAUTH%'");
        jdbcTemplate.update("delete from stock_price where symbol like 'ZQAUTH%'");
        jdbcTemplate.update("delete from stock_auto_participant_symbol_config where symbol like 'ZQAUTH%' or user_key like 'stock-auto-auth%'");
        jdbcTemplate.update("delete from stock_auto_market_config where symbol like 'ZQAUTH%'");
        jdbcTemplate.update("delete from stock_auto_participant where user_key like 'stock-auto-auth%'");
        jdbcTemplate.update("delete from stock_auto_participant_profile_config where profile_type in ('NOISE_TRADER', 'MOMENTUM_FOLLOWER')");
        jdbcTemplate.update("""
                delete from stock_account_cash_flow
                 where account_id in (
                       select id
                         from stock_account
                        where user_key like 'stock-auto-auth%'
                           or user_key like 'stock-user-auth%'
                 )
                """);
        jdbcTemplate.update("delete from stock_account where user_key like 'stock-auto-auth%'");
        jdbcTemplate.update("delete from stock_account where user_key like 'stock-user-auth%'");
        jdbcTemplate.update("delete from stock_order_book_market_config where symbol like 'ZQAUTH%'");
        jdbcTemplate.update("delete from stock_order_book_instrument where symbol like 'ZQAUTH%'");
    }

    private void ensureDefaultVirtualMarketData() {
        Long instrumentCount = jdbcTemplate.queryForObject(
                "select count(*) from stock_instrument where symbol = ?",
                Long.class,
                "005930"
        );
        if (instrumentCount == null || instrumentCount == 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_instrument(symbol, name, market, enabled, created_at)
                    values (?, ?, ?, true, ?)
                    """,
                    "005930",
                    "삼성전자",
                    "KOSPI",
                    LocalDateTime.now()
            );
        }

        Long priceCount = jdbcTemplate.queryForObject(
                "select count(*) from stock_price where symbol = ?",
                Long.class,
                "005930"
        );
        if (priceCount == null || priceCount == 0) {
            jdbcTemplate.update(
                    """
                    insert into stock_price(symbol, current_price, previous_close, price_time, provider)
                    values (?, ?, ?, ?, 'test-seed')
                    """,
                    "005930",
                    new BigDecimal("72400.00"),
                    new BigDecimal("72400.00"),
                    LocalDateTime.now()
            );
        }
        jdbcTemplate.update(
                """
                merge into stock_virtual_market_config(symbol, enabled, market_status, updated_at)
                key(symbol)
                values ('005930', true, 'OPEN', ?)
                """,
                LocalDateTime.now()
        );
    }

    private void seedAutoParticipant(String userKey) {
        jdbcTemplate.update(
                """
                insert into stock_auto_participant(user_key, display_name, enabled, profile_type, created_at, updated_at)
                values (?, ?, true, 'NOISE_TRADER', ?, ?)
                """,
                userKey,
                userKey + " 참여자",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void seedStockAccount(String userKey) {
        jdbcTemplate.update(
                """
                merge into stock_account(user_key, cash_balance, created_at, updated_at)
                key(user_key)
                values (?, 10000000.00, ?, ?)
                """,
                userKey,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private Long stockAccountCount(String userKey) {
        return jdbcTemplate.queryForObject(
                "select count(*) from stock_account where user_key = ?",
                Long.class,
                userKey
        );
    }

    private void seedOrderBookInstrument(String symbol) {
        jdbcTemplate.update(
                """
                insert into stock_order_book_instrument(
                  symbol, name, market, initial_price, issued_shares, tradable_shares,
                  tick_size, price_limit_rate, enabled, created_at, updated_at
                )
                values (?, ?, 'ORDERBOOK', 70000.00, 100000, 100000, 1.00, 30.00, true, ?, ?)
                """,
                symbol,
                symbol + " 종목",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        jdbcTemplate.update(
                "insert into stock_price(symbol, current_price, previous_close, price_time, provider) values (?, 70000.00, 70000.00, ?, 'test')",
                symbol,
                LocalDateTime.now()
        );
    }

    private String additionalIssueBody() {
        return """
                {
                  "actionType": "ADDITIONAL_ISSUE",
                  "shareQuantity": 1000,
                  "issuePrice": 60000,
                  "listingDate": "%s"
                }
                """.formatted(LocalDate.now().plusDays(1));
    }

    private String autoParticipantProfileConfigBody() {
        return """
                {
                  "newsWeight": 0.2,
                  "momentumWeight": 0.3,
                  "contrarianWeight": 0.1,
                  "lossAversionWeight": 0.4,
                  "herdingWeight": 0.2,
                  "marketMakingWeight": 0.1,
                  "overconfidenceWeight": 0.3,
                  "noiseWeight": 0.2,
                  "panicSellWeight": 0.1,
                  "dipBuyWeight": 0.2,
                  "orderMultiplier": 1.2,
                  "aggressionMultiplier": 1.1,
                  "orderTtlMultiplier": 0.9,
                  "quantityMultiplier": 1.3,
                  "holdingPatienceWeight": 0.2,
                  "deepLossHoldWeight": 0.1,
                  "profitTakingWeight": 0.4,
                  "recurringDepositAmount": 500000,
                  "recurringDepositIntervalValue": 30,
                  "recurringDepositIntervalUnit": "DAY"
                }
                """;
    }

    @Test
    void marketPrices_withoutPrincipalHeaders_isPublic() throws Exception {
        mockMvc.perform(get("/api/stock/v1/markets/prices"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")));
    }

    @Test
    void createOrderBookInstrument_withoutPrincipalHeaders_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/stock/v1/markets/order-book-instruments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol": "ZQAUTH01",
                                  "name": "권한 테스트",
                                  "market": "ORDERBOOK",
                                  "initialPrice": 70000,
                                  "issuedShares": 100000
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("Login required")));
    }

    @Test
    void createOrderBookInstrument_userPrincipalHeaders_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/stock/v1/markets/order-book-instruments")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol": "ZQAUTH01",
                                  "name": "권한 테스트",
                                  "market": "ORDERBOOK",
                                  "initialPrice": 70000,
                                  "issuedShares": 100000
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Required role: ADMIN")));
    }

    @Test
    void createOrderBookInstrument_adminPrincipalHeaders_isAllowed() throws Exception {
        mockMvc.perform(post("/api/stock/v1/markets/order-book-instruments")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol": "ZQAUTH01",
                                  "name": "권한 테스트",
                                  "market": "ORDERBOOK",
                                  "initialPrice": 70000,
                                  "issuedShares": 100000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"symbol\":\"ZQAUTH01\"")));

        Long listedInstrumentCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_order_book_instrument
                 where symbol = 'ZQAUTH01'
                   and initial_price = 70000.00
                   and issued_shares = 100000
                   and tradable_shares = 100000
                   and enabled = true
                """,
                Long.class
        );
        Long initialIssueCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_corporate_action
                 where symbol = 'ZQAUTH01'
                   and action_type = 'INITIAL_ISSUE'
                   and share_quantity = 100000
                   and issue_price = 70000.00
                   and status = 'LISTED'
                   and listed_at is not null
                   and applied_at is null
                   and paid_at is null
                """,
                Long.class
        );
        Long listingHoldingCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_holding h
                  join stock_account a on a.id = h.account_id
                 where h.symbol = 'ZQAUTH01'
                   and a.user_key = 'stock-listing-zqauth01'
                   and h.quantity = 100000
                   and h.reserved_quantity = 0
                   and h.average_price = 70000.00
                """,
                Long.class
        );
        Long listingConfigCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_listing_auto_account_config
                 where symbol = 'ZQAUTH01'
                   and user_key = 'stock-listing-zqauth01'
                   and enabled = true
                   and position_side = 'SELL_ONLY'
                """,
                Long.class
        );
        Long askLevelQuantity = jdbcTemplate.queryForObject(
                """
                select coalesce(sum(quantity - filled_quantity), 0)
                  from stock_order
                 where symbol = 'ZQAUTH01'
                   and side = 'SELL'
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                   and limit_price = 70000.00
                """,
                Long.class
        );
        assertThat(listedInstrumentCount).isEqualTo(1L);
        assertThat(initialIssueCount).isEqualTo(1L);
        assertThat(listingHoldingCount).isEqualTo(1L);
        assertThat(listingConfigCount).isEqualTo(1L);
        assertThat(askLevelQuantity).isZero();

        String orderBookContent = mockMvc.perform(get("/api/stock/v1/markets/order-books/ZQAUTH01"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode asks = objectMapper.readTree(orderBookContent).path("data").path("asks");
        assertThat(asks).isEmpty();
    }

    @Test
    void applyCorporateAction_userPrincipalHeaders_returnsForbidden() throws Exception {
        seedOrderBookInstrument("ZQAUTH02");

        mockMvc.perform(post("/api/stock/v1/markets/order-book-instruments/ZQAUTH02/corporate-actions")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(additionalIssueBody()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Required role: ADMIN")));
    }

    @Test
    void applyCorporateAction_adminPrincipalHeaders_isAllowed() throws Exception {
        seedOrderBookInstrument("ZQAUTH03");

        mockMvc.perform(post("/api/stock/v1/markets/order-book-instruments/ZQAUTH03/corporate-actions")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(additionalIssueBody()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"symbol\":\"ZQAUTH03\"")));
    }

    @Test
    void updateAutoMarketConfig_userPrincipalHeaders_returnsForbidden() throws Exception {
        seedOrderBookInstrument("ZQAUTH04");

        mockMvc.perform(patch("/api/stock/v1/markets/auto-market/configs/ZQAUTH04")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "intensity": 10,
                                  "maxOrderQuantity": 3,
                                  "orderTtlSeconds": 15
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Required role: ADMIN")));
    }

    @Test
    void updateAutoMarketConfig_adminPrincipalHeaders_isAllowed() throws Exception {
        seedOrderBookInstrument("ZQAUTH05");

        mockMvc.perform(patch("/api/stock/v1/markets/auto-market/configs/ZQAUTH05")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "intensity": 10,
                                  "maxOrderQuantity": 3,
                                  "orderTtlSeconds": 15
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"symbol\":\"ZQAUTH05\"")))
                .andExpect(content().string(containsString("\"intensity\":10")));
    }

    @Test
    void updateAutoParticipantProfileConfig_userPrincipalHeaders_returnsForbidden() throws Exception {
        mockMvc.perform(patch("/api/stock/v1/markets/auto-market/profile-configs/NOISE_TRADER")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(autoParticipantProfileConfigBody()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Required role: ADMIN")));
    }

    @Test
    void updateAutoParticipantProfileConfig_adminPrincipalHeaders_isAllowed() throws Exception {
        mockMvc.perform(patch("/api/stock/v1/markets/auto-market/profile-configs/MOMENTUM_FOLLOWER")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(autoParticipantProfileConfigBody()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"profileType\":\"MOMENTUM_FOLLOWER\"")))
                .andExpect(content().string(containsString("\"orderMultiplier\":1.2")))
                .andExpect(content().string(containsString("\"customized\":true")));
    }

    @Test
    void upsertAutoParticipant_adminPrincipalHeaders_isAllowed() throws Exception {
        mockMvc.perform(patch("/api/stock/v1/markets/auto-market/participants/stock-auto-auth")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
	                                {
	                                  "displayName": "권한 자동 참여자",
	                                  "enabled": true
	                                }
	                                """))
	                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"userKey\":\"stock-auto-auth\"")));
    }

    @Test
    void getAutoParticipantOverviews_userPrincipalHeaders_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/stock/v1/markets/auto-market/participants/overviews")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Required role: ADMIN")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET /api/stock/v1/markets/auto-market/cash-flow",
            "PATCH /api/stock/v1/markets/auto-market/cash-flow",
            "POST /api/stock/v1/markets/auto-market/cash-flow/run",
            "GET /api/stock/v1/markets/batch-jobs/runtime-controls",
            "PATCH /api/stock/v1/markets/batch-jobs/runtime-controls/auto-market"
    })
    void autoParticipantCashFlowAdminEndpoints_withoutPrincipalHeaders_returnUnauthorized(String requestLine) throws Exception {
        String[] parts = requestLine.split(" ", 2);
        String method = parts[0];
        String path = parts[1];

        switch (method) {
            case "GET" -> mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(containsString("Login required")));
            case "PATCH" -> mockMvc.perform(patch(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "runtimeEnabled": false
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(containsString("Login required")));
            case "POST" -> mockMvc.perform(post(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(containsString("Login required")));
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET /api/stock/v1/markets/auto-market/cash-flow",
            "PATCH /api/stock/v1/markets/auto-market/cash-flow",
            "POST /api/stock/v1/markets/auto-market/cash-flow/run",
            "GET /api/stock/v1/markets/batch-jobs/runtime-controls",
            "PATCH /api/stock/v1/markets/batch-jobs/runtime-controls/auto-market"
    })
    void autoParticipantCashFlowAdminEndpoints_userPrincipalHeaders_returnForbidden(String requestLine) throws Exception {
        String[] parts = requestLine.split(" ", 2);
        String method = parts[0];
        String path = parts[1];

        switch (method) {
            case "GET" -> mockMvc.perform(get(path)
                            .header("X-User-Key", "stock-user-key")
                            .header("X-User-Role", "ROLE_USER"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(containsString("Required role: ADMIN")));
            case "PATCH" -> mockMvc.perform(patch(path)
                            .header("X-User-Key", "stock-user-key")
                            .header("X-User-Role", "ROLE_USER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "runtimeEnabled": false
                                    }
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(containsString("Required role: ADMIN")));
            case "POST" -> mockMvc.perform(post(path)
                            .header("X-User-Key", "stock-user-key")
                            .header("X-User-Role", "ROLE_USER"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().string(containsString("Required role: ADMIN")));
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        }
    }

    @Test
    void updateAutoParticipantCashFlowStatus_missingRuntimeEnabled_returnsBadRequestBeforeBatchCall() throws Exception {
        mockMvc.perform(patch("/api/stock/v1/markets/auto-market/cash-flow")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "updatedBy": "ignored-client-value"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("runtimeEnabled is required")));
    }

    @Test
    void updateBatchJobRuntimeControl_missingRuntimeEnabled_returnsBadRequestBeforeBatchCall() throws Exception {
        mockMvc.perform(patch("/api/stock/v1/markets/batch-jobs/runtime-controls/auto-market")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "updatedBy": "ignored-client-value"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("runtimeEnabled is required")));
    }

    @Test
    void getAutoParticipantOverviews_adminPrincipalHeaders_isAllowed() throws Exception {
        seedAutoParticipant("stock-auto-auth-overview");
        seedStockAccount("stock-auto-auth-overview");
        Long accountId = jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                "stock-auto-auth-overview"
        );
        jdbcTemplate.update(
                """
                insert into stock_account_cash_flow(account_id, flow_type, amount, reason, created_by, created_at)
                values (?, 'DEPOSIT', 10000000.00, 'OPENING_GRANT', 'SYSTEM', ?)
                """,
                accountId,
                LocalDateTime.now()
        );

        mockMvc.perform(get("/api/stock/v1/markets/auto-market/participants/overviews")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")))
                .andExpect(content().string(containsString("\"userKey\":\"stock-auto-auth-overview\"")))
                .andExpect(content().string(containsString("\"availableCash\":10000000.00")))
                .andExpect(content().string(containsString("\"estimatedTotalAsset\":10000000.00")))
                .andExpect(content().string(containsString("\"netCashFlow\":10000000.00")))
                .andExpect(content().string(containsString("\"totalProfit\":0.00")))
                .andExpect(content().string(containsString("\"returnRate\":0")));
    }

    @Test
    void withdrawAutoParticipant_adminPrincipalHeaders_isAllowed() throws Exception {
        seedAutoParticipant("stock-auto-auth-withdraw");

        mockMvc.perform(delete("/api/stock/v1/markets/auto-market/participants/stock-auto-auth-withdraw")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"userKey\":\"stock-auto-auth-withdraw\"")))
                .andExpect(content().string(containsString("\"enabled\":false")))
                .andExpect(content().string(containsString("\"withdrawnAt\":")));
    }

    @Test
    void adjustAutoParticipantCash_adminPrincipalHeaders_isAllowed() throws Exception {
        seedAutoParticipant("stock-auto-auth-cash");
        seedStockAccount("stock-auto-auth-cash");

        mockMvc.perform(post("/api/stock/v1/markets/auto-market/participants/stock-auto-auth-cash/cash-adjustments")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "adjustmentType": "DEPOSIT",
                                  "amount": 1000000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"userKey\":\"stock-auto-auth-cash\"")))
                .andExpect(content().string(containsString("\"adjustmentType\":\"DEPOSIT\"")))
                .andExpect(content().string(containsString("\"cashBalance\":11000000")));

        BigDecimal cashBalance = jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where user_key = ?",
                BigDecimal.class,
                "stock-auto-auth-cash"
        );
        assertThat(cashBalance).isEqualByComparingTo(new BigDecimal("11000000.00"));
    }

    @Test
    void adjustAutoParticipantCash_userPrincipalHeaders_returnsForbidden() throws Exception {
        seedAutoParticipant("stock-auto-auth-cash-user");
        seedStockAccount("stock-auto-auth-cash-user");

        mockMvc.perform(post("/api/stock/v1/markets/auto-market/participants/stock-auto-auth-cash-user/cash-adjustments")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "adjustmentType": "DEPOSIT",
                                  "amount": 1000000
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Required role: ADMIN")));

        BigDecimal cashBalance = jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where user_key = ?",
                BigDecimal.class,
                "stock-auto-auth-cash-user"
        );
        assertThat(cashBalance).isEqualByComparingTo(new BigDecimal("10000000.00"));
    }

    @Test
    void adjustUserAccountCash_adminPrincipalHeaders_isAllowed() throws Exception {
        seedStockAccount("stock-user-auth-cash");

        mockMvc.perform(post("/api/stock/v1/accounts/admin/users/stock-user-auth-cash/cash-adjustments")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "adjustmentType": "DEPOSIT",
                                  "amount": 1000000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"userKey\":\"stock-user-auth-cash\"")))
                .andExpect(content().string(containsString("\"adjustmentType\":\"DEPOSIT\"")))
                .andExpect(content().string(containsString("\"cashBalance\":11000000")));

        BigDecimal cashBalance = jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where user_key = ?",
                BigDecimal.class,
                "stock-user-auth-cash"
        );
        assertThat(cashBalance).isEqualByComparingTo(new BigDecimal("11000000.00"));
    }

    @Test
    void adjustUserAccountCash_userPrincipalHeaders_returnsForbidden() throws Exception {
        seedStockAccount("stock-user-auth-cash-user");

        mockMvc.perform(post("/api/stock/v1/accounts/admin/users/stock-user-auth-cash-user/cash-adjustments")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "adjustmentType": "DEPOSIT",
                                  "amount": 1000000
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Required role: ADMIN")));

        BigDecimal cashBalance = jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where user_key = ?",
                BigDecimal.class,
                "stock-user-auth-cash-user"
        );
        assertThat(cashBalance).isEqualByComparingTo(new BigDecimal("10000000.00"));
    }

    @Test
    void updateAutoParticipantSymbolConfig_userPrincipalHeaders_returnsForbidden() throws Exception {
        seedOrderBookInstrument("ZQAUTH06");
        seedAutoParticipant("stock-auto-auth-symbol");

        mockMvc.perform(patch("/api/stock/v1/markets/auto-market/participants/stock-auto-auth-symbol/symbols/ZQAUTH06")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
	                                {
	                                  "enabled": true,
	                                  "intensity": 10
	                                }
	                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("Required role: ADMIN")));
    }

    @Test
    void updateAutoParticipantSymbolConfig_adminPrincipalHeaders_isAllowed() throws Exception {
        seedOrderBookInstrument("ZQAUTH07");
        seedAutoParticipant("stock-auto-auth-symbol-admin");

        mockMvc.perform(patch("/api/stock/v1/markets/auto-market/participants/stock-auto-auth-symbol-admin/symbols/ZQAUTH07")
                        .header("X-User-Key", "stock-admin-key")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
	                                {
	                                  "enabled": true,
	                                  "intensity": 1
	                                }
	                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"userKey\":\"stock-auto-auth-symbol-admin\"")))
                .andExpect(content().string(containsString("\"symbol\":\"ZQAUTH07\"")))
                .andExpect(content().string(containsString("\"intensity\":1")));
    }

    @Test
    void portfolio_withoutPrincipalHeaders_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/stock/v1/portfolio/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("\"success\":false")))
                .andExpect(content().string(containsString("Login required")));
    }

    @Test
    void holdings_withoutPrincipalHeaders_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/stock/v1/holdings"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("\"success\":false")))
                .andExpect(content().string(containsString("Login required")));
    }

    @Test
    void portfolio_userPrincipalHeaders_isAllowed() throws Exception {
        seedStockAccount("stock-user-key");

        mockMvc.perform(get("/api/stock/v1/portfolio/me")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")));
    }

    @Test
    void holdings_userPrincipalHeaders_isAllowed() throws Exception {
        mockMvc.perform(get("/api/stock/v1/holdings")
                        .header("X-User-Key", "stock-user-key")
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")));
    }

    @Test
    void portfolio_gatewayPrincipalHeaders_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/stock/v1/portfolio/me")
                        .header("X-User-Key", "gateway:GW-STORE-001")
                        .header("X-User-Role", "ROLE_GATEWAY"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("\"success\":false")))
                .andExpect(content().string(containsString("Required role: USER")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_MANAGER"})
    void portfolio_nonUserNonAdminAccountRoleHeaders_returnsForbidden(String role) throws Exception {
        mockMvc.perform(get("/api/stock/v1/portfolio/me")
                        .header("X-User-Key", "operator-key")
                        .header("X-User-Role", role))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("\"success\":false")))
                .andExpect(content().string(containsString("Required role: USER")));
    }

    @Test
    void portfolio_adminPrincipalHeaders_isAllowed() throws Exception {
        seedStockAccount("admin-key");

        mockMvc.perform(get("/api/stock/v1/portfolio/me")
                        .header("X-User-Key", "admin-key")
                        .header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")));
    }

    @Test
    void placeOrder_marketWithNonPositiveLimitPrice_ignoresLimitAtApiBoundary() throws Exception {
        seedStockAccount("stock-market-order-user");

        mockMvc.perform(post("/api/stock/v1/orders")
                        .header("X-User-Key", "stock-market-order-user")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol": "005930",
                                  "side": "BUY",
                                  "orderType": "MARKET",
                                  "limitPrice": 0,
                                  "quantity": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"orderType\":\"MARKET\"")))
                .andExpect(content().string(containsString("\"limitPrice\":null")));
    }

    @Test
    void placeOrder_duplicateClientOrderId_returnsExistingOrderWithoutCreatingDuplicate() throws Exception {
        seedStockAccount("stock-api-idempotent-user");

        String requestBody = """
                {
                  "symbol": "005930",
                  "side": "BUY",
                  "orderType": "LIMIT",
                  "limitPrice": 70000,
                  "quantity": 1,
                  "clientOrderId": "api-idempotent-order"
                }
                """;

        mockMvc.perform(post("/api/stock/v1/orders")
                        .header("X-User-Key", "stock-api-idempotent-user")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"clientOrderId\":\"api-idempotent-order\"")));

        mockMvc.perform(post("/api/stock/v1/orders")
                        .header("X-User-Key", "stock-api-idempotent-user")
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"clientOrderId\":\"api-idempotent-order\"")));

        Long orderCount = jdbcTemplate.queryForObject(
                "select count(*) from stock_order where client_order_id = 'api-idempotent-order'",
                Long.class
        );
        Long accountCount = jdbcTemplate.queryForObject(
                "select count(*) from stock_account where user_key = 'stock-api-idempotent-user'",
                Long.class
        );
        BigDecimal cashBalance = jdbcTemplate.queryForObject(
                "select cash_balance from stock_account where user_key = 'stock-api-idempotent-user'",
                BigDecimal.class
        );

        assertThat(orderCount).isEqualTo(1L);
        assertThat(accountCount).isEqualTo(1L);
        assertThat(cashBalance).isEqualByComparingTo(new BigDecimal("9930000.00"));
    }

    @Test
    void getMyAccount_withoutExistingAccount_returnsNotFoundWithoutOpeningAccount() throws Exception {
        String userKey = "stock-no-auto-account-user";

        mockMvc.perform(get("/api/stock/v1/accounts/me")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Account not found")));

        assertThat(stockAccountCount(userKey)).isZero();
    }

    @Test
    void getMyAccountStatus_withExistingAccount_returnsConnectedAccountWithoutOpeningDuplicate() throws Exception {
        String userKey = "stock-existing-status-user";
        seedStockAccount(userKey);

        mockMvc.perform(get("/api/stock/v1/accounts/me/status")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"hasAccount\":true")))
                .andExpect(content().string(containsString("\"userKey\":\"stock-existing-status-user\"")));

        assertThat(stockAccountCount(userKey)).isEqualTo(1L);
    }

    @Test
    void readListAndSummaryApis_withoutExistingAccount_doNotOpenAccount() throws Exception {
        String userKey = "stock-no-auto-read-user";

        mockMvc.perform(get("/api/stock/v1/orders")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"data\":[]")));

        mockMvc.perform(get("/api/stock/v1/executions")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"data\":[]")));

        mockMvc.perform(get("/api/stock/v1/holdings")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"data\":[]")));

        mockMvc.perform(get("/api/stock/v1/portfolio/me/snapshots")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"data\":[]")));

        mockMvc.perform(get("/api/stock/v1/portfolio/me/profit-summary")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"executionCount\":0")));

        assertThat(stockAccountCount(userKey)).isZero();
    }

    @Test
    void openMyAccount_withoutExistingAccount_createsAccount() throws Exception {
        String userKey = "stock-explicit-open-user";

        mockMvc.perform(post("/api/stock/v1/accounts/me")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"userKey\":\"stock-explicit-open-user\"")))
                .andExpect(content().string(containsString("\"accountCode\":\"STK-")))
                .andExpect(content().string(containsString("\"recoveryCode\":\"RC-")));

        assertThat(stockAccountCount(userKey)).isEqualTo(1L);
    }

    @Test
    void openMyAccount_withExistingAccount_returnsExistingAccountWithoutOpeningDuplicate() throws Exception {
        String userKey = "stock-explicit-open-existing-user";
        seedStockAccount(userKey);

        mockMvc.perform(post("/api/stock/v1/accounts/me")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"userKey\":\"stock-explicit-open-existing-user\"")))
                .andExpect(content().string(containsString("\"accountCode\":\"STK-")))
                .andExpect(content().string(containsString("\"recoveryCode\":\"RC-")));

        assertThat(stockAccountCount(userKey)).isEqualTo(1L);
    }

    @Test
    void detachMyAccount_withExistingAccount_detachesAccountAndIssuesRecoveryCode() throws Exception {
        String userKey = "stock-detach-user";
        seedStockAccount(userKey);

        String content = mockMvc.perform(delete("/api/stock/v1/accounts/me")
                        .header("X-User-Key", userKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"DETACHED\"")))
                .andExpect(content().string(containsString("\"userKey\":null")))
                .andExpect(content().string(containsString("\"accountCode\":\"STK-")))
                .andExpect(content().string(containsString("\"recoveryCode\":\"RC-")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode account = objectMapper.readTree(content).path("data");
        assertThat(stockAccountCount(userKey)).isZero();
        assertThat(account.path("recoveryExpiresAt").asText()).isNotBlank();
        assertThat(account.path("purgeAfter").asText()).isNotBlank();
    }

    @Test
    void reconnectMyAccount_withDetachedAccount_connectsNewUserAndRotatesRecoveryCode() throws Exception {
        String oldUserKey = "stock-reconnect-old-user";
        String newUserKey = "stock-reconnect-new-user";
        seedStockAccount(oldUserKey);

        String detachContent = mockMvc.perform(delete("/api/stock/v1/accounts/me")
                        .header("X-User-Key", oldUserKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode detachedAccount = objectMapper.readTree(detachContent).path("data");
        String accountCode = detachedAccount.path("accountCode").asText();
        String recoveryCode = detachedAccount.path("recoveryCode").asText();

        String reconnectContent = mockMvc.perform(post("/api/stock/v1/accounts/reconnect")
                        .header("X-User-Key", newUserKey)
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountCode":"%s","recoveryCode":"%s"}
                                """.formatted(accountCode, recoveryCode)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"userKey\":\"stock-reconnect-new-user\"")))
                .andExpect(content().string(containsString("\"status\":\"ACTIVE\"")))
                .andExpect(content().string(containsString("\"recoveryCode\":\"RC-")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode reconnectedAccount = objectMapper.readTree(reconnectContent).path("data");
        assertThat(stockAccountCount(oldUserKey)).isZero();
        assertThat(stockAccountCount(newUserKey)).isEqualTo(1L);
        assertThat(reconnectedAccount.path("recoveryCode").asText()).isNotEqualTo(recoveryCode);
    }

    @Test
    void reconnectMyAccount_withExpiredRecoveryCode_returnsConflict() throws Exception {
        String oldUserKey = "stock-reconnect-expired-old-user";
        String newUserKey = "stock-reconnect-expired-new-user";
        seedStockAccount(oldUserKey);

        String detachContent = mockMvc.perform(delete("/api/stock/v1/accounts/me")
                        .header("X-User-Key", oldUserKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode detachedAccount = objectMapper.readTree(detachContent).path("data");
        String accountCode = detachedAccount.path("accountCode").asText();
        String recoveryCode = detachedAccount.path("recoveryCode").asText();
        jdbcTemplate.update(
                "update stock_account set recovery_expires_at = ? where account_code = ?",
                LocalDateTime.now().minusDays(1),
                accountCode
        );

        mockMvc.perform(post("/api/stock/v1/accounts/reconnect")
                        .header("X-User-Key", newUserKey)
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountCode":"%s","recoveryCode":"%s"}
                                """.formatted(accountCode, recoveryCode)))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("Account recovery code expired")));
    }

    @Test
    void reconnectMyAccount_afterPurgeWindow_returnsNotFoundAndClosesAccount() throws Exception {
        String oldUserKey = "stock-reconnect-purge-old-user";
        String newUserKey = "stock-reconnect-purge-new-user";
        seedStockAccount(oldUserKey);

        String detachContent = mockMvc.perform(delete("/api/stock/v1/accounts/me")
                        .header("X-User-Key", oldUserKey)
                        .header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode detachedAccount = objectMapper.readTree(detachContent).path("data");
        String accountCode = detachedAccount.path("accountCode").asText();
        String recoveryCode = detachedAccount.path("recoveryCode").asText();
        jdbcTemplate.update(
                "update stock_account set recovery_expires_at = ?, purge_after = ? where account_code = ?",
                LocalDateTime.now().minusDays(61),
                LocalDateTime.now().minusDays(1),
                accountCode
        );

        mockMvc.perform(post("/api/stock/v1/accounts/reconnect")
                        .header("X-User-Key", newUserKey)
                        .header("X-User-Role", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountCode":"%s","recoveryCode":"%s"}
                                """.formatted(accountCode, recoveryCode)))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Account recovery period expired")));

        String status = jdbcTemplate.queryForObject(
                "select status from stock_account where account_code = ?",
                String.class,
                accountCode
        );
        assertThat(status).isEqualTo("CLOSED");
    }
}
