package com.ray.web3.controller;

import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.model.vo.BalanceVO;
import com.ray.web3.model.vo.ChainStatusVO;
import com.ray.web3.service.ChainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;

@Tag(name = "链上查询", description = "基于 Web3j + Infura 的链上数据查询")
@RestController
@RequestMapping("/api/chain")
@RequiredArgsConstructor
public class ChainController {

    private final ChainService chainService;

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
}
