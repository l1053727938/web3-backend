package com.ray.web3.service.impl;

import com.ray.web3.config.InfuraProperties;
import com.ray.web3.mapper.BlockSyncStateMapper;
import com.ray.web3.mapper.TransferEventLogMapper;
import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.entity.TransferEventLog;
import com.ray.web3.service.TransferEventStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页落库实现：事件写入与游标推进同事务提交
 * <p>
 * 事件走 INSERT IGNORE + 唯一键做幂等（重放安全靠数据库约束，不依赖分布式事务）；
 * 每 500 行一批控制单语句包大小，避免超出 MySQL max_allowed_packet；
 * 游标用 upsertCursor 的 GREATEST 语义，保证只向前单调推进
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferEventStoreServiceImpl implements TransferEventStoreService {

    /** 单条 INSERT 语句的最大行数：控制包大小，防止批量拼接口超 max_allowed_packet 被拒 */
    private static final int INSERT_BATCH_SIZE = 500;

    private final TransferEventLogMapper transferEventLogMapper;
    private final BlockSyncStateMapper blockSyncStateMapper;
    private final InfuraProperties infuraProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long savePage(String contractAddress, long pageToBlock, List<TransferEventDTO> pageEvents) {
        Long chainId = infuraProperties.getChainId();
        String contract = contractAddress.toLowerCase();

        long saved = 0;
        if (pageEvents != null && !pageEvents.isEmpty()) {
            List<TransferEventLog> rows = toEntities(chainId, contract, pageEvents);
            for (int from = 0; from < rows.size(); from += INSERT_BATCH_SIZE) {
                int to = Math.min(from + INSERT_BATCH_SIZE, rows.size());
                saved += transferEventLogMapper.insertIgnoreBatch(rows.subList(from, to));
            }
        }

        // 空页同样推进游标：否则补齐会卡在没有 Transfer 的区间，循环永不前进
        blockSyncStateMapper.upsertCursor(chainId, contract, pageToBlock);

        log.debug("分页落库 contract={} pageTo={} events={} saved={}",
                contract, pageToBlock, pageEvents == null ? 0 : pageEvents.size(), saved);
        return saved;
    }

    /**
     * DTO → 落库实体：金额以十进制字符串存两列（raw 最小单位 / display 换算值），合约/链维度补齐
     */
    private List<TransferEventLog> toEntities(Long chainId, String contract, List<TransferEventDTO> events) {
        List<TransferEventLog> rows = new ArrayList<>(events.size());
        for (TransferEventDTO e : events) {
            TransferEventLog row = new TransferEventLog();
            row.setChainId(chainId);
            row.setContractAddress(contract);
            row.setBlockNumber(e.blockNumber().longValue());
            row.setTransactionHash(e.transactionHash());
            row.setLogIndex(e.logIndex().longValue());
            row.setFromAddress(e.from());
            row.setToAddress(e.to());
            row.setRawValue(e.rawValue().toString());
            row.setDisplayValue(e.displayValue());
            row.setDecimals(e.decimals());
            rows.add(row);
        }
        return rows;
    }
}
