package com.subhamviky.settlement.settlement;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/** Command DTO for POST /api/v1/settlements. Validated by BaseCommandService.mutate(). */
public record SubmitSettlementCommand(
        @NotBlank String payerAccount,
        @NotBlank String payeeAccount,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
}
