package com.ray.web3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ray.web3.model.entity.LedgerEntry;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分录组 Mapper（第 2 周 U1-b）
 * <p>
 * 写入直接复用 {@code BaseMapper#insert}：幂等由唯一键 {@code uk_biz(biz_type, biz_no)} 兜底，
 * 命中时 MyBatis-Spring 会抛 {@code DuplicateKeyException}，服务层据此走“重复投递”分支。
 * <p>
 * 不提供 update/delete 的自定义方法，避免账本被业务代码改写。
 */
public interface LedgerEntryMapper extends BaseMapper<LedgerEntry> {

    /** 按幂等键回读已存在的分录组，无则返回 null */
    @Select("""
            SELECT id, entry_no, biz_type, biz_no, status, reverse_of, created_at
            FROM ledger_entry
            WHERE biz_type = #{bizType} AND biz_no = #{bizNo}
            """)
    LedgerEntry selectByBiz(@Param("bizType") String bizType, @Param("bizNo") String bizNo);
}
