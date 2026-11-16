package com.ray.web3.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 消费端幂等 Mapper（第 3 周 Day 5-6）
 * <p>
 * {@code consumer_biz.biz_no} 是主键：处理消息前先 INSERT IGNORE，
 * 返回 1 = 首次消费，返回 0 = 主键冲突（重复投递），直接 ack 跳过。
 * <p>
 * 本包由 {@code Web3BackendApplication} 的 {@code @MapperScan} 扫描，走业务连接池，
 * 与 outbox_message / deposit_record 同库；消费者的 {@code @Transactional} 覆盖本表写入。
 */
public interface ConsumerBizMapper {

    /**
     * 幂等占位：首次插入返回 1；同一 bizNo 再次插入被 IGNORE，返回 0
     */
    @Insert("INSERT IGNORE INTO consumer_biz (biz_no) VALUES (#{bizNo})")
    int insertIgnore(@Param("bizNo") String bizNo);
}