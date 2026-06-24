package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.StockCorporateActionEntitlement;

import java.util.List;

public interface StockCorporateActionEntitlementRepository extends JpaRepository<StockCorporateActionEntitlement, Long> {

    List<StockCorporateActionEntitlement> findTop50ByAccountIdOrderByCreatedAtDesc(Long accountId);
}
