package com.ray.web3.rpc;

import io.reactivex.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.BatchResponse;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.exceptions.ClientConnectionException;
import org.web3j.protocol.websocket.events.Notification;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RPC 供应商容灾（第 4 周 Day 5-6 余量）
 * <p>
 * 在 {@link Web3jService} 传输层做“主节点 + 备用节点”切换，业务 Service 仍然注入普通
 * {@link Web3j}，不需要知道多供应商的存在：
 * <ul>
 *   <li>每次 RPC 先走当前活动节点；遇到 429、5xx、超时、连接重置等网络级失败，
 *       记一次连续失败，并<b>立即用同一请求尝试下一个节点</b>，成功则本次调用不向业务层抛异常；</li>
 *   <li>连续失败达到阈值后把活动节点永久切到下一个；成功调用会重置计数；</li>
 *   <li>独立定时任务调用 {@link #probePrimary()}：每 30s 用 {@code eth_blockNumber} 探活主节点，
 *       成功即切回主节点（半开探活）；</li>
 *   <li>参数错误等业务级错误（如 HTTP 400）不触发切换，直接抛给调用方。</li>
 * </ul>
 * 这样“退避重试”和“供应商切换”互补：重试管同一节点的时间节奏，本类管节点本身不可用。
 * <p>
 * 只在 {@code web3.infura.failover.enabled=true} 时由配置装配多个节点；关闭时列表只有主节点，
 * 行为与原来的单 HttpService 一致。
 */
@Slf4j
public class RpcProviderManager implements Web3jService {

    private final List<Web3jService> providers;
    private final int failureThreshold;
    private final AtomicInteger activeIndex = new AtomicInteger(0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RpcProviderManager(List<Web3jService> providers, int failureThreshold) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("RPC providers 不能为空");
        }
        for (Web3jService provider : providers) {
            if (provider == null) {
                throw new IllegalArgumentException("RPC provider 不能为 null");
            }
        }
        this.providers = List.copyOf(providers);
        this.failureThreshold = Math.max(1, failureThreshold);
    }

    /**
     * 同步 RPC：当前节点失败时用同一请求尝试下一个节点；全部失败才把最后一个异常抛给业务层。
     */
    @Override
    public <T extends Response> T send(Request request, Class<T> responseType) throws IOException {
        int start = activeIndex.get();
        int count = providers.size();
        Throwable last = null;
        for (int i = 0; i < count; i++) {
            int index = Math.floorMod(start + i, count);
            try {
                T response = providers.get(index).send(request, responseType);
                markSuccess(index);
                return response;
            } catch (IOException e) {
                if (!isFailoverWorthy(e)) {
                    throw e;
                }
                last = e;
                handleFailure(index, e);
            } catch (ClientConnectionException e) {
                if (!isFailoverWorthy(e)) {
                    throw e;
                }
                last = e;
                handleFailure(index, e);
            }
        }
        rethrow(last);
        return null;
    }

    /**
     * 批量 RPC：与 {@link #send} 相同的失败切换语义。
     */
    @Override
    public BatchResponse sendBatch(BatchRequest batchRequest) throws IOException {
        int start = activeIndex.get();
        int count = providers.size();
        Throwable last = null;
        for (int i = 0; i < count; i++) {
            int index = Math.floorMod(start + i, count);
            try {
                BatchResponse response = providers.get(index).sendBatch(batchRequest);
                markSuccess(index);
                return response;
            } catch (IOException e) {
                if (!isFailoverWorthy(e)) {
                    throw e;
                }
                last = e;
                handleFailure(index, e);
            } catch (ClientConnectionException e) {
                if (!isFailoverWorthy(e)) {
                    throw e;
                }
                last = e;
                handleFailure(index, e);
            }
        }
        rethrow(last);
        return null;
    }

    /**
     * 异步入口复用同步的失败切换逻辑（项目主链路都是同步 .send()；这里保证异步调用也不会绕过容灾）。
     */
    @Override
    public <T extends Response> CompletableFuture<T> sendAsync(Request request, Class<T> responseType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(request, responseType);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<BatchResponse> sendBatchAsync(BatchRequest batchRequest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendBatch(batchRequest);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public <T extends Notification<?>> Flowable<T> subscribe(Request request, String unsubscribeMethod,
                                                            Class<T> responseType) {
        // HTTP 通道不使用 subscribe；保留委托，避免接口默认实现不一致
        return currentProvider().subscribe(request, unsubscribeMethod, responseType);
    }

    /**
     * 半开探活：主节点恢复后切回。由 {@code RpcProviderProbeScheduler} 每 30s 调用一次。
     *
     * @return 主节点是否可用
     */
    public boolean probePrimary() {
        if (providers.size() <= 1) {
            return true;
        }
        try {
            BigInteger block = Web3j.build(providers.get(0)).ethBlockNumber().send().getBlockNumber();
            boolean switched = activeIndex.getAndSet(0) != 0;
            consecutiveFailures.set(0);
            if (switched) {
                log.info("[rpc-failover] 主节点恢复（block={}），已切回主节点", block);
            }
            return true;
        } catch (Exception e) {
            log.debug("[rpc-failover] 主节点仍未恢复: {}", e.getMessage());
            return false;
        }
    }

    public boolean isOnPrimary() {
        return activeIndex.get() == 0;
    }

    public int activeProviderIndex() {
        return activeIndex.get();
    }

    public int providerCount() {
        return providers.size();
    }

    private Web3jService currentProvider() {
        return providers.get(activeIndex.get());
    }

    private void markSuccess(int index) {
        activeIndex.set(index);
        consecutiveFailures.set(0);
    }

    private void handleFailure(int index, Throwable cause) {
        int failures = consecutiveFailures.incrementAndGet();
        int next = (index + 1) % providers.size();
        if (failures >= failureThreshold) {
            activeIndex.set(next);
            consecutiveFailures.set(0);
            log.warn("[rpc-failover] provider[{}] 连续失败 {} 次，切换到 provider[{}]: {}",
                    index, failures, next, cause.getMessage());
        } else {
            log.warn("[rpc-failover] provider[{}] 失败 {} 次，本次先尝试 provider[{}]: {}",
                    index, failures, next, cause.getMessage());
        }
    }

    /**
     * 只对网络级错误切换：HTTP 429/5xx、超时、连接重置/拒绝、Socket 异常。
     * 参数错误（如 400）等业务错误不切换。
     */
    private static boolean isFailoverWorthy(Throwable error) {
        if (error instanceof ClientConnectionException clientError) {
            Integer code = clientError.getCode();
            return code == null || code == 429 || code >= 500;
        }
        if (error instanceof SocketTimeoutException
                || error instanceof ConnectException
                || error instanceof SocketException) {
            return true;
        }
        if (error instanceof IOException) {
            String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
            return message.contains("429")
                    || message.contains("too many")
                    || message.contains("timeout")
                    || message.contains("timed out")
                    || message.contains("connection reset")
                    || message.contains("connection refused")
                    || message.contains("502")
                    || message.contains("503")
                    || message.contains("504");
        }
        return false;
    }

    private static void rethrow(Throwable last) throws IOException {
        if (last instanceof IOException io) {
            throw io;
        }
        if (last instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new IOException("RPC 调用失败", last);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException first = null;
        for (int i = 0; i < providers.size(); i++) {
            try {
                providers.get(i).close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
                log.warn("[rpc-failover] 关闭 provider[{}] 失败: {}", i, e.getMessage());
            }
        }
        if (first != null) {
            throw first;
        }
    }
}