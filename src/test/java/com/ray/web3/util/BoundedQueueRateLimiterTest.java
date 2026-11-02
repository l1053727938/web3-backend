package com.ray.web3.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 有界排队原语单测：验证"处理槽 + 排队预算"双约束语义——
 * 有槽即占、排队区满立即拒、有界等待超时拒、归还后槽位复用
 */
class BoundedQueueRateLimiterTest {

    @Test
    @DisplayName("构造参数非法时快速失败")
    void rejectsIllegalConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedQueueRateLimiter(0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedQueueRateLimiter(1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedQueueRateLimiter(1, 10, -1));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("快速失败：无空槽且排队区满时立即拒；归还后槽位可复用")
    void fastFailRejectsWhenNoCapacityAndReusesAfterRelease() throws InterruptedException {
        // queueCapacity=0 且 timeout=0 → 退化为"有槽就过、没槽就拒"
        BoundedQueueRateLimiter gate = new BoundedQueueRateLimiter(1, 0, 0);

        Runnable first = gate.tryAcquire();
        assertNotNull(first, "首个请求应拿到唯一处理槽");

        assertNull(gate.tryAcquire(), "槽被占用且排队区容量为 0，第二个请求应立即被拒");

        first.run(); // 归还槽位
        assertNotNull(gate.tryAcquire(), "归还后新请求应能拿到释放出的槽位");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("有界排队：等待超时且槽未释放时返回 null，排队计数归零")
    void boundedQueueRejectsOnTimeout() throws InterruptedException {
        BoundedQueueRateLimiter gate = new BoundedQueueRateLimiter(1, 10, 50);

        Runnable holder = gate.tryAcquire();
        assertNotNull(holder);

        long start = System.nanoTime();
        // 排队区内有空位，但持有者不释放 → 等待至 timeout(50ms) 后被拒
        assertNull(gate.tryAcquire(), "超过排队超时仍未拿到槽位应返回 null");
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(40),
                "应确实经历了有界等待而非立即返回");
        assertEquals(0, gate.waitingCount(), "被拒后不应残留排队计数");
        assertEquals(0, gate.availableSlots(), "持有者仍占着唯一槽位，空闲数应为 0");

        holder.run();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("并发处理槽按 maxConcurrent 逐个放行，超出进入排队判定")
    void admitsUpToMaxConcurrent() throws InterruptedException {
        BoundedQueueRateLimiter gate = new BoundedQueueRateLimiter(2, 0, 0);

        Runnable a = gate.tryAcquire();
        Runnable b = gate.tryAcquire();
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(0, gate.availableSlots(), "两个槽都用完了");

        assertNull(gate.tryAcquire(), "第三个请求无槽且排队区为 0 → 拒");

        a.run();
        b.run();
        assertEquals(2, gate.availableSlots(), "全部归还后恢复初始容量");
    }
}
