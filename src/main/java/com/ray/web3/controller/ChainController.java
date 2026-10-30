package com.ray.web3.controller;

import com.ray.web3.model.dto.TransferEventDTO;
import com.ray.web3.model.entity.DepositWallet;
import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.model.vo.BackfillResultVO;
import com.ray.web3.model.vo.BalanceVO;
import com.ray.web3.model.vo.ChainStatusVO;
import com.ray.web3.model.vo.TransferLogVO;
import com.ray.web3.service.ChainService;
import com.ray.web3.service.DepositWalletService;
import com.ray.web3.service.EventLogService;
import com.ray.web3.service.HistoryBackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** 补拉服务受 web3.backfill.enabled 控制，用 ObjectProvider 延迟取得，关闭时不影响应用启动 */
    private final ObjectProvider<HistoryBackfillService> historyBackfillService;

    /** 充值地址配置（模块四）：常规总是存在，依赖异常时用 ObjectProvider 避免拖住启动 */
    private final ObjectProvider<DepositWalletService> depositWalletService;

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

    @Operation(summary = "分页拉取并解码 Transfer 事件", description = "按事件蓝图解码 topics/data，精度动态取自合约 decimals()，非 Transfer 事件丢弃，displayValue 与 Etherscan 展示一致，0xdac17f958d2ee523a2206206994597c13d831ec7")
    @GetMapping("/logs/transfer/decoded/{contract}")
    public ApiResponse<List<TransferEventDTO>> decodedTransferLogs(
            @PathVariable String contract,
            @RequestParam BigInteger fromBlock,
            @RequestParam BigInteger toBlock) {
        return ApiResponse.ok(eventLogService.fetchDecodedTransferLogs(contract, fromBlock, toBlock));
    }

    @Operation(summary = "按交易哈希解码 Transfer 事件", description = "读取交易收据内全部日志，筛选 Transfer 事件并按各自合约精度换算，用于与 Etherscan 对照验收")
    @GetMapping("/tx/{txHash}/transfers")
    public ApiResponse<List<TransferEventDTO>> txTransfers(@PathVariable String txHash) {
        return ApiResponse.ok(eventLogService.fetchTransfersByTx(txHash));
    }

    @Operation(summary = "历史 Transfer 日志补拉（断点续传）",
            description = "以 block_sync_state 中的 lastProcessedBlock 为游标按页拉取 [fromBlock,toBlock] 区间日志；"
                    + "每页解码后的事件与游标同一事务落库，崩溃后重调本接口即可从断点续拉。"
                    + "chunkSize 为空时取 web3.backfill.chunk-size（默认 1000 块/页）。示例：USDT 主网 fromBlock=18000000&toBlock=18001000")
    @PostMapping("/logs/transfer/backfill/{contract}")
    public ApiResponse<BackfillResultVO> backfillTransferLogs(
            @PathVariable String contract,
            @RequestParam BigInteger fromBlock,
            @RequestParam BigInteger toBlock,
            @RequestParam(required = false) Integer chunkSize) {
        return ApiResponse.ok(requireBackfillService().backfill(contract, fromBlock, toBlock, chunkSize));
    }

    @Operation(summary = "查询补拉游标", description = "返回该合约已处理到的区块高度 lastProcessedBlock（Redis 命中优先，未命中回源 MySQL），无记录返回 null")
    @GetMapping("/logs/cursor/{contract}")
    public ApiResponse<Long> syncCursor(@PathVariable String contract) {
        return ApiResponse.ok(requireBackfillService().currentCursor(contract));
    }

    @Operation(summary = "对账已入库 Transfer 数", description = "统计区间内 transfer_event_log 已落库的事件条数，与链上拉取条数比对确认不漏单")
    @GetMapping("/logs/transfer/saved-count/{contract}")
    public ApiResponse<Long> savedTransferCount(
            @PathVariable String contract,
            @RequestParam BigInteger fromBlock,
            @RequestParam BigInteger toBlock) {
        return ApiResponse.ok(requireBackfillService().savedTransferCount(contract, fromBlock, toBlock));
    }

    private HistoryBackfillService requireBackfillService() {
        HistoryBackfillService service = historyBackfillService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("历史补拉服务未启用（web3.backfill.enabled=false）");
        }
        return service;
    }

    // ============ 模块四：充值业务路由（REQ-BIZ-04） ============

    @Operation(summary = "查询生效中的充值地址配置",
            description = "返回当前路由使用的全部启用地址（配置中心热钱包 + deposit_wallet 表），地址与限定合约均为小写")
    @GetMapping("/deposit/wallets")
    public ApiResponse<List<DepositWallet>> depositWallets() {
        return ApiResponse.ok(depositWalletService().activeWallets());
    }

    @Operation(summary = "新增/更新充值地址",
            description = "按 (chainId, 小写地址) upsert；walletType=HOT 平台热钱包，USER 用户充值地址（必填 userId）；"
                    + "contract 为空表示任意代币到账均计充值，非空表示仅该合约计入。写完立即失效地址缓存")
    @PostMapping("/deposit/wallets")
    public ApiResponse<DepositWallet> saveDepositWallet(@RequestBody DepositWallet wallet) {
        return ApiResponse.ok(depositWalletService().saveWallet(wallet));
    }

    @Operation(summary = "停用充值地址", description = "status 置 0，不再路由新充值（历史充值记录保留可追溯）；chainId 为空取当前链")
    @DeleteMapping("/deposit/wallets/{address}")
    public ApiResponse<Boolean> disableDepositWallet(@PathVariable String address,
                                                     @RequestParam(required = false) Long chainId) {
        return ApiResponse.ok(depositWalletService().disableWallet(chainId, address));
    }

    @Operation(summary = "强制刷新充值地址缓存", description = "新增/停用地址后无需等 TTL，直接回源重建内存索引")
    @PostMapping("/deposit/wallets/refresh")
    public ApiResponse<Integer> refreshDepositWallets() {
        DepositWalletService service = depositWalletService();
        service.invalidateCache();
        return ApiResponse.ok(service.activeWallets().size());
    }

    private DepositWalletService depositWalletService() {
        DepositWalletService service = depositWalletService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("充值地址服务不可用（Redis/MySQL 未就绪）");
        }
        return service;
    }
}
