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
import com.ray.web3.model.ledger.LedgerGroup;
import com.ray.web3.model.ledger.Posting;
import com.ray.web3.service.LedgerService;
import com.ray.web3.util.MoneyUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 复式账本服务实现（第 2 周 Day 3-4：{@code post()}）
 * <p>
 * 五步顺序不可交换，每一步都对应一类真实故障：
 * <ol>
 *   <li><b>借贷平衡校验</b>：不平就抛 {@link UnbalancedLedgerException}，不落库、不自动修正；</li>
 *   <li><b>按 account_id 升序加锁</b>：两个事务交叉更新同一组账户时保证同序，消除死锁；</li>
 *   <li><b>同事务 INSERT ledger_entry → ledger_posting</b>：分录组先行，幂等键撞键即重复投递；</li>
 *   <li><b>逐账户乐观锁回写</b>：{@code WHERE id=? AND version=?}，影响行数 0 即并发冲突，整组回滚；</li>
 *   <li><b>@Transactional(rollbackFor = Exception.class)</b>：任何一步失败全组回滚，不存在“一半入账”。</li>
 * </ol>
 * <p>
 * 幂等边界：{@code INSERT ledger_entry} 命中 {@code uk_biz(biz_type, biz_no)} 说明该业务已记过账，
 * 直接返回原 entry 且不再动余额——这是“重复投递”的唯一正确姿势，比在应用层查一次再写更可靠
 * （查与写之间有竞态，唯一键没有）。
 * <p>
 * 未做（留到后续）：账本独立数据源/连接池隔离、冲正 {@code reverse()}、四类业务场景封装。
 */
@Slf4j
@Service
public class LedgerServiceImpl implements LedgerService {

    /** entry_no 的时间部分：yyyyMMddHHmmssSSS，按时间排序即可还原入账顺序 */
    private static final DateTimeFormatter ENTRY_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 同一毫秒内的序号，6 位回绕（单实例每秒千万级不会撞车） */
    private static final AtomicLong ENTRY_NO_SEQ = new AtomicLong();

    private final AccountMapper accountMapper;
    private final LedgerEntryMapper ledgerEntryMapper;
    private final LedgerPostingMapper ledgerPostingMapper;

    /** 资损告警信号：借贷不平被拦下的次数，非 0 就要人工介入，不能靠重试消化 */
    private final Counter unbalancedCounter;

    public LedgerServiceImpl(AccountMapper accountMapper,
                             LedgerEntryMapper ledgerEntryMapper,
                             LedgerPostingMapper ledgerPostingMapper,
                             MeterRegistry meterRegistry) {
        this.accountMapper = accountMapper;
        this.ledgerEntryMapper = ledgerEntryMapper;
        this.ledgerPostingMapper = ledgerPostingMapper;
        this.unbalancedCounter = Counter.builder("ledger.unbalanced")
                .description("借贷不平被拦截的分录组数（资损告警）")
                .register(meterRegistry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LedgerEntry post(LedgerGroup group) {
        Objects.requireNonNull(group, "分录组不能为空");

        // ① 借贷平衡：不平直接抛，连 ledger_entry 都不插
        validateBalanced(group);

        // ③ 先插分录组：uk_biz 是幂等的最终防线，撞键说明重复投递
        LedgerEntry entry = buildEntry(group);
        try {
            ledgerEntryMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            LedgerEntry existing = ledgerEntryMapper.selectByBiz(group.bizType(), group.bizNo());
            if (existing == null) {
                // 撞的不是 uk_biz（理论上不会发生），原样抛出，让事务回滚
                throw e;
            }
            log.info("[ledger] 幂等命中，跳过重复入账 bizType={} bizNo={} entryNo={}",
                    group.bizType(), group.bizNo(), existing.getEntryNo());
            return existing;
        }

        // ② 锁定涉及的账户，按 account_id 升序（跨事务同序加锁，防死锁）
        Map<Long, Account> accounts = lockAccounts(group.postings());

        // 同一子账的多条分录按调用方原始顺序累计，balance_after 才有“本笔操作后”的语义
        Map<Long, BigDecimal> availableBalances = new HashMap<>();
        Map<Long, BigDecimal> frozenBalances = new HashMap<>();
        List<LedgerPosting> rows = new ArrayList<>(group.postings().size());
        for (Posting posting : group.postings()) {
            Account account = accounts.get(posting.accountId());
            requireSameCurrency(account, posting);

            Map<Long, BigDecimal> balances = posting.balanceType() == BalanceType.AVAILABLE
                    ? availableBalances : frozenBalances;
            BigDecimal current = balances.computeIfAbsent(posting.accountId(),
                    id -> currentBalance(account, posting.balanceType()));

            BigDecimal after = posting.direction().apply(current, posting.amount());
            if (after.signum() < 0) {
                throw new LedgerPostingException("子账余额不足，拒绝记账: accountId=" + posting.accountId()
                        + ", balanceType=" + posting.balanceType() + ", currency=" + posting.currency()
                        + ", 当前=" + current.toPlainString() + ", 本笔=" + posting.direction()
                        + " " + posting.amount().toPlainString());
            }
            balances.put(posting.accountId(), after);
            rows.add(toRow(entry.getId(), posting, after));
        }

        // ③ 续：明细一次落库
        ledgerPostingMapper.insertBatch(rows);

        // ④ 乐观锁回写：影响行数 0 = 期间被并发事务改过（理论上已被 FOR UPDATE 挡住，留作最后防线）
        for (Account account : accounts.values()) {
            BigDecimal available = MoneyUtils.normalize(availableBalances.getOrDefault(
                    account.getId(), nullToZero(account.getAvailable())));
            BigDecimal frozen = MoneyUtils.normalize(frozenBalances.getOrDefault(
                    account.getId(), nullToZero(account.getFrozen())));
            Long version = account.getVersion() == null ? 0L : account.getVersion();
            int updated = accountMapper.updateBalance(account.getId(), available, frozen, version);
            if (updated == 0) {
                throw new LedgerPostingException("账户余额乐观锁冲突，整组回滚: accountId="
                        + account.getId() + ", version=" + version);
            }
        }

        log.info("[ledger] 记账成功 bizType={} bizNo={} entryNo={} postings={}",
                group.bizType(), group.bizNo(), entry.getEntryNo(), rows.size());
        return entry;
    }

    /**
     * ① 按币种分别校验 {@code sum(CREDIT) == sum(CREDIT)}；跨币种不能互相抵平。
     * 失败只做两件事：打资损告警 + 抛异常，<b>绝不尝试修正</b>。
     */
    private void validateBalanced(LedgerGroup group) {
        Map<String, BigDecimal> netByCurrency = new LinkedHashMap<>();
        for (Posting posting : group.postings()) {
            netByCurrency.merge(posting.currency(),
                    posting.direction().signed(posting.amount()), BigDecimal::add);
        }
        List<String> unbalanced = netByCurrency.entrySet().stream()
                .filter(e -> e.getValue().signum() != 0)
                .map(e -> e.getKey() + "=" + e.getValue().toPlainString())
                .toList();
        if (!unbalanced.isEmpty()) {
            unbalancedCounter.increment();
            log.error("[ledger][资损告警] 借贷不平，拒绝落库 bizType={} bizNo={} 差额={}",
                    group.bizType(), group.bizNo(), unbalanced);
            throw new UnbalancedLedgerException("分录借贷不平，拒绝落库: bizType=" + group.bizType()
                    + ", bizNo=" + group.bizNo() + ", 差额=" + unbalanced);
        }
    }

    /**
     * ② 按 account_id 升序逐行加锁（同 id 只锁一次）。
     * 返回的 LinkedHashMap 保持加锁顺序，后续回写也按同一顺序进行。
     */
    private Map<Long, Account> lockAccounts(List<Posting> postings) {
        List<Long> accountIds = postings.stream()
                .map(Posting::accountId)
                .distinct()
                .sorted()
                .toList();
        Map<Long, Account> accounts = new LinkedHashMap<>(accountIds.size());
        for (Long accountId : accountIds) {
            Account account = accountMapper.selectByIdForUpdate(accountId);
            if (account == null) {
                throw new LedgerPostingException("账户不存在: accountId=" + accountId);
            }
            accounts.put(accountId, account);
        }
        return accounts;
    }

    private static void requireSameCurrency(Account account, Posting posting) {
        if (account.getCurrency() == null || !account.getCurrency().equalsIgnoreCase(posting.currency())) {
            throw new LedgerPostingException("分录币种与账户币种不一致: accountId=" + posting.accountId()
                    + ", account=" + account.getCurrency() + ", posting=" + posting.currency());
        }
    }

    private static BigDecimal currentBalance(Account account, BalanceType balanceType) {
        BigDecimal raw = balanceType == BalanceType.AVAILABLE ? account.getAvailable() : account.getFrozen();
        return MoneyUtils.normalize(nullToZero(raw));
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static LedgerEntry buildEntry(LedgerGroup group) {
        LedgerEntry entry = new LedgerEntry();
        // entry_no 不与业务单号绑定：biz_no 最长 128 字符，且同一 biz_no 可能需要冲正组。
        // 用「时间戳 + 同毫秒序号」而不是 UUID：按单号排序就是入账顺序，排查时能一眼看出时间。
        // 单实例内唯一由序号保证；跨实例同一毫秒撞号时由 uk_entry_no 兜底（重复即整组回滚，不会记错账）。
        entry.setEntryNo(String.format("LE%s%06d",
                LocalDateTime.now().format(ENTRY_NO_TIME),
                Math.floorMod(ENTRY_NO_SEQ.getAndIncrement(), 1_000_000)));
        entry.setBizType(group.bizType());
        entry.setBizNo(group.bizNo());
        entry.setStatus(LedgerConsts.EntryStatus.SUCCESS);
        return entry;
    }

    private static LedgerPosting toRow(Long entryId, Posting posting, BigDecimal balanceAfter) {
        LedgerPosting row = new LedgerPosting();
        row.setEntryId(entryId);
        row.setAccountId(posting.accountId());
        row.setDirection(posting.direction().name());
        row.setAmount(posting.amount());
        row.setCurrency(posting.currency());
        row.setBalanceType(posting.balanceType().name());
        row.setBalanceAfter(balanceAfter);
        return row;
    }
}
