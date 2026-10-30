package com.ray.web3.service.impl;

import com.ray.web3.config.InfuraProperties;
import com.ray.web3.consts.DbConsts;
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
 * 单语句行数受 {@link DbConsts#INSERT_BATCH_SIZE} 约束，避免超出 MySQL max_allowed_packet；
 * 游标用 upsertCursor 的 GREATEST 语义，保证只向前单调推进
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferEventStoreServiceImpl implements TransferEventStoreService {

    private final TransferEventLogMapper transferEventLogMapper;
    private final BlockSyncStateMapper blockSyncStateMapper;
    private final InfuraProperties infuraProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long savePage(String contractAddress, long pageToBlock, List<TransferEventDTO> pageEvents) {
        String contract = contractAddress.toLowerCase();

        long saved = 0;
        if (pageEvents != null && !pageEvents.isEmpty()) {
            saved = insertIgnoreChunks(toEntities(pageEvents));
        }

        // 空页同样推进游标：否则补齐会卡在没有 Transfer 的区间，循环永不前进
        blockSyncStateMapper.upsertCursor(infuraProperties.getChainId(), contract, pageToBlock);

        log.debug("分页落库 contract={} pageTo={} events={} saved={}",
                contract, pageToBlock, pageEvents == null ? 0 : pageEvents.size(), saved);
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long saveEvents(List<TransferEventDTO> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        long saved = insertIgnoreChunks(toEntities(events));
        log.debug("实时落库 events={} saved={}", events.size(), saved);
        return saved;
    }

    /**
     * 分批幂等写入：每 {@link DbConsts#INSERT_BATCH_SIZE} 行一条 INSERT IGNORE，累加实际新增行数
     * （与模块四充值表共用同一常量，两条链路不会因环境配置不同而拼出不同大小的包）
     */
    private long insertIgnoreChunks(List<TransferEventLog> rows) {
        long saved = 0;
        for (int from = 0; from < rows.size(); from += DbConsts.INSERT_BATCH_SIZE) {
            int to = Math.min(from + DbConsts.INSERT_BATCH_SIZE, rows.size());
            saved += transferEventLogMapper.insertIgnoreBatch(rows.subList(from, to));
        }
        return saved;
    }

    /**
     * DTO → 落库实体：金额以十进制字符串存两列（raw 最小单位 / display 换算值），链维度补齐、合约取各 DTO 自带地址
     */
    private List<TransferEventLog> toEntities(List<TransferEventDTO> events) {
        Long chainId = infuraProperties.getChainId();
        List<TransferEventLog> rows = new ArrayList<>(events.size());
        for (TransferEventDTO e : events) {
            TransferEventLog row = new TransferEventLog();
            row.setChainId(chainId);
            row.setContractAddress(e.contract());
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
