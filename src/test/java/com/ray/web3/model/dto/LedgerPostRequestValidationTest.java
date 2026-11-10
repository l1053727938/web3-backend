package com.ray.web3.model.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记账请求形状校验单测（第 2 周）：不启动 Spring 上下文，直接用 Hibernate Validator 验证 DTO 约束。
 * <p>
 * 锁死边界：嵌套分录会被级联校验、枚举忽略大小写且报错时列出可用值、金额精度不超过
 * {@code DECIMAL(38,18)}、可选 balanceType 的空串留给业务取默认值。
 */
class LedgerPostRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void 合法请求无校验错误() {
        assertThat(validator.validate(validRequest())).isEmpty();
    }

    @Test
    void 枚举值大小写不敏感() {
        LedgerPostRequest request = new LedgerPostRequest("DEPOSIT", "D1",
                List.of(posting(1L, "debit", "100.5", "usdt", "available"),
                        posting(2L, "Credit", "100.5", "USDT", null)));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 非法枚举值报错并列出可用值() {
        LedgerPostRequest request = new LedgerPostRequest("DEPOSIT", "D1",
                List.of(posting(1L, "BUY", "100", "USDT", null),
                        posting(2L, "CREDIT", "100", "USDT", null)));

        Set<ConstraintViolation<LedgerPostRequest>> violations = validator.validate(request);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("postings[0].direction");
        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .anyMatch(message -> message.contains("CREDIT/DEBIT"));
    }

    @Test
    void 分录少于两条被拒绝() {
        LedgerPostRequest request = new LedgerPostRequest("DEPOSIT", "D1",
                List.of(posting(1L, "CREDIT", "100", "USDT", null)));

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("postings");
    }

    @Test
    void 金额必须为正且不超过账本精度() {
        assertThat(validator.validate(withAmount("0"))).isNotEmpty();
        assertThat(validator.validate(withAmount("-1"))).isNotEmpty();
        assertThat(validator.validate(withAmount("1.0000000000000000001"))).isNotEmpty();
        assertThat(validator.validate(withAmount("1.000000000000000001"))).isEmpty();
    }

    @Test
    void 可选balanceType的空串留给业务取默认值() {
        assertThat(validator.validate(withBalanceType("  "))).isEmpty();
        assertThat(validator.validate(withBalanceType("FROZEN"))).isEmpty();
        assertThat(validator.validate(withBalanceType("LOCKED"))).isNotEmpty();
    }

    private static LedgerPostRequest validRequest() {
        return new LedgerPostRequest("DEPOSIT", "D1",
                List.of(posting(1L, "CREDIT", "100.5", "USDT", null),
                        posting(2L, "DEBIT", "100.5", "USDT", null)));
    }

    private static LedgerPostRequest withAmount(String amount) {
        return new LedgerPostRequest("DEPOSIT", "D1",
                List.of(posting(1L, "CREDIT", amount, "USDT", null),
                        posting(2L, "DEBIT", amount, "USDT", null)));
    }

    private static LedgerPostRequest withBalanceType(String balanceType) {
        return new LedgerPostRequest("DEPOSIT", "D1",
                List.of(posting(1L, "CREDIT", "100", "USDT", balanceType),
                        posting(2L, "DEBIT", "100", "USDT", balanceType)));
    }

    private static LedgerPostRequest.PostingRequest posting(Long accountId, String direction,
                                                            String amount, String currency,
                                                            String balanceType) {
        return new LedgerPostRequest.PostingRequest(accountId, direction, amount, currency, balanceType);
    }
}
