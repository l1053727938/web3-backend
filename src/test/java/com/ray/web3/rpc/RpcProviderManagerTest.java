package com.ray.web3.rpc;

import io.reactivex.Flowable;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.BatchResponse;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.exceptions.ClientConnectionException;
import org.web3j.protocol.websocket.events.Notification;

import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RPC 主备容灾单测（第 4 周 Day 5-6 余量）
 * <p>
 * 用假 {@link Web3jService} 模拟主节点 429/超时：断言同一请求自动切到备用节点、
 * 后续请求继续走备用、主节点恢复后探活切回；业务层不会收到切换异常。
 */
class RpcProviderManagerTest {

    @Test
    void 主节点429时应自动切备用且第二次调用不再打主节点() throws Exception {
        FakeWeb3jService primary = new FakeWeb3jService();
        FakeWeb3jService backup = new FakeWeb3jService();
        primary.failWith429 = true;
        RpcProviderManager manager = new RpcProviderManager(List.of(primary, backup), 1);

        EthBlockNumber first = Web3j.build(manager).ethBlockNumber().send();

        assertThat(first.getBlockNumber()).isEqualTo(BigInteger.ONE);
        assertThat(primary.calls).isEqualTo(1);
        assertThat(backup.calls).isEqualTo(1);
        assertThat(manager.isOnPrimary()).isFalse();

        // 第二次调用：主节点仍不可用，不应再打主节点，直接走备用
        EthBlockNumber second = Web3j.build(manager).ethBlockNumber().send();

        assertThat(second.getBlockNumber()).isEqualTo(BigInteger.ONE);
        assertThat(primary.calls).isEqualTo(1);
        assertThat(backup.calls).isEqualTo(2);
    }

    @Test
    void 主节点恢复后探活切回主节点() throws Exception {
        FakeWeb3jService primary = new FakeWeb3jService();
        FakeWeb3jService backup = new FakeWeb3jService();
        primary.failWith429 = true;
        RpcProviderManager manager = new RpcProviderManager(List.of(primary, backup), 1);

        Web3j.build(manager).ethBlockNumber().send();
        assertThat(manager.isOnPrimary()).isFalse();

        // 主节点恢复，半开探活成功 -> 切回主节点
        primary.failWith429 = false;
        assertThat(manager.probePrimary()).isTrue();
        assertThat(manager.isOnPrimary()).isTrue();
        assertThat(primary.calls).isEqualTo(2); // 1 次失败 + 1 次探活

        Web3j.build(manager).ethBlockNumber().send();
        assertThat(primary.calls).isEqualTo(3);
        assertThat(backup.calls).isEqualTo(1);
    }

    @Test
    void 超时也触发切换() throws Exception {
        FakeWeb3jService primary = new FakeWeb3jService();
        FakeWeb3jService backup = new FakeWeb3jService();
        primary.failWithTimeout = true;
        RpcProviderManager manager = new RpcProviderManager(List.of(primary, backup), 1);

        EthBlockNumber response = Web3j.build(manager).ethBlockNumber().send();

        assertThat(response.getBlockNumber()).isEqualTo(BigInteger.ONE);
        assertThat(primary.calls).isEqualTo(1);
        assertThat(backup.calls).isEqualTo(1);
        assertThat(manager.isOnPrimary()).isFalse();
    }

    @Test
    void 参数错误不触发切换() {
        FakeWeb3jService primary = new FakeWeb3jService();
        FakeWeb3jService backup = new FakeWeb3jService();
        primary.failWith400 = true;
        RpcProviderManager manager = new RpcProviderManager(List.of(primary, backup), 1);

        assertThatThrownBy(() -> Web3j.build(manager).ethBlockNumber().send())
                .isInstanceOf(ClientConnectionException.class);

        assertThat(primary.calls).isEqualTo(1);
        assertThat(backup.calls).isZero();
        assertThat(manager.isOnPrimary()).isTrue();
    }

    @Test
    void 所有节点都失败时抛出最后一个异常而不是无限循环() {
        FakeWeb3jService primary = new FakeWeb3jService();
        FakeWeb3jService backup = new FakeWeb3jService();
        primary.failWith429 = true;
        backup.failWith429 = true;
        RpcProviderManager manager = new RpcProviderManager(List.of(primary, backup), 1);

        assertThatThrownBy(() -> Web3j.build(manager).ethBlockNumber().send())
                .isInstanceOf(ClientConnectionException.class);

        assertThat(primary.calls).isEqualTo(1);
        assertThat(backup.calls).isEqualTo(1);
    }

    /** 假 RPC 节点：可配置 429 / 400 / 超时；成功时返回 blockNumber=0x1 */
    private static class FakeWeb3jService implements Web3jService {

        private int calls;
        private boolean failWith429;
        private boolean failWith400;
        private boolean failWithTimeout;

        @Override
        public <T extends Response> T send(Request request, Class<T> responseType) throws IOException {
            calls++;
            if (failWith429) {
                throw new ClientConnectionException(429, "Too Many Requests");
            }
            if (failWith400) {
                throw new ClientConnectionException(400, "Bad Request");
            }
            if (failWithTimeout) {
                throw new SocketTimeoutException("timeout");
            }
            EthBlockNumber response = new EthBlockNumber();
            response.setJsonrpc("2.0");
            response.setId(1);
            response.setResult("0x1");
            return responseType.cast(response);
        }

        @Override
        public <T extends Response> CompletableFuture<T> sendAsync(Request request, Class<T> responseType) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public BatchResponse sendBatch(BatchRequest batchRequest) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<BatchResponse> sendBatchAsync(BatchRequest batchRequest) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public <T extends Notification<?>> Flowable<T> subscribe(Request request, String unsubscribeMethod,
                                                                Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}