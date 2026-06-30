package stock.back.service.market.biz;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.ListingAutoPosition;
import stock.back.service.database.entity.StockListingAutoAccountConfig;

final class ListingAutoAccountConfigValidator {

    private ListingAutoAccountConfigValidator() {
    }

    static void validate(StockListingAutoAccountConfig config) {
        if (config.getPositionSide() == null) {
            throw StockException.badRequest("Listing auto account position side is required");
        }
        if (config.getPositionSide() != ListingAutoPosition.SELL_ONLY && config.getPositionSide() != ListingAutoPosition.BUY_ONLY) {
            throw StockException.badRequest("Listing auto account position side must be SELL_ONLY or BUY_ONLY");
        }
        if (config.getMaxOrderQuantity() == null || config.getMaxOrderQuantity() <= 0) {
            throw StockException.badRequest("Listing auto account max order quantity must be positive");
        }
        if (config.getOrderTtlSeconds() == null || config.getOrderTtlSeconds() <= 0) {
            throw StockException.badRequest("Listing auto account order TTL seconds must be positive");
        }
        if (config.getPriceOffsetTicks() == null || config.getPriceOffsetTicks() < 0) {
            throw StockException.badRequest("Listing auto account price offset ticks must be zero or positive");
        }
    }
}
