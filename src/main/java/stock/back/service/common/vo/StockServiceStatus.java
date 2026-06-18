package stock.back.service.common.vo;

import java.util.List;

public record StockServiceStatus(
        String serviceName,
        List<String> plannedDomains,
        boolean gatewayRequired
) {
}
