package com.ray.web3.service.impl;

import com.ray.web3.config.DepositConsumerProperties;
import com.ray.web3.ledger.mapper.AccountMapper;
import com.ray.web3.mapper.ConsumerBizMapper;
import com.ray.web3.model.entity.Account;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.vo.DepositEventVO;
import com.ray.web3.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

/**
 * 充值消息消费入账（第 3 周 Day 5-6）
 * <p>
 * Kafka 消费者拿到的是 Outbox 投递的 {@link DepositEventVO} JSON，本服务负责：
 * <ol>
 *   <li><b>幂等占位</b>：先 {@code INSERT IGNORE consumer_biz(biz_no)}；返回 0 说明重复投递，直接跳过；</li>
 *   <li><b>账户解析</b>：按 payload 的 userId + currency 查用户 SPOT 账户，平台代收负债账户取配置；</li>
 *   <li><b>账本入账</b>：调用 {@link LedgerService#deposit}，账本本身以 depositNo 为幂等键。</li>
 * </ol>
 * <p>
 * <b>事务与顺序</b>：consumer_biz 写入在业务库事务内，账本写入在账本库事务内（两个事务管理器）。
 * 先插幂等表再调账本：账本失败会抛异常，业务事务回滚 consumer_biz，Kafka 不 ack，消息重投；
 * 账本成功但业务提交失败时，重投会再次调用账本，而账本按 {@code uk_biz(DEPOSIT, depositNo)}
 * 幂等返回原分录，不会重复加钱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositCreditService {

    private final ConsumerBizMapper consumerBizMapper;
    private final AccountMapper accountMapper;
    private final LedgerService ledgerService;
    private final ObjectMapper objectMapper;
    private final DepositConsumerProperties consumerProperties;

    /**
     * 消费一条充值消息并记入账本；重复消息返回 {@code duplicate=true}。
     *
     * @throws RuntimeException 解析失败、账户不存在、账本入账失败等；调用方不得 ack
     */
    @Transactional(rollbackFor = Exception.class)
    public CreditResult credit(String payload) {
        DepositEventVO event = parse(payload);

        // 1. 幂等占位：命中主键 = 同一笔充值已消费过，直接跳过（调用方 ack）
        if (consumerBizMapper.insertIgnore(event.depositNo()) == 0) {
            return new CreditResult(event.depositNo(), null, true);
        }

        // 2. 账户解析
        long userAccountId = resolveUserAccountId(event);
        long platformLiabilityAccountId = requirePlatformLiabilityAccountId();
        BigDecimal amount = parseAmount(event);

        // 3. 账本入账（独立事务管理器；depositNo 是账本幂等键）
        LedgerEntry entry = ledgerService.deposit(event.depositNo(), userAccountId,
                platformLiabilityAccountId, amount, event.currency());
        log.info("[consumer] 账本入账成功 depositNo={} entryNo={} userAccountId={} amount={} {}",
                event.depositNo(), entry.getEntryNo(), userAccountId,
                amount.toPlainString(), event.currency());
        return new CreditResult(event.depositNo(), entry.getEntryNo(), false);
    }

    private DepositEventVO parse(String payload) {
        DepositEventVO event;
        try {
            event = objectMapper.readValue(payload, DepositEventVO.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("充值消息解析失败", e);
        }
        if (event == null || event.depositNo() == null || event.depositNo().isBlank()) {
            throw new IllegalArgumentException("充值消息缺少 depositNo");
        }
        return event;
    }

    private long resolveUserAccountId(DepositEventVO event) {
        if (event.userId() == null || event.userId().isBlank()) {
            // HOT 钱包是平台归集地址，没有归属用户，不能直接记到某个用户账本
            throw new IllegalStateException("充值消息缺少 userId，HOT 钱包不能直接入用户账本: depositNo="
                    + event.depositNo());
        }
        long userId;
        try {
            userId = Long.parseLong(event.userId().trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("充值消息 userId 不是数字: " + event.userId(), e);
        }
        if (event.currency() == null || event.currency().isBlank()) {
            throw new IllegalStateException("充值消息缺少 currency: depositNo=" + event.depositNo());
        }
        Account account = accountMapper.selectByUserTypeCurrency(
                userId, consumerProperties.getUserAccountType(), event.currency());
        if (account == null) {
            throw new IllegalStateException("用户账户不存在: userId=" + userId
                    + ", type=" + consumerProperties.getUserAccountType()
                    + ", currency=" + event.currency());
        }
        return account.getId();
    }

    private long requirePlatformLiabilityAccountId() {
        long id = consumerProperties.getPlatformLiabilityAccountId();
        if (id <= 0) {
            throw new IllegalStateException(
                    "未配置 web3.deposit.consumer.platform-liability-account-id");
        }
        return id;
    }

    private static BigDecimal parseAmount(DepositEventVO event) {
        if (event.amount() == null || event.amount().isBlank()) {
            throw new IllegalStateException("充值消息缺少 amount: depositNo=" + event.depositNo());
        }
        BigDecimal amount = new BigDecimal(event.amount());
        if (amount.signum() <= 0) {
            throw new IllegalStateException("充值金额必须大于 0: depositNo=" + event.depositNo()
                    + ", amount=" + event.amount());
        }
        return amount;
    }

    /**
     * @param depositNo 充值单号（消费幂等键）
     * @param entryNo   账本分录号；重复消息为 null
     * @param duplicate 是否命中 consumer_biz 幂等表
     */
    public record CreditResult(String depositNo, String entryNo, boolean duplicate) {
    }
}