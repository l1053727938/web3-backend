package com.ray.web3.service.impl;

import com.ray.web3.config.WithdrawProperties;
import com.ray.web3.mapper.WithdrawRecordMapper;
import com.ray.web3.model.WithdrawConsts;
import com.ray.web3.model.entity.WithdrawRecord;
import com.ray.web3.service.NonceManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提币追踪状态机推进单元测试：重点验证 <b>确认数门槛对 SUCCESS 与 FAILED 对称</b>（防 FAILED 浅块误判双花）
 * <p>
 * 全部用 Mockito 伪造节点返回的收据与链高，不发真实 RPC：状态判定是纯逻辑，
 * 且"浅块 Revert 不能写 FAILED"这类结论只有在可控的高度参数下才能精确复现
 */
@ExtendWith(MockitoExtension.class)
class WithdrawTrackingServiceImplTest {

    private static final String WITHDRAW_NO = "WD20261103000001";
    private static final String TX_HASH = "0xaaaa";
    // 地址必须全是合法 hex：重放取因会用它重建 calldata，非法字符会触发编码异常从而退到通用文案
    private static final String HOT_WALLET = "0x1111111111111111111111111111111111111111";
    private static final String CONTRACT = "0x2222222222222222222222222222222222222222";
    private static final String TO = "0x3333333333333333333333333333333333333333";

    @Mock
    private Web3j web3j;

    @Mock
    private WithdrawRecordMapper withdrawRecordMapper;

    @Mock
    private WithdrawProperties withdrawProperties;

    @Mock
    private NonceManagerService nonceManagerService;

    private WithdrawTrackingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WithdrawTrackingServiceImpl(web3j, withdrawRecordMapper, withdrawProperties, nonceManagerService);
        // 批次上限与卡单阈值对所有用例生效，但只有"未落终态"的用例才会读到，故用 lenient 避开 strict stubs 告警
        lenient().when(withdrawProperties.getTrackBatchSize()).thenReturn(20);
        lenient().when(withdrawProperties.getTrackStuckTimeoutMs()).thenReturn(600_000L);
    }

    // ========== FAILED 对称门槛 ==========

    /** 浅块 Revert 只写 BROADCAST：确认不足时必须不碰 FAILED，否则重组后"链上付出 + 账本退款"就是双花 */
    @Test
    void shallowRevertWithInsufficientConfirmations_keepsBroadcastAndNeverMarksFailed() throws Exception {
        stubConfirmations(12);
        // 中块 100、链高 105 → 仅 6 个确认
        stubReceipt(receipt("0x0", 100, null));
        stubLatestBlock(105);

        assertThat(service.trackPendingBroadcasts()).isZero();
        verify(withdrawRecordMapper, never()).markReverted(anyString(), anyString());
        verify(withdrawRecordMapper, never()).markSuccess(anyString());
        // 确认不足仍要走心跳，否则这笔会永久霸占 selectByStatus 队头把新交易饿死
        verify(withdrawRecordMapper).touchBroadcast(WITHDRAW_NO);
    }

    /** 确认达标的 Revert 才落 FAILED，原因文本取收据里的 revertReason */
    @Test
    void revertedWithEnoughConfirmations_marksFailedAndNotSuccess() throws Exception {
        stubConfirmations(12);
        stubReceipt(receipt("0x0", 100, "ERC20: transfer amount exceeds balance"));
        stubLatestBlock(112); // 13 个确认
        when(withdrawRecordMapper.markReverted(eq(WITHDRAW_NO), anyString())).thenReturn(1);

        assertThat(service.trackPendingBroadcasts()).isEqualTo(1);
        verify(withdrawRecordMapper).markReverted(WITHDRAW_NO, "ERC20: transfer amount exceeds balance");
        verify(withdrawRecordMapper, never()).markSuccess(anyString());
    }

    /** 达标的成功交易照常转 SUCCESS（回归：门槛前移不能动原有成功路径） */
    @Test
    void succeededWithEnoughConfirmations_marksSuccess() throws Exception {
        stubConfirmations(12);
        stubReceipt(receipt("0x1", 100, null));
        stubLatestBlock(112);
        when(withdrawRecordMapper.markSuccess(WITHDRAW_NO)).thenReturn(1);

        assertThat(service.trackPendingBroadcasts()).isEqualTo(1);
        verify(withdrawRecordMapper, never()).markReverted(anyString(), anyString());
    }

    /** 重组把链高顶回去（minedBlock &gt; latest）：确认数为负一律判不足，不能落任何终态 */
    @Test
    void blockHeightRolledBackByReorg_keepsBroadcast() throws Exception {
        stubConfirmations(12);
        stubReceipt(receipt("0x0", 200, "boom"));
        stubLatestBlock(195);

        assertThat(service.trackPendingBroadcasts()).isZero();
        verify(withdrawRecordMapper, never()).markReverted(anyString(), anyString());
    }

    /** 取不到链高（节点异常）：确认数无法判定就不落终态，与"RPC 失败不猜测"同一条原则 */
    @Test
    void latestBlockUnavailable_keepsBroadcast() throws Exception {
        stubConfirmations(12);
        stubReceipt(receipt("0x0", 100, "boom"));
        Request<?, EthBlockNumber> numberRequest = mock(Request.class);
        doThrow(new RuntimeException("connection reset")).when(numberRequest).send();
        doReturn(numberRequest).when(web3j).ethBlockNumber();

        assertThat(service.trackPendingBroadcasts()).isZero();
        verify(withdrawRecordMapper, never()).markReverted(anyString(), anyString());
        verify(withdrawRecordMapper).touchBroadcast(WITHDRAW_NO);
    }

    /** {@code track-confirmations=0} 时不判定：测试链复现仍需"Revert 即时 FAILED"，这条守住该退路 */
    @Test
    void zeroConfirmationsConfigured_marksFailedImmediately() throws Exception {
        stubConfirmations(0);
        stubReceipt(receipt("0x0", 1, "boom"));
        when(withdrawRecordMapper.markReverted(eq(WITHDRAW_NO), anyString())).thenReturn(1);

        assertThat(service.trackPendingBroadcasts()).isEqualTo(1);
        verify(withdrawRecordMapper).markReverted(WITHDRAW_NO, "boom");
    }

    // ========== Revert 取因 ==========

    /** 收据不带原因时用 eth_call 重放取因，并剔掉节点通用前缀 {@code execution reverted:} 只留合约原文 */
    @Test
    void revertWithoutReasonOnReceipt_fallsBackToEthCallReplay() throws Exception {
        stubConfirmations(1);
        stubReceipt(receipt("0x0", 100, null));
        stubLatestBlock(100);
        stubEthCall(true, "execution reverted:  ERC20: insufficient balance");
        when(withdrawRecordMapper.markReverted(eq(WITHDRAW_NO), anyString())).thenReturn(1);

        service.trackPendingBroadcasts();

        verify(withdrawRecordMapper).markReverted(WITHDRAW_NO, "ERC20: insufficient balance");
    }

    /** 重放也拿不到原因：仍照写 FAILED 并退通用文案，不能因为"缺原因"把已知失败的交易吊住 */
    @Test
    void revertReasonUnavailable_everywhere_stillMarksFailedWithGenericText() throws Exception {
        stubConfirmations(1);
        stubReceipt(receipt("0x0", 100, null));
        stubLatestBlock(100);
        stubEthCall(false, null); // 后续区块已改变合约状态，本次失败无法复现
        when(withdrawRecordMapper.markReverted(eq(WITHDRAW_NO), anyString())).thenReturn(1);

        service.trackPendingBroadcasts();

        verify(withdrawRecordMapper).markReverted(eq(WITHDRAW_NO), argThat(msg -> msg.contains("status=0x0")));
    }

    // ========== 辅助 ==========

    private void stubConfirmations(long required) {
        when(withdrawProperties.getTrackConfirmations()).thenReturn(required);
    }

    /** 伪造一笔 BROADCAST 记录的收据查询，并让它进入本轮扫描批次 */
    private void stubReceipt(TransactionReceipt receipt) throws Exception {
        EthGetTransactionReceipt response = mock(EthGetTransactionReceipt.class);
        when(response.getTransactionReceipt()).thenReturn(Optional.of(receipt));
        Request<?, EthGetTransactionReceipt> request = mock(Request.class);
        doReturn(response).when(request).send();
        doReturn(request).when(web3j).ethGetTransactionReceipt(TX_HASH);
        when(withdrawRecordMapper.selectByStatus(eq(WithdrawConsts.Status.BROADCAST), anyInt()))
                .thenReturn(List.of(broadcastRecord()));
    }

    private void stubLatestBlock(long latest) throws Exception {
        EthBlockNumber numberResponse = mock(EthBlockNumber.class);
        when(numberResponse.getBlockNumber()).thenReturn(BigInteger.valueOf(latest));
        Request<?, EthBlockNumber> numberRequest = mock(Request.class);
        doReturn(numberResponse).when(numberRequest).send();
        doReturn(numberRequest).when(web3j).ethBlockNumber();
    }

    /** 伪造 eth_call 重放返回（只读取因，不影响状态判定本身） */
    private void stubEthCall(boolean reverted, String reason) throws Exception {
        EthCall ethCall = mock(EthCall.class);
        when(ethCall.isReverted()).thenReturn(reverted);
        lenient().when(ethCall.getRevertReason()).thenReturn(reason);
        Request<?, EthCall> callRequest = mock(Request.class);
        doReturn(ethCall).when(callRequest).send();
        doReturn(callRequest).when(web3j).ethCall(any(), any());
    }

    private TransactionReceipt receipt(String status, long blockNumber, String revertReason) {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus(status);
        // web3j 内部存 hex 字符串，getBlockNumber() 再解回 BigInteger：直接 set 十进制会解出错误高度
        receipt.setBlockNumber(Numeric.encodeQuantity(BigInteger.valueOf(blockNumber)));
        receipt.setRevertReason(revertReason);
        return receipt;
    }

    private WithdrawRecord broadcastRecord() {
        WithdrawRecord record = new WithdrawRecord();
        record.setWithdrawNo(WITHDRAW_NO);
        record.setStatus(WithdrawConsts.Status.BROADCAST);
        record.setTransactionHash(TX_HASH);
        record.setNonce(BigInteger.valueOf(7));
        record.setFromAddress(HOT_WALLET);
        record.setContractAddress(CONTRACT);
        record.setToAddress(TO);
        record.setRawValue("1000000000000000000");
        record.setGasPrice(BigInteger.valueOf(20_000_000_000L));
        record.setGasLimit(BigInteger.valueOf(60_000));
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }
}
