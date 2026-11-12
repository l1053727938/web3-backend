package com.ray.web3.model.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四类业务场景 + 冲正请求 DTO 的离线校验单测（第 2 周 Day 5-6）
 * <p>
 * 重点锁三件事：
 * <ul>
 *   <li>{@link com.ray.web3.validation.PositiveAmount} 组合约束真正生效（0/负数/超精度都被拒）；</li>
 *   <li>{@code settle.fee} 可选且允许 0（0 表示只记本金），但非法格式仍拒绝；</li>
 *   <li>账户 ID 必须为正数，业务单号不能为空。</li>
 * </ul>
 */
class LedgerScenarioRequestValidationTest {

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
    void 合法充值请求无校验错误() {
        assertThat(validator.validate(deposit("100"))).isEmpty();
    }

    @Test
    void 充值金额必须为正且不超过账本精度() {
        assertThat(validator.validate(deposit("0"))).isNotEmpty();
        assertThat(validator.validate(deposit("-1"))).isNotEmpty();
        assertThat(validator.validate(deposit("1.0000000000000000001"))).isNotEmpty();
        assertThat(validator.validate(deposit("1.000000000000000001"))).isEmpty();
    }

    @Test
    void 账户ID必须为正数() {
        LedgerFreezeRequest request = new LedgerFreezeRequest("ORD-1", 0L, "10", "USDT");
        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("userAccountId");
    }

    @Test
    void 冻结与解冻请求的合法形状() {
        assertThat(validator.validate(new LedgerFreezeRequest("ORD-1", 1L, "10", "USDT"))).isEmpty();
        assertThat(validator.validate(new LedgerUnfreezeRequest("WD-1", 1L, "10", "USDT"))).isEmpty();
        assertThat(validator.validate(new LedgerUnfreezeRequest("", 1L, "10", "USDT"))).isNotEmpty();
    }

    @Test
    void 清算手续费可省略或为0但非法格式应拒绝() {
        assertThat(validator.validate(settle(null))).isEmpty();
        assertThat(validator.validate(settle("0"))).isEmpty();
        assertThat(validator.validate(settle("1.5"))).isEmpty();
        assertThat(validator.validate(settle("-1"))).isNotEmpty();
        assertThat(validator.validate(settle("abc"))).isNotEmpty();
    }

    @Test
    void 冲正请求业务键不能为空() {
        assertThat(validator.validate(new LedgerReverseRequest("DEPOSIT", "DEP-1"))).isEmpty();
        Set<ConstraintViolation<LedgerReverseRequest>> violations =
                validator.validate(new LedgerReverseRequest("", " "));
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("bizType", "bizNo");
    }

    private static LedgerDepositRequest deposit(String amount) {
        return new LedgerDepositRequest("DEP-1", 900001L, 900101L, amount, "USDT");
    }

    private static LedgerSettleRequest settle(String fee) {
        return new LedgerSettleRequest(
                "TRADE-1", 900002L, 900001L, 900003L, 900102L, "100", fee, "USDT");
    }
}