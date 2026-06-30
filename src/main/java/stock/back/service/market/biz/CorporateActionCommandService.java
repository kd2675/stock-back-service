package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockCorporateActionType;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.CorporateActionRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CorporateActionCommandService {

    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockPriceRepository stockPriceRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public OrderBookInstrumentResponse applyCorporateAction(String symbol, CorporateActionRequest request) {
        String normalizedSymbol = normalizeSymbol(symbol);
        if (normalizedSymbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (request == null || request.actionType() == null) {
            throw StockException.badRequest("Corporate action type is required");
        }
        if (request.actionType() == StockCorporateActionType.INITIAL_ISSUE) {
            throw StockException.badRequest("Initial issue is only allowed when creating an instrument");
        }
        CorporateActionFieldScopeValidator.validate(request);
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.findById(normalizedSymbol)
                .orElseThrow(() -> StockException.notFound("Unknown order book symbol: " + normalizedSymbol));
        if (request.actionType() != StockCorporateActionType.DELISTING) {
            assertNoOpenOrderBookOrders(normalizedSymbol);
        }

        return switch (request.actionType()) {
            case PAID_IN_CAPITAL_INCREASE, ADDITIONAL_ISSUE -> applyShareIssue(instrument, request);
            case BONUS_ISSUE, STOCK_DIVIDEND -> applyFreeShareDistribution(instrument, request);
            case STOCK_SPLIT -> applyStockSplit(instrument, request);
            case CASH_DIVIDEND -> applyCashDividend(instrument, request);
            case DELISTING -> applyDelisting(instrument, request);
            case INITIAL_ISSUE -> throw StockException.badRequest("Initial issue is only allowed when creating an instrument");
        };
    }

    private OrderBookInstrumentResponse applyShareIssue(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        long shares = request.shareQuantity() == null ? 0L : request.shareQuantity();
        if (shares <= 0) {
            throw StockException.badRequest("Share quantity must be positive");
        }
        BigDecimal issuePrice = request.issuePrice();
        if (issuePrice == null || issuePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Share issue requires a positive issue price");
        }

        if (request.actionType() == StockCorporateActionType.PAID_IN_CAPITAL_INCREASE) {
            return announcePaidInCapitalIncrease(instrument, request, shares, issuePrice);
        }

        return announceAdditionalIssue(instrument, request, shares, issuePrice);
    }

    private OrderBookInstrumentResponse announcePaidInCapitalIncrease(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            long shares,
            BigDecimal issuePrice
    ) {
        LocalDate exRightsDate = request.exRightsDate();
        LocalDate paymentDate = request.paymentDate();
        LocalDate listingDate = request.listingDate();
        if (exRightsDate == null || paymentDate == null || listingDate == null) {
            throw StockException.badRequest("Paid-in capital increase requires ex-rights, payment, and listing dates");
        }
        if (paymentDate.isBefore(exRightsDate) || listingDate.isBefore(paymentDate)) {
            throw StockException.badRequest("Paid-in capital increase dates must be ordered by ex-rights, payment, listing");
        }

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();
        BigDecimal theoreticalExRightsPrice = calculateTheoreticalExRightsPrice(
                instrument.getIssuedShares(),
                basePrice,
                shares,
                issuePrice
        );
        stockCorporateActionRepository.save(StockCorporateAction.paidInCapitalIncrease(
                instrument.getSymbol(),
                shares,
                issuePrice,
                basePrice,
                theoreticalExRightsPrice,
                exRightsDate,
                paymentDate,
                listingDate,
                normalizeNullableDescription(request.description())
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, price);
    }

    private OrderBookInstrumentResponse announceAdditionalIssue(
            StockOrderBookInstrument instrument,
            CorporateActionRequest request,
            long shares,
            BigDecimal issuePrice
    ) {
        LocalDate listingDate = request.listingDate();
        if (listingDate == null) {
            throw StockException.badRequest("Additional issue requires a listing date");
        }
        stockCorporateActionRepository.save(StockCorporateAction.additionalIssue(
                instrument.getSymbol(),
                shares,
                issuePrice,
                listingDate,
                normalizeNullableDescription(request.description())
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    private BigDecimal calculateTheoreticalExRightsPrice(
            long existingShares,
            BigDecimal basePrice,
            long newShares,
            BigDecimal issuePrice
    ) {
        BigDecimal existingValue = basePrice.multiply(BigDecimal.valueOf(existingShares));
        BigDecimal issueValue = issuePrice.multiply(BigDecimal.valueOf(newShares));
        return existingValue.add(issueValue)
                .divide(BigDecimal.valueOf(existingShares + newShares), 2, RoundingMode.HALF_UP);
    }

    private OrderBookInstrumentResponse applyFreeShareDistribution(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        long shares = request.shareQuantity() == null ? 0L : request.shareQuantity();
        if (shares <= 0) {
            throw StockException.badRequest("Share quantity must be positive");
        }
        LocalDate exRightsDate = request.exRightsDate();
        LocalDate listingDate = request.listingDate();
        if (exRightsDate == null || listingDate == null) {
            throw StockException.badRequest("Free share distribution requires ex-rights and listing dates");
        }
        if (listingDate.isBefore(exRightsDate)) {
            throw StockException.badRequest("Free share distribution listing date must be on or after ex-rights date");
        }

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();
        BigDecimal theoreticalExRightsPrice = calculateTheoreticalFreeSharePrice(
                instrument.getIssuedShares(),
                basePrice,
                shares
        );
        StockCorporateAction action = request.actionType() == StockCorporateActionType.BONUS_ISSUE
                ? StockCorporateAction.bonusIssue(
                        instrument.getSymbol(),
                        shares,
                        basePrice,
                        theoreticalExRightsPrice,
                        exRightsDate,
                        listingDate,
                        normalizeNullableDescription(request.description())
                )
                : StockCorporateAction.stockDividend(
                        instrument.getSymbol(),
                        shares,
                        basePrice,
                        theoreticalExRightsPrice,
                        exRightsDate,
                        listingDate,
                        normalizeNullableDescription(request.description())
                );
        stockCorporateActionRepository.save(action);
        return OrderBookInstrumentResponseMapper.toResponse(instrument, price);
    }

    private BigDecimal calculateTheoreticalFreeSharePrice(
            long existingShares,
            BigDecimal basePrice,
            long newShares
    ) {
        BigDecimal existingValue = basePrice.multiply(BigDecimal.valueOf(existingShares));
        return existingValue.divide(BigDecimal.valueOf(existingShares + newShares), 2, RoundingMode.HALF_UP);
    }

    private OrderBookInstrumentResponse applyStockSplit(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        Integer splitFrom = request.splitFrom();
        Integer splitTo = request.splitTo();
        if (splitFrom == null || splitTo == null || splitFrom <= 0 || splitTo <= 0 || splitTo <= splitFrom) {
            throw StockException.badRequest("Stock split ratio must be positive and greater than 1:1");
        }
        if (splitTo % splitFrom != 0) {
            throw StockException.badRequest("Only integer share split ratios are supported");
        }
        LocalDate listingDate = request.listingDate();
        if (listingDate == null) {
            throw StockException.badRequest("Stock split requires an effective date");
        }
        stockCorporateActionRepository.save(StockCorporateAction.stockSplit(
                instrument.getSymbol(),
                splitFrom,
                splitTo,
                listingDate,
                normalizeNullableDescription(request.description())
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    private OrderBookInstrumentResponse applyCashDividend(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        BigDecimal dividendAmount = request.dividendAmount();
        if (dividendAmount == null || dividendAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Cash dividend amount must be positive");
        }
        LocalDate exRightsDate = request.exRightsDate();
        LocalDate paymentDate = request.paymentDate();
        if (exRightsDate == null || paymentDate == null) {
            throw StockException.badRequest("Cash dividend requires ex-dividend and payment dates");
        }
        if (paymentDate.isBefore(exRightsDate)) {
            throw StockException.badRequest("Cash dividend payment date must be on or after ex-dividend date");
        }

        StockPrice price = stockPriceRepository.findById(instrument.getSymbol())
                .orElseThrow(() -> StockException.notFound("Price not found: " + instrument.getSymbol()));
        BigDecimal basePrice = price.getCurrentPrice();

        stockCorporateActionRepository.save(StockCorporateAction.cashDividend(
                instrument.getSymbol(),
                dividendAmount,
                basePrice,
                basePrice,
                exRightsDate,
                paymentDate,
                normalizeNullableDescription(request.description())
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, price);
    }

    private OrderBookInstrumentResponse applyDelisting(StockOrderBookInstrument instrument, CorporateActionRequest request) {
        LocalDate delistingDate = request.delistingDate();
        if (delistingDate == null) {
            throw StockException.badRequest("Delisting requires a delisting date");
        }
        stockCorporateActionRepository.save(StockCorporateAction.delisting(
                instrument.getSymbol(),
                delistingDate,
                normalizeNullableDescription(request.description())
        ));
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    private void assertNoOpenOrderBookOrders(String symbol) {
        Long openOrderCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                  from stock_order
                 where symbol = ?
                   and market_type = 'ORDER_BOOK'
                   and status in ('PENDING', 'PARTIALLY_FILLED')
                """,
                Long.class,
                symbol
        );
        if (openOrderCount != null && openOrderCount > 0) {
            throw StockException.conflict("Corporate action requires no open order book orders: " + symbol);
        }
    }

    private String normalizeNullableDescription(String value) {
        String description = normalizeText(value);
        if (description.isBlank()) {
            return null;
        }
        if (description.length() > 255) {
            throw StockException.badRequest("Description must be 255 characters or less");
        }
        return description;
    }

    private StockPrice findPrice(StockOrderBookInstrument instrument) {
        return stockPriceRepository.findById(instrument.getSymbol()).orElse(null);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
