package com.ray.web3.service.impl;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.mapper.WithdrawRecordMapper;
import com.ray.web3.model.WithdrawConsts;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.Erc20WithdrawService;
import com.ray.web3.service.WithdrawAccelerateService;
import com.ray.web3.service.WithdrawTrackingService;
import com.ray.web3.service.WithdrawTrackingService.StuckState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提币卡单自动加速实现（第 3 周 Day 5-7：Replace-by-fee）
 * <p>
 * 职责边界刻意切得很窄：<b>本类只决定"该不该重播"，怎么重播（复现字段、提价、签名、广播、回填）
 * 全部交给执行层 {@link Erc20WithdrawService}</b>——私钥与签名逻辑因此只有一处出口，加速与首广播
 * 不可能走出两套签名口径。
 * <p>
 * 三条不变式：
 * <ol>
 *   <li><b>绝不因加速而多提一笔币</b>：重播用的是本行已占的同一个 Nonce，且转账字段与原交易逐字节一致，
 *       同 Nonce 决定两笔只能活一笔；</li>
 *   <li><b>绝不因加速而回拨 Nonce</b>：该值已被本行提币占用，退回游标等于把它再分配给别人 = 双花
 *       （与首广播"确定性拒绝即回拨"的规则相反，原因见执行层注释）；</li>
 *   <li><b>结果不确定就不猜终态</b>：诊断问不到成因、广播超时/被拒，一律保持 BROADCAST 留给下一轮。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "web3.withdraw", name = "enabled", havingValue = "true")
public class WithdrawAccelerateServiceImpl implements WithdrawAccelerateService {

    private final WithdrawRecordMapper withdrawRecordMapper;
    private final WithdrawProperties withdrawProperties;
    /** 复用追踪层的成因判定，避免同一套"查交易 + 查 nonce 消耗"逻辑在两处漂移 */
    private final WithdrawTrackingService withdrawTrackingService;
    private final Erc20WithdrawService erc20WithdrawService;

    @Override
    public int accelerateStuckBroadcasts() {
        // 卡单门槛沿用 track-stuck-timeout-ms：只处理年龄超阈的记录，刚广播的正常排队交易不碰
        // （LocalDateTime 没有 minusMillis，走 Duration 减法）
        LocalDateTime createdBefore = LocalDateTime.now()
                .minus(Duration.ofMillis(withdrawProperties.getTrackStuckTimeoutMs()));
        List<WithdrawRecord> batch = withdrawRecordMapper.selectStuck(
                WithdrawConsts.Status.BROADCAST, createdBefore, withdrawProperties.getAccelerateBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }
        int acted = 0;
        for (WithdrawRecord record : batch) {
            try {
                if (handleOne(record)) {
                    acted++;
                }
            } catch (Exception e) {
                // 单条失败不影响整轮：状态仍是 BROADCAST、次数也没加，下一轮按同样成因重来
                log.warn("[withdraw-accelerate] 单条处置失败，保持 BROADCAST withdrawNo={} : {}",
                        record.getWithdrawNo(), e.getMessage());
            }
        }
        log.debug("[withdraw-accelerate] 本轮扫描卡单={} 采取动作={}", batch.size(), acted);
        return acted;
    }

    /**
     * 单笔处置：先过次数预算，再按链上成因分流。返回是否真的动过库/链（动过才计入观测）
     */
    private boolean handleOne(WithdrawRecord record) {
        int used = record.getAccelerateCount() == null ? 0 : record.getAccelerateCount();
        int max = withdrawProperties.getAccelerateMaxCount();
        if (used >= max) {
            return escalate(record, used, "已达自动加速上限");
        }

        // 每轮重新诊断、不复用上次的标注文本：加速会清空 error_message（旧结论对新交易已失效），
        // 且成因随时在变（排队→被丢弃→被替换），拿陈旧结论决策会走错分支
        StuckState state = withdrawTrackingService.diagnoseStuck(record.getWithdrawNo());
        if (state == null) {
            return false;
        }
        return switch (state) {
            // 排队（价格不够看）与被节点踢掉（出价已过期）：两者都靠"同 nonce 提价重播"推进，动作一致
            case QUEUED, DROPPED -> {
                log.info("[withdraw-accelerate] 卡单提价重播 withdrawNo={} 成因={} nonce={} 已加速={} 次 tx={}",
                        record.getWithdrawNo(), state, record.getNonce(), used, record.getTransactionHash());
                erc20WithdrawService.accelerateWithdraw(record.getWithdrawNo());
                yield true;
            }
            // 已中块、只是确认数未达标：正常等待，此时提价重播会撞 nonce too low，纯浪费
            case AWAITING_CONFIRMATION, NODE_UNAVAILABLE -> false;
            // Nonce 已被别的交易消耗而本笔交易在节点视野里查不到：见方法注释，只标注不作废
            case REPLACED -> escalate(record, used,
                    "疑似被同 nonce 的其他交易覆盖（nonce=" + record.getNonce() + " 已消耗）");
        };
    }

    /**
     * 升级到人工：写一次性标注（{@code error_message IS NULL} 条件保证不重复刷库），不动状态
     * <p>
     * {@code REPLACED} 之所以不在这里自动落库：作废一笔提币带资金语义（要触发解冻/退款），
     * 不能建立在"单次节点视图 + 可能滞后的 pending 计数"上——多节点路由下 {@code eth_getTransactionByHash}
     * 与 {@code eth_getTransactionCount} 可能来自不同后端，视图不一致会误判。真正的 REPLACED 由
     * 人工撤销（{@code Erc20WithdrawService#cancelWithdraw}）或第 6-7 周对账落定
     */
    private boolean escalate(WithdrawRecord record, int used, String cause) {
        String note = "卡单待人工：" + cause + " 已加速=" + used + " nonce=" + record.getNonce()
                + " tx=" + record.getTransactionHash();
        int marked = withdrawRecordMapper.markStuck(record.getWithdrawNo(), note);
        if (marked > 0) {
            log.error("[withdraw-accelerate] 自动处置止步，转人工 withdrawNo={} {}", record.getWithdrawNo(), note);
        }
        return marked > 0;
    }
}
