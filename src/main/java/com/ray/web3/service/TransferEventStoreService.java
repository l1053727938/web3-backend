package com.ray.web3.service;

import com.ray.web3.model.dto.TransferEventDTO;

import java.util.List;

/**
 * 补拉分页落库：一个分页的“事件写入 + 游标推进”在同一事务内提交
 * <p>
 * 事务边界以“页”为单位：拆开会出现“游标前移但事件没入库”的丢单，或“事件入库但游标没动”的重拉；
 * 一页为单元时崩溃最多回退一页，重拉代价可控且幂等
 */
public interface TransferEventStoreService {

    /**
     * 落库一页数据：先幂等批量写入事件，再把游标推进到 pageToBlock（同事务）
     *
     * @param contractAddress  合约地址（大小写不敏感，落库统一小写）
     * @param pageToBlock      本页覆盖到的结束区块（游标推进目标）
     * @param pageEvents       本页解码后的 Transfer 事件（可为空：空页不发 SQL，但照例推进游标）
     * @return 本页实际新增的事件行数（重放区间为 0）
     */
    long savePage(String contractAddress, long pageToBlock, List<TransferEventDTO> pageEvents);

    /**
     * 实时事件落库（模块三消费线程调用）：只做幂等批量 INSERT IGNORE，<b>不推进补拉游标</b>
     * <p>
     * 与补拉的区别：实时推送到达顺序不保证连续，若同步推进游标会给尚未拉取的中间块留下“空洞”造成漏单；
     * 落库与游标解耦后，实时负责“提前发现+入库”，区间完整性仍由模块二按游标重扫兜底（靠唯一键幂等去重）。
     * 可跨多个合约地址（按各 DTO 自带 contract 归属）。
     *
     * @param events 解码后的 Transfer 事件（可为空，直接返回 0）
     * @return 实际新增行数（重放为 0）
     */
    long saveEvents(List<TransferEventDTO> events);
}
