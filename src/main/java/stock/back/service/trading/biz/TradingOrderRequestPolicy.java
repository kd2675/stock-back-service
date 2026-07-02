package stock.back.service.trading.biz;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.MarketType;
import stock.back.service.database.entity.OrderType;
import stock.back.service.trading.vo.OrderRequest;

final class TradingOrderRequestPolicy {

    private static final int CLIENT_ORDER_ID_MAX_LENGTH = 64;
    private static final Pattern CLIENT_ORDER_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]+");

    private TradingOrderRequestPolicy() {
    }

    static String normalizeSymbol(OrderRequest request) {
        if (request == null || request.symbol() == null) {
            return "";
        }
        return request.symbol().trim().toUpperCase(Locale.ROOT);
    }

    static void validateOrderRequest(OrderRequest request, String symbol) {
        if (request == null) {
            throw StockException.badRequest("Order request is required");
        }
        if (symbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request.side() == null) {
            throw StockException.badRequest("Order side is required");
        }
        normalizeMarketType(request);
        if (request.orderType() == null) {
            throw StockException.badRequest("Order type is required");
        }
        if (request.quantity() <= 0) {
            throw StockException.badRequest("Quantity must be positive");
        }
        if (request.orderType() == OrderType.LIMIT) {
            if (request.limitPrice() == null) {
                throw StockException.badRequest("Limit price is required for limit orders");
            }
            if (request.limitPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                throw StockException.badRequest("Limit price must be positive");
            }
        }
    }

    static String normalizeClientOrderId(OrderRequest request) {
        if (request.clientOrderId() == null || request.clientOrderId().isBlank()) {
            return UUID.randomUUID().toString();
        }
        String clientOrderId = request.clientOrderId().trim();
        if (clientOrderId.length() > CLIENT_ORDER_ID_MAX_LENGTH) {
            throw StockException.badRequest("Client order id must be 64 characters or less");
        }
        if (!CLIENT_ORDER_ID_PATTERN.matcher(clientOrderId).matches()) {
            throw StockException.badRequest("Client order id contains invalid characters");
        }
        return clientOrderId;
    }

    static MarketType normalizeMarketType(OrderRequest request) {
        if (request == null || request.marketType() == null) {
            return MarketType.VIRTUAL_PRICE;
        }
        return request.marketType();
    }
}
