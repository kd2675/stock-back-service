package stock.back.service.market.biz;

import stock.back.service.common.exception.StockException;
import stock.back.service.market.vo.CorporateActionRequest;

final class CorporateActionFieldScopeValidator {

    private CorporateActionFieldScopeValidator() {
    }

    static void validate(CorporateActionRequest request) {
        switch (request.actionType()) {
            case PAID_IN_CAPITAL_INCREASE -> {
                rejectPresent(request.splitFrom(), "Paid-in capital increase does not use splitFrom");
                rejectPresent(request.splitTo(), "Paid-in capital increase does not use splitTo");
                rejectPresent(request.dividendAmount(), "Paid-in capital increase does not use dividendAmount");
                rejectPresent(request.delistingDate(), "Paid-in capital increase does not use delistingDate");
            }
            case STOCK_SPLIT -> {
                rejectPresent(request.shareQuantity(), "Stock split does not use shareQuantity");
                rejectPresent(request.issuePrice(), "Stock split does not use issuePrice");
                rejectPresent(request.offeringType(), "Stock split does not use offeringType");
                rejectPresent(request.subscriptionStartDate(), "Stock split does not use subscriptionStartDate");
                rejectPresent(request.subscriptionEndDate(), "Stock split does not use subscriptionEndDate");
                rejectPresent(request.exRightsDate(), "Stock split does not use exRightsDate");
                rejectPresent(request.paymentDate(), "Stock split does not use paymentDate");
                rejectPresent(request.dividendAmount(), "Stock split does not use dividendAmount");
                rejectPresent(request.delistingDate(), "Stock split does not use delistingDate");
            }
            case CASH_DIVIDEND -> {
                rejectPresent(request.shareQuantity(), "Cash dividend does not use shareQuantity");
                rejectPresent(request.issuePrice(), "Cash dividend does not use issuePrice");
                rejectPresent(request.offeringType(), "Cash dividend does not use offeringType");
                rejectPresent(request.subscriptionStartDate(), "Cash dividend does not use subscriptionStartDate");
                rejectPresent(request.subscriptionEndDate(), "Cash dividend does not use subscriptionEndDate");
                rejectPresent(request.splitFrom(), "Cash dividend does not use splitFrom");
                rejectPresent(request.splitTo(), "Cash dividend does not use splitTo");
                rejectPresent(request.listingDate(), "Cash dividend does not use listingDate");
                rejectPresent(request.delistingDate(), "Cash dividend does not use delistingDate");
            }
            case BONUS_ISSUE, STOCK_DIVIDEND -> {
                rejectPresent(request.issuePrice(), "Free share distribution does not use issuePrice");
                rejectPresent(request.offeringType(), "Free share distribution does not use offeringType");
                rejectPresent(request.subscriptionStartDate(), "Free share distribution does not use subscriptionStartDate");
                rejectPresent(request.subscriptionEndDate(), "Free share distribution does not use subscriptionEndDate");
                rejectPresent(request.splitFrom(), "Free share distribution does not use splitFrom");
                rejectPresent(request.splitTo(), "Free share distribution does not use splitTo");
                rejectPresent(request.paymentDate(), "Free share distribution does not use paymentDate");
                rejectPresent(request.dividendAmount(), "Free share distribution does not use dividendAmount");
                rejectPresent(request.delistingDate(), "Free share distribution does not use delistingDate");
            }
            case DELISTING -> {
                rejectPresent(request.shareQuantity(), "Delisting does not use shareQuantity");
                rejectPresent(request.issuePrice(), "Delisting does not use issuePrice");
                rejectPresent(request.offeringType(), "Delisting does not use offeringType");
                rejectPresent(request.subscriptionStartDate(), "Delisting does not use subscriptionStartDate");
                rejectPresent(request.subscriptionEndDate(), "Delisting does not use subscriptionEndDate");
                rejectPresent(request.splitFrom(), "Delisting does not use splitFrom");
                rejectPresent(request.splitTo(), "Delisting does not use splitTo");
                rejectPresent(request.exRightsDate(), "Delisting does not use exRightsDate");
                rejectPresent(request.paymentDate(), "Delisting does not use paymentDate");
                rejectPresent(request.listingDate(), "Delisting does not use listingDate");
                rejectPresent(request.dividendAmount(), "Delisting does not use dividendAmount");
            }
            case INITIAL_ISSUE -> throw StockException.badRequest("Initial issue is only allowed when creating an instrument");
        }
    }

    private static void rejectPresent(Object value, String message) {
        if (value != null) {
            throw StockException.badRequest(message);
        }
    }
}
