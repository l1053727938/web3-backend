package com.ray.web3.mapper;

import com.ray.web3.model.entity.DlqMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 死信消息 Mapper（第 4 周 Day 1-2）
 * <p>
 * 只在投递重试耗尽时写入，走业务连接池，与 outbox_message 的状态更新在同一事务：
 * 要么“死信 + FAILED”都成功，要么都回滚、消息仍保持 NEW 继续重试。
 */
public interface DlqMessageMapper {

    /**
     * 幂等写入死信：命中 {@code uk_source(source_table, source_id)} 时静默跳过，
     * 避免重复处理同一条消息产生多条死信。
     */
    @Insert("""
            INSERT IGNORE INTO dlq_message
                (source_table, source_id, biz_type, biz_no, partition_key,
                 payload, retry_count, error_message, status)
            VALUES
                (#{sourceTable}, #{sourceId}, #{bizType}, #{bizNo}, #{partitionKey},
                 #{payload}, #{retryCount}, #{errorMessage}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertIgnore(DlqMessage message);
}
