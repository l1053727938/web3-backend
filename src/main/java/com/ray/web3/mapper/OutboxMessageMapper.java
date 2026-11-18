package com.ray.web3.mapper;

import com.ray.web3.model.entity.OutboxMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Outbox 消息 Mapper（第 3 周 Day 1-2）
 * <p>
 * 本包由 {@code Web3BackendApplication} 的 {@code @MapperScan} 扫描，走业务连接池；
 * 因此 {@code outbox_message} 与 {@code deposit_record} 的写入可以共享同一个本地事务。
 * <p>
 * 投递器扫描语句必须带 {@code FOR UPDATE SKIP LOCKED}：
 * 多实例同时扫表时，已被别的实例锁住的行直接跳过，而不是阻塞等待，从而真正并行；
 * 锁在事务提交时释放，所以扫描、投递、改状态必须在同一个事务里完成。
 */
public interface OutboxMessageMapper {

    /**
     * 写入待投递消息（与业务表同事务）。
     * <p>
     * 刻意不写 {@code next_retry_at}：由 DB 默认值 {@code CURRENT_TIMESTAMP(3)} 填充，
     * 保证“首次可投递时间”与投递器扫描用的 {@code NOW(3)} 处于同一个时钟（DB），
     * 避免 JVM 与 MySQL 时区不一致时消息被推迟/提前数小时。
     */
    @Insert("""
            INSERT INTO outbox_message (biz_type, biz_no, partition_key, payload, status, retry_count)
            VALUES (#{bizType}, #{bizNo}, #{partitionKey}, #{payload}, #{status}, #{retryCount})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(OutboxMessage message);

    /**
     * 取一批到期待投递消息并加行锁；{@code SKIP LOCKED} 保证多实例各拿各的批次。
     * <p>
     * 必须在事务内调用，否则锁在语句结束即释放，其他实例会重复拿到同一批。
     */
    @Select("""
            SELECT id, biz_type, biz_no, partition_key, payload, status, retry_count, next_retry_at, created_at
            FROM outbox_message
            WHERE status = 'NEW' AND next_retry_at <= NOW(3)
            ORDER BY id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxMessage> selectPendingBatch(@Param("limit") int limit);

    /** 投递成功：NEW → SENT */
    @Update("""
            UPDATE outbox_message
            SET status = 'SENT'
            WHERE id = #{id} AND status = 'NEW'
            """)
    int markSent(@Param("id") Long id);

    /**
     * 投递失败但未达上限：累计次数并推迟下次投递时间，状态保持 NEW。
     * <p>
     * 时间计算放在 SQL 里用 {@code TIMESTAMPADD(..., NOW(3))}：与扫描条件 {@code next_retry_at <= NOW(3)}
     * 使用同一个数据库时钟，不依赖 JVM 与 MySQL 的时区配置一致。
     */
    @Update("""
            UPDATE outbox_message
            SET retry_count = retry_count + 1,
                next_retry_at = TIMESTAMPADD(SECOND, #{delaySeconds}, NOW(3)),
                status = 'NEW'
            WHERE id = #{id} AND status = 'NEW'
            """)
    int markRetry(@Param("id") Long id, @Param("delaySeconds") long delaySeconds);

    /** 投递失败且达到重试上限：NEW → FAILED，停止自动投递 */
    @Update("""
            UPDATE outbox_message
            SET retry_count = retry_count + 1,
                next_retry_at = TIMESTAMPADD(SECOND, #{delaySeconds}, NOW(3)),
                status = 'FAILED'
            WHERE id = #{id} AND status = 'NEW'
            """)
    int markFailed(@Param("id") Long id, @Param("delaySeconds") long delaySeconds);
}