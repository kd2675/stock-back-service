package stock.back.service.market.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import stock.back.service.common.exception.StockException;
import stock.back.service.database.entity.StockAutoMarketConfig;
import stock.back.service.database.entity.StockCorporateAction;
import stock.back.service.database.entity.StockListingAutoAccountConfig;
import stock.back.service.database.entity.StockOrderBookInstrument;
import stock.back.service.database.entity.StockOrderBookMarketConfig;
import stock.back.service.database.entity.StockPrice;
import stock.back.service.database.repository.StockAutoMarketConfigRepository;
import stock.back.service.database.repository.StockCorporateActionRepository;
import stock.back.service.database.repository.StockInstrumentRepository;
import stock.back.service.database.repository.StockListingAutoAccountConfigRepository;
import stock.back.service.database.repository.StockOrderBookInstrumentRepository;
import stock.back.service.database.repository.StockOrderBookMarketConfigRepository;
import stock.back.service.database.repository.StockPriceRepository;
import stock.back.service.market.vo.ListingAutoAccountRequest;
import stock.back.service.market.vo.OrderBookInstrumentRequest;
import stock.back.service.market.vo.OrderBookInstrumentResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class OrderBookInstrumentCommandService {

    private static final String LISTING_SUPPLY_USER_KEY_PREFIX = "stock-listing-";

    private final StockInstrumentRepository stockInstrumentRepository;
    private final StockPriceRepository stockPriceRepository;
    private final StockAutoMarketConfigRepository stockAutoMarketConfigRepository;
    private final StockOrderBookInstrumentRepository stockOrderBookInstrumentRepository;
    private final StockOrderBookMarketConfigRepository stockOrderBookMarketConfigRepository;
    private final StockCorporateActionRepository stockCorporateActionRepository;
    private final StockListingAutoAccountConfigRepository stockListingAutoAccountConfigRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public OrderBookInstrumentResponse createOrderBookInstrument(OrderBookInstrumentRequest request) {
        String symbol = normalizeSymbol(request == null ? null : request.symbol());
        String name = normalizeText(request == null ? null : request.name());
        String market = normalizeText(request == null ? null : request.market());
        if (symbol.isBlank()) {
            throw StockException.badRequest("Symbol is required");
        }
        if (name.isBlank()) {
            throw StockException.badRequest("Name is required");
        }
        if (market.isBlank()) {
            market = "ORDERBOOK";
        }
        validateInstrumentRequest(symbol, name, market, request);
        if (stockInstrumentRepository.existsById(symbol)) {
            throw StockException.conflict("Symbol already exists in virtual price market: " + symbol);
        }
        if (stockOrderBookInstrumentRepository.existsById(symbol)) {
            throw StockException.conflict("Order book symbol already exists: " + symbol);
        }

        BigDecimal tickSize = request.tickSize() == null ? BigDecimal.ONE : request.tickSize();
        BigDecimal priceLimitRate = request.priceLimitRate() == null ? BigDecimal.valueOf(30) : request.priceLimitRate();
        StockOrderBookInstrument instrument = stockOrderBookInstrumentRepository.save(
                StockOrderBookInstrument.listed(symbol, name, market, request.initialPrice(), request.issuedShares(), tickSize, priceLimitRate)
        );
        stockCorporateActionRepository.save(
                StockCorporateAction.initialIssue(symbol, request.issuedShares(), request.initialPrice())
        );
        stockOrderBookMarketConfigRepository.save(StockOrderBookMarketConfig.enabled(symbol));
        stockAutoMarketConfigRepository.save(StockAutoMarketConfig.defaults(symbol));
        stockPriceRepository.save(StockPrice.initial(symbol, request.initialPrice()));
        seedListingAutoAccount(symbol, name, request.initialPrice(), request.issuedShares(), request.listingAutoAccount());
        return OrderBookInstrumentResponseMapper.toResponse(instrument, findPrice(instrument));
    }

    private void validateInstrumentRequest(String symbol, String name, String market, OrderBookInstrumentRequest request) {
        if (!symbol.matches("[A-Z0-9]{2,20}")) {
            throw StockException.badRequest("Symbol must be 2-20 uppercase letters or digits");
        }
        if (name.length() > 120) {
            throw StockException.badRequest("Name must be 120 characters or less");
        }
        if (market.length() > 20) {
            throw StockException.badRequest("Market must be 20 characters or less");
        }
        if (request == null || request.initialPrice() == null || request.initialPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Initial price must be positive");
        }
        if (request.issuedShares() == null || request.issuedShares() <= 0) {
            throw StockException.badRequest("Issued shares must be positive");
        }
        BigDecimal tickSize = request.tickSize() == null ? BigDecimal.ONE : request.tickSize();
        BigDecimal priceLimitRate = request.priceLimitRate() == null ? BigDecimal.valueOf(30) : request.priceLimitRate();
        if (tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            throw StockException.badRequest("Tick size must be positive");
        }
        if (priceLimitRate.compareTo(BigDecimal.ZERO) <= 0 || priceLimitRate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw StockException.badRequest("Price limit rate must be greater than 0 and 100 or less");
        }
    }

    private void seedListingAutoAccount(
            String symbol,
            String name,
            BigDecimal initialPrice,
            long tradableShares,
            ListingAutoAccountRequest request
    ) {
        LocalDateTime now = LocalDateTime.now();
        String listingUserKey = LISTING_SUPPLY_USER_KEY_PREFIX + symbol.toLowerCase(Locale.ROOT);
        String displayName = normalizeText(request == null ? null : request.displayName());
        if (displayName.isBlank()) {
            displayName = name + " 상장주관사";
        }
        if (displayName.length() > 80) {
            throw StockException.badRequest("Listing auto account display name must be 80 characters or less");
        }
        jdbcTemplate.update(
                """
                insert into stock_account(
                    user_key, status, cash_balance, created_at, updated_at
                )
                values (?, 'ACTIVE', 0.00, ?, ?)
                """,
                listingUserKey,
                now,
                now
        );
        Long accountId = jdbcTemplate.queryForObject(
                "select id from stock_account where user_key = ?",
                Long.class,
                listingUserKey
        );
        if (accountId == null) {
            throw StockException.notFound("Listing supply account not found after opening");
        }
        jdbcTemplate.update(
                """
                insert into stock_holding(
                    account_id, symbol, quantity, reserved_quantity, average_price, updated_at
                )
                values (?, ?, ?, ?, ?, ?)
                """,
                accountId,
                symbol,
                tradableShares,
                0L,
                initialPrice,
                now
        );
        StockListingAutoAccountConfig config = StockListingAutoAccountConfig.defaults(symbol, listingUserKey, displayName, tradableShares);
        if (request != null) {
            config.update(
                    displayName,
                    request.enabled(),
                    request.positionSide(),
                    request.maxOrderQuantity(),
                    request.orderTtlSeconds(),
                    request.priceOffsetTicks()
            );
            ListingAutoAccountConfigValidator.validate(config);
        }
        stockListingAutoAccountConfigRepository.save(config);
    }

    private StockPrice findPrice(StockOrderBookInstrument instrument) {
        return stockPriceRepository.findById(instrument.getSymbol()).orElse(null);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }
}
