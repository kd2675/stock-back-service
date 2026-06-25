package stock.back.service.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class StockBackApiSurfaceContractTest {

    private static final Set<String> EXPECTED_STOCK_API_SURFACE = Set.of(
            "GET /api/stock/v1/system/status",
            "GET /api/stock/v1/accounts/me",
            "GET /api/stock/v1/accounts/me/status",
            "POST /api/stock/v1/accounts/me",
            "DELETE /api/stock/v1/accounts/me",
            "POST /api/stock/v1/accounts/reconnect",
            "POST /api/stock/v1/accounts/admin/users/{userKey}/cash-adjustments",
            "GET /api/stock/v1/users/me",
            "GET /api/stock/v1/portfolio/me",
            "GET /api/stock/v1/portfolio/me/snapshots",
            "GET /api/stock/v1/portfolio/me/profit-summary",
            "GET /api/stock/v1/orders",
            "POST /api/stock/v1/orders",
            "DELETE /api/stock/v1/orders/{orderId}",
            "PATCH /api/stock/v1/orders/{orderId}",
            "POST /api/stock/v1/orders/{orderId}/cancel",
            "GET /api/stock/v1/executions",
            "GET /api/stock/v1/holdings",
            "GET /api/stock/v1/markets/instruments",
            "GET /api/stock/v1/markets/order-book-instruments",
            "POST /api/stock/v1/markets/order-book-instruments",
            "POST /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions",
            "GET /api/stock/v1/markets/order-book-instruments/{symbol}/corporate-actions",
            "GET /api/stock/v1/markets/order-book-instruments/{symbol}/reports",
            "GET /api/stock/v1/markets/order-book-instruments/{symbol}/reports/latest",
            "POST /api/stock/v1/markets/order-book-instruments/{symbol}/reports",
            "PATCH /api/stock/v1/markets/order-book-instruments/{symbol}/reports",
            "DELETE /api/stock/v1/markets/order-book-instruments/{symbol}/reports",
            "GET /api/stock/v1/markets/corporate-action-entitlements/me",
            "PATCH /api/stock/v1/markets/{marketType}/symbols/{symbol}/status",
            "GET /api/stock/v1/markets/prices",
            "GET /api/stock/v1/markets/prices/stream",
            "GET /api/stock/v1/markets/prices/{symbol}/ticks",
            "GET /api/stock/v1/markets/order-books/{symbol}",
            "GET /api/stock/v1/markets/rankings",
            "GET /api/stock/v1/markets/virtual-market",
            "GET /api/stock/v1/markets/order-book-market",
            "GET /api/stock/v1/markets/auto-market",
            "GET /api/stock/v1/markets/auto-market/participants/overviews",
            "GET /api/stock/v1/markets/auto-market/cash-flow",
            "PATCH /api/stock/v1/markets/auto-market/cash-flow",
            "POST /api/stock/v1/markets/auto-market/cash-flow/run",
            "GET /api/stock/v1/markets/batch-jobs/runtime-controls",
            "PATCH /api/stock/v1/markets/batch-jobs/runtime-controls/{jobName}",
            "PATCH /api/stock/v1/markets/auto-market/profile-configs/{profileType}",
            "PATCH /api/stock/v1/markets/auto-market/configs/{symbol}",
            "PATCH /api/stock/v1/markets/auto-market/listing-accounts/{symbol}",
            "PATCH /api/stock/v1/markets/auto-market/participants/{userKey}",
            "DELETE /api/stock/v1/markets/auto-market/participants/{userKey}",
            "POST /api/stock/v1/markets/auto-market/participants/{userKey}/cash-adjustments",
            "PATCH /api/stock/v1/markets/auto-market/participants/{userKey}/symbols/{symbol}"
    );

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void stockBackApiSurface_matchesInitialEssentialScope() {
        assertThat(stockApiSurface()).isEqualTo(new TreeSet<>(EXPECTED_STOCK_API_SURFACE));
    }

    private Set<String> stockApiSurface() {
        return requestMappingHandlerMapping.getHandlerMethods()
                .keySet()
                .stream()
                .flatMap(mappingInfo -> paths(mappingInfo).stream()
                        .filter(path -> path.startsWith("/api/stock/v1"))
                        .flatMap(path -> methods(mappingInfo).stream().map(method -> method + " " + path)))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> paths(RequestMappingInfo mappingInfo) {
        if (mappingInfo.getPathPatternsCondition() == null) {
            return Set.of();
        }
        return mappingInfo.getPathPatternsCondition().getPatternValues();
    }

    private Set<RequestMethod> methods(RequestMappingInfo mappingInfo) {
        Set<RequestMethod> methods = mappingInfo.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
            return Set.of(RequestMethod.GET);
        }
        return methods;
    }
}
