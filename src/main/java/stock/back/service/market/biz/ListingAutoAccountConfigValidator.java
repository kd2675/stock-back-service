package stock.back.service.market.biz;

import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.ListingAutoPosition;
import stock.back.service.database.entity.ListingAutoPriceDirection;
import stock.back.service.database.entity.StockListingAutoAccountConfig;

final class ListingAutoAccountConfigValidator {

    private ListingAutoAccountConfigValidator() {
    }

    static void validate(StockListingAutoAccountConfig config) {
        if (config.getPositionSide() == null) {
            throw StockException.badRequest("Listing auto account position side is required");
        }
        if (config.getPositionSide() != ListingAutoPosition.SELL_ONLY
                && config.getPositionSide() != ListingAutoPosition.BUY_ONLY
                && config.getPositionSide() != ListingAutoPosition.TWO_SIDED) {
            throw StockException.badRequest("Listing auto account position side must be SELL_ONLY, BUY_ONLY, or TWO_SIDED");
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
        if (config.getTargetBuyQuantity() == null || config.getTargetBuyQuantity() < 0) {
            throw StockException.badRequest("Listing auto account target buy quantity must be zero or positive");
        }
        if (config.getTargetSellQuantity() == null || config.getTargetSellQuantity() < 0) {
            throw StockException.badRequest("Listing auto account target sell quantity must be zero or positive");
        }
        if (config.getTargetHoldingQuantity() == null || config.getTargetHoldingQuantity() < 0) {
            throw StockException.badRequest("Listing auto account target holding quantity must be zero or positive");
        }
        if (config.getInventoryBandQuantity() == null || config.getInventoryBandQuantity() < 0) {
            throw StockException.badRequest("Listing auto account inventory band quantity must be zero or positive");
        }
        if ((config.getPositionSide() == ListingAutoPosition.BUY_ONLY
                || config.getPositionSide() == ListingAutoPosition.TWO_SIDED)
                && config.getTargetBuyQuantity() <= 0) {
            throw StockException.badRequest("Listing auto account active buy side requires a positive target quantity");
        }
        if ((config.getPositionSide() == ListingAutoPosition.SELL_ONLY
                || config.getPositionSide() == ListingAutoPosition.TWO_SIDED)
                && config.getTargetSellQuantity() <= 0) {
            throw StockException.badRequest("Listing auto account active sell side requires a positive target quantity");
        }
        if (config.getPositionSide() == ListingAutoPosition.TWO_SIDED) {
            validateTwoSidedInventoryPolicy(config);
        }
        validateDirection(config.getBuyPriceOffsetDirection(), "buy");
        validateDirection(config.getSellPriceOffsetDirection(), "sell");
    }

    static void validate(StockListingAutoAccountConfig config, long issuedShares) {
        validate(config);
        if (config.getPositionSide() == ListingAutoPosition.TWO_SIDED
                && issuedShares > 0
                && config.getTargetHoldingQuantity() > issuedShares - config.getInventoryBandQuantity()) {
            throw StockException.badRequest("Two-sided inventory upper limit cannot exceed issued shares");
        }
    }

    private static void validateTwoSidedInventoryPolicy(StockListingAutoAccountConfig config) {
        long bandQuantity = config.getInventoryBandQuantity();
        if (bandQuantity <= 0) {
            throw StockException.badRequest("Two-sided listing operation requires a positive inventory band quantity");
        }
        if (config.getTargetHoldingQuantity() < bandQuantity) {
            throw StockException.badRequest("Two-sided inventory band cannot exceed target holding quantity");
        }
        if (config.getTargetBuyQuantity() > bandQuantity) {
            throw StockException.badRequest("Two-sided buy quote target cannot exceed inventory band quantity");
        }
        if (config.getTargetSellQuantity() > bandQuantity) {
            throw StockException.badRequest("Two-sided sell quote target cannot exceed inventory band quantity");
        }
    }

    private static void validateDirection(ListingAutoPriceDirection direction, String side) {
        if (direction == null) {
            throw StockException.badRequest("Listing auto account " + side + " price offset direction is required");
        }
    }
}
