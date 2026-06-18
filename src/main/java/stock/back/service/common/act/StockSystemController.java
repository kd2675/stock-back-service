package stock.back.service.common.act;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import stock.back.service.common.vo.StockServiceStatus;
import web.common.core.response.base.dto.ResponseDataDTO;

import java.util.List;

@RestController
@RequestMapping("/api/stock/v1/system")
public class StockSystemController {

    @GetMapping("/status")
    public ResponseDataDTO<StockServiceStatus> status() {
        return ResponseDataDTO.of(
                new StockServiceStatus(
                        "stock-back-service",
                        List.of("accounts", "orders", "executions", "holdings", "rankings"),
                        true
                )
        );
    }

}
