package com.ray.web3.controller;

import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.model.vo.BalanceVO;
import com.ray.web3.model.vo.ChainStatusVO;
import com.ray.web3.model.vo.TransferLogVO;
import com.ray.web3.service.ChainService;
import com.ray.web3.service.EventLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.List;

@Tag(name = "链上查询", description = "基于 Web3j + Infura 的链上数据查询")
@RestController
@RequestMapping("/api/chain")
@RequiredArgsConstructor
public class ChainController {

    private final ChainService chainService;
    private final EventLogService eventLogService;

    @Operation(summary = "链上状态", description = "客户端版本 / 链ID / 最新区块高度 / Gas Price")
    @GetMapping("/status")
    public ApiResponse<ChainStatusVO> status() {
        return ApiResponse.ok(chainService.status());
    }

    @Operation(summary = "最新区块高度")
    @GetMapping("/block-number")
    public ApiResponse<BigInteger> blockNumber() {
        return ApiResponse.ok(chainService.latestBlockNumber());
    }

    @Operation(summary = "地址余额", description = "查询 ETH 余额，返回 wei 与 ether 两种单位")
    @GetMapping("/balance/{address}")
    public ApiResponse<BalanceVO> balance(@PathVariable String address) {
        return ApiResponse.ok(chainService.getBalance(address));
    }

    @Operation(summary = "分页拉取 Transfer 日志", description = "按固定块跨度分段调用 eth_getLogs，拉取指定合约历史区间内的 ERC-20 Transfer 事件,0x0fD04a68d3c3A692d6Fa30384D1A87Ef93554eE6")
    @GetMapping("/logs/transfer/{contract}")
    public ApiResponse<List<TransferLogVO>> transferLogs(
            @PathVariable String contract,
            @RequestParam BigInteger fromBlock,
            @RequestParam BigInteger toBlock) {
        return ApiResponse.ok(eventLogService.fetchTransferLogs(contract, fromBlock, toBlock));
    }
}
