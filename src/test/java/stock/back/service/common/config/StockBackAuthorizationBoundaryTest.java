package stock.back.service.common.config;

import auth.common.core.context.RequirePrincipalRoleFilter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requirePrincipalRoleFilter.getFilter())
                .build();
    }

    @Test
    void marketPrices_withoutPrincipalHeaders_isPublic() throws Exception {
        mockMvc.perform(get("/api/stock/v1/markets/prices"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")));
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
    @ValueSource(strings = {"ROLE_MANAGER", "ROLE_ADMIN"})
    void portfolio_nonUserAccountRoleHeaders_returnsForbidden(String role) throws Exception {
        mockMvc.perform(get("/api/stock/v1/portfolio/me")
                        .header("X-User-Key", "operator-key")
                        .header("X-User-Role", role))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("\"success\":false")))
                .andExpect(content().string(containsString("Required role: USER")));
    }

    @Test
    void placeOrder_marketWithNonPositiveLimitPrice_ignoresLimitAtApiBoundary() throws Exception {
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
}
