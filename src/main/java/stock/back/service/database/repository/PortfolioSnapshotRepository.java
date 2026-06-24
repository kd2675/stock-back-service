package stock.back.service.database.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import stock.back.service.database.entity.PortfolioSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {
    List<PortfolioSnapshot> findTop20BySnapshotDateOrderByReturnRateDesc(LocalDate snapshotDate);

    List<PortfolioSnapshot> findTop30ByAccountIdOrderBySnapshotDateDesc(Long accountId);

    Optional<PortfolioSnapshot> findTopByOrderBySnapshotDateDesc();
}
