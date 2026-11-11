package com.ray.web3.ledger.mapper;

import com.ray.web3.model.entity.LedgerEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分录组 Mapper（第 2 周 U1-b）
 * <p>
 * <b>刻意不继承 {@code BaseMapper}</b>：BaseMapper 自带 update/delete/updateById 等方法，
 * 只要接口上存在，业务代码就能绕过“账本只增不改”的约束。这里只声明 insert 与查询，
 * 从类型层面消除改账本的可能性（配合 DB 账号只授 INSERT/SELECT）。
 * <p>
 * 幂等由唯一键 {@code uk_biz(biz_type, biz_no)} 兜底，命中时 MyBatis-Spring 抛
 * {@code DuplicateKeyException}，服务层据此走“重复投递”分支。
 * <p>
 * 本包由 {@code LedgerDataSourceConfig} 扫描，走账本专属连接池。
 */
public interface LedgerEntryMapper {

    /**
     * 写入分录组（唯一的写方法）。
     * <p>
     * 手写 INSERT 而不是 BaseMapper.insert：后者要求接口继承 BaseMapper，等于把 update/delete 一并放出来。
     */
    @Insert("""
            INSERT INTO ledger_entry (entry_no, biz_type, biz_no, status, reverse_of)
            VALUES (#{entryNo}, #{bizType}, #{bizNo}, #{status}, #{reverseOf})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(LedgerEntry entry);

    /** 按幂等键回读已存在的分录组，无则返回 null */
    @Select("""
            SELECT id, entry_no, biz_type, biz_no, status, reverse_of, created_at
            FROM ledger_entry
            WHERE biz_type = #{bizType} AND biz_no = #{bizNo}
            """)
    LedgerEntry selectByBiz(@Param("bizType") String bizType, @Param("bizNo") String bizNo);
}
