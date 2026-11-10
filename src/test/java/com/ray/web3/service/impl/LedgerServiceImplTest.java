package com.ray.web3.service.impl;

import com.ray.web3.exception.LedgerPostingException;
import com.ray.web3.exception.UnbalancedLedgerException;
import com.ray.web3.mapper.AccountMapper;
import com.ray.web3.mapper.LedgerEntryMapper;
import com.ray.web3.mapper.LedgerPostingMapper;
import com.ray.web3.consts.LedgerConsts;
import com.ray.web3.model.entity.Account;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.entity.LedgerPosting;
import com.ray.web3.enums.BalanceType;
import com.ray.web3.enums.Direction;
import com.ray.web3.model.ledger.LedgerGroup;
import com.ray.web3.model.ledger.Posting;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 账本 {@code post()} 离线单测（第 2 周 Day 3-4）：全部 Mock，不连 MySQL
 * <p>
 * 重点验证两件事：
 * <ul>
 *   <li>借贷不平<b>不落库</b>——一条 SQL 都不该发出去；</li>
 *   <li>幂等命中 {@code uk_biz} 时<b>不再动余额</b>——重复投递不能二次扣款。</li>
 * </ul>
 * 另外覆盖加锁顺序（account_id 升序）与余额不足拦截。
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceImplTest {

    private static final String USDT = "USDT";

    @Mock
    private AccountMapper accountMapper;
    @Mock
    private LedgerEntryMapper ledgerEntryMapper;
    @Mock
    private LedgerPostingMapper ledgerPostingMapper;

    private SimpleMeterRegistry meterRegistry;
    private LedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new LedgerServiceImpl(accountMapper, ledgerEntryMapper, ledgerPostingMapper, meterRegistry);
    }

    @Test
    void 借贷不平应抛异常且不落库() {
        LedgerGroup group = new LedgerGroup(LedgerConsts.BizType.TRANSFER, "TR-1", List.of(
                posting(1L, Direction.DEBIT, "100"),
                posting(1L, Direction.CREDIT, "99")));

        assertThatThrownBy(() -> service.post(group))
                .isInstanceOf(UnbalancedLedgerException.class)
                .hasMessageContaining("借贷不平");

        // 校验在事务的最前面：entry / posting / 余额更新一条都不能发出
        verifyNoInteractions(ledgerEntryMapper, ledgerPostingMapper, accountMapper);
        // 资损告警指标必须 +1，非 0 即需人工介入
        assertThat(meterRegistry.get("ledger.unbalanced").counter().count()).isEqualTo(1.0);
    }

    @Test
    void 平衡分录应落两组明细并按版本号回写余额() {
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(account(1L, "100", "0", 7L));
        when(accountMapper.selectByIdForUpdate(2L)).thenReturn(account(2L, "50", "0", 3L));
        when(ledgerEntryMapper.insert(any(LedgerEntry.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, LedgerEntry.class).setId(42L);
            return 1;
        });
        when(ledgerPostingMapper.insertBatch(anyList())).thenReturn(2);
        when(accountMapper.updateBalance(any(), any(), any(), any())).thenReturn(1);

        // 故意把 account 2 的分录写在前面：加锁仍必须按 account_id 升序
        LedgerGroup group = new LedgerGroup(LedgerConsts.BizType.TRANSFER, "TR-2", List.of(
                posting(2L, Direction.CREDIT, "30"),
                posting(1L, Direction.DEBIT, "30")));

        LedgerEntry entry = service.post(group);

        assertThat(entry.getBizNo()).isEqualTo("TR-2");
        assertThat(entry.getStatus()).isEqualTo(LedgerConsts.EntryStatus.SUCCESS);

        InOrder lockOrder = inOrder(accountMapper);
        lockOrder.verify(accountMapper).selectByIdForUpdate(1L);
        lockOrder.verify(accountMapper).selectByIdForUpdate(2L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPosting>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerPostingMapper).insertBatch(rowsCaptor.capture());
        List<LedgerPosting> rows = rowsCaptor.getValue();
        assertThat(rows).hasSize(2);
        // 明细顺序与入参一致：先 account 2（50 -> 80），再 account 1（100 -> 70）
        assertThat(rows.get(0).getEntryId()).isEqualTo(42L);
        assertThat(rows.get(0).getAccountId()).isEqualTo(2L);
        assertThat(rows.get(0).getBalanceAfter()).isEqualByComparingTo("80");
        assertThat(rows.get(1).getAccountId()).isEqualTo(1L);
        assertThat(rows.get(1).getBalanceAfter()).isEqualByComparingTo("70");

        verify(accountMapper).updateBalance(eq(1L), amount("70"), amount("0"), eq(7L));
        verify(accountMapper).updateBalance(eq(2L), amount("80"), amount("0"), eq(3L));
    }

    @Test
    void uk_biz命中时应返回原分录且不再动余额() {
        LedgerEntry existing = new LedgerEntry();
        existing.setId(99L);
        existing.setEntryNo("LE-existing");
        existing.setBizType(LedgerConsts.BizType.TRANSFER);
        existing.setBizNo("TR-3");

        when(ledgerEntryMapper.insert(any(LedgerEntry.class)))
                .thenThrow(new DuplicateKeyException("uk_biz duplicated"));
        when(ledgerEntryMapper.selectByBiz(LedgerConsts.BizType.TRANSFER, "TR-3")).thenReturn(existing);

        LedgerEntry result = service.post(new LedgerGroup(LedgerConsts.BizType.TRANSFER, "TR-3", List.of(
                posting(1L, Direction.DEBIT, "30"),
                posting(2L, Direction.CREDIT, "30"))));

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(accountMapper);
        verify(ledgerPostingMapper, never()).insertBatch(anyList());
    }

    @Test
    void 余额不足应拦截且不写明细不回写余额() {
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(account(1L, "10", "0", 1L));
        when(accountMapper.selectByIdForUpdate(2L)).thenReturn(account(2L, "0", "0", 1L));
        when(ledgerEntryMapper.insert(any(LedgerEntry.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, LedgerEntry.class).setId(7L);
            return 1;
        });

        LedgerGroup group = new LedgerGroup(LedgerConsts.BizType.TRANSFER, "TR-4", List.of(
                posting(1L, Direction.DEBIT, "30"),
                posting(2L, Direction.CREDIT, "30")));

        assertThatThrownBy(() -> service.post(group))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("余额不足");

        verify(ledgerPostingMapper, never()).insertBatch(anyList());
        verify(accountMapper, never()).updateBalance(any(), any(), any(), any());
    }

    @Test
    void 分录币种与账户币种不一致应拦截() {
        // account 2 是 ETH 账户，却收到一条 USDT 分录；整组按 USDT 来看是平的，
        // 所以能走到账户校验这一层，验证不会把 USDT 记进 ETH 账户
        when(accountMapper.selectByIdForUpdate(1L)).thenReturn(account(1L, "100", "0", 1L));
        when(accountMapper.selectByIdForUpdate(2L)).thenReturn(account(2L, "100", "0", 1L, "ETH"));
        when(ledgerEntryMapper.insert(any(LedgerEntry.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, LedgerEntry.class).setId(8L);
            return 1;
        });

        LedgerGroup group = new LedgerGroup(LedgerConsts.BizType.TRANSFER, "TR-5", List.of(
                posting(1L, Direction.DEBIT, "30"),
                posting(2L, Direction.CREDIT, "30")));

        assertThatThrownBy(() -> service.post(group))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessageContaining("币种");

        verify(ledgerPostingMapper, never()).insertBatch(anyList());
        verify(accountMapper, never()).updateBalance(any(), any(), any(), any());
    }

    private static Posting posting(long accountId, Direction direction, String amount) {
        return new Posting(accountId, direction, new BigDecimal(amount), USDT, BalanceType.AVAILABLE);
    }

    private static Account account(long id, String available, String frozen, long version) {
        return account(id, available, frozen, version, USDT);
    }

    private static Account account(long id, String available, String frozen, long version, String currency) {
        Account account = new Account();
        account.setId(id);
        account.setUserId(1000L + id);
        account.setAccountType("SPOT");
        account.setCurrency(currency);
        account.setAvailable(new BigDecimal(available));
        account.setFrozen(new BigDecimal(frozen));
        account.setVersion(version);
        return account;
    }

    private static BigDecimal amount(String value) {
        return argThat(actual -> actual != null && actual.compareTo(new BigDecimal(value)) == 0);
    }
}
