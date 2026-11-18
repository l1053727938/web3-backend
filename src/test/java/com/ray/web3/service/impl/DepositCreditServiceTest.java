package com.ray.web3.service.impl;

import com.ray.web3.config.DepositConsumerProperties;
import com.ray.web3.ledger.mapper.AccountMapper;
import com.ray.web3.mapper.ConsumerBizMapper;
import com.ray.web3.model.entity.Account;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.vo.DepositEventVO;
import com.ray.web3.service.LedgerAccountService;
import com.ray.web3.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 充值消息消费入账单测（第 3 周 Day 5-6）
 * <p>
 * 覆盖：consumer_biz 幂等短路、userId/currency 解析、账户查找、平台负债账户配置、
 * 以及最终调用 {@link LedgerService#deposit} 的参数口径。
 */
@ExtendWith(MockitoExtension.class)
class DepositCreditServiceTest {

    private static final String USDT = "USDT";
    private static final String TX = "0xaaa1";
    private static final String FROM = "0x95222290dd7278aa3ddd389b1e2d9d2f0f4b0001";
    private static final String TO = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";

    @Mock
    private ConsumerBizMapper consumerBizMapper;
    @Mock
    private AccountMapper accountMapper;
    @Mock
    private LedgerService ledgerService;
    @Mock
    private LedgerAccountService ledgerAccountService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DepositConsumerProperties consumerProperties;
    private DepositCreditService service;

    @BeforeEach
    void setUp() {
        consumerProperties = new DepositConsumerProperties();
        consumerProperties.setPlatformLiabilityAccountId(900101L);
        consumerProperties.setUserAccountType("SPOT");
        service = new DepositCreditService(consumerBizMapper, accountMapper, ledgerService,
                ledgerAccountService, objectMapper, consumerProperties);
    }

    @Test
    void 首次消费应查账户并调用账本入账() throws Exception {
        when(consumerBizMapper.insertIgnore("DP1")).thenReturn(1);
        when(accountMapper.selectByUserTypeCurrency(10001L, "SPOT", USDT)).thenReturn(account(900001L));
        when(ledgerService.deposit(eq("DP1"), eq(900001L), eq(900101L),
                argThat(a -> a != null && a.compareTo(new BigDecimal("100")) == 0), eq(USDT)))
                .thenReturn(entry("LE1"));

        DepositCreditService.CreditResult result = service.credit(payload("10001", USDT, "100"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.depositNo()).isEqualTo("DP1");
        assertThat(result.entryNo()).isEqualTo("LE1");
        verify(ledgerService).deposit(eq("DP1"), eq(900001L), eq(900101L),
                argThat(a -> a != null && a.compareTo(new BigDecimal("100")) == 0), eq(USDT));
    }

    @Test
    void consumer_biz命中主键时直接跳过且不碰账本() throws Exception {
        when(consumerBizMapper.insertIgnore("DP1")).thenReturn(0);

        DepositCreditService.CreditResult result = service.credit(payload("10001", USDT, "100"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.entryNo()).isNull();
        verifyNoInteractions(accountMapper, ledgerService);
    }

    @Test
    void 缺少userId时不能入用户账本() throws Exception {
        when(consumerBizMapper.insertIgnore("DP1")).thenReturn(1);

        assertThatThrownBy(() -> service.credit(payload(null, USDT, "100")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userId");

        verify(ledgerService, never()).deposit(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 用户账户不存在时拒绝入账() throws Exception {
        when(consumerBizMapper.insertIgnore("DP1")).thenReturn(1);
        when(accountMapper.selectByUserTypeCurrency(10001L, "SPOT", USDT)).thenReturn(null);

        assertThatThrownBy(() -> service.credit(payload("10001", USDT, "100")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("用户账户不存在");

        verify(ledgerService, never()).deposit(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 未配置平台负债账户时拒绝入账() throws Exception {
        consumerProperties.setPlatformLiabilityAccountId(0L);
        when(consumerBizMapper.insertIgnore("DP1")).thenReturn(1);
        when(accountMapper.selectByUserTypeCurrency(10001L, "SPOT", USDT)).thenReturn(account(900001L));

        assertThatThrownBy(() -> service.credit(payload("10001", USDT, "100")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform-liability-account-id");

        verifyNoInteractions(ledgerService);
    }

    private Account account(long id) {
        Account account = new Account();
        account.setId(id);
        account.setUserId(10001L);
        account.setAccountType("SPOT");
        account.setCurrency(USDT);
        return account;
    }

    private LedgerEntry entry(String entryNo) {
        LedgerEntry entry = new LedgerEntry();
        entry.setEntryNo(entryNo);
        entry.setBizType("DEPOSIT");
        entry.setBizNo("DP1");
        return entry;
    }

    private String payload(String userId, String currency, String amount) throws Exception {
        DepositEventVO event = new DepositEventVO(
                "DP1", 31337L, "0x5fbdb2315678afecb367f032d93f642f64180aa3",
                currency, TX, 7L, 100L, FROM, TO, "USER", userId,
                amount, "100000000", 6, "PENDING_CONFIRM", 12L,
                "STREAM", LocalDateTime.of(2026, 11, 16, 10, 0));
        return objectMapper.writeValueAsString(event);
    }
}