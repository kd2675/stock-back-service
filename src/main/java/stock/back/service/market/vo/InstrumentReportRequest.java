package stock.back.service.market.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InstrumentReportRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 1000) String summary,
        @NotNull @Min(1) @Max(10) Integer score,
        @Size(max = 500) String riseReason,
        @Size(max = 500) String fallReason
) {
}
