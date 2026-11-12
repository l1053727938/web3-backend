package com.ray.web3.controller;

import com.ray.web3.enums.BalanceType;
import com.ray.web3.enums.Direction;
import com.ray.web3.model.dto.LedgerDepositRequest;
import com.ray.web3.model.dto.LedgerFreezeRequest;
import com.ray.web3.model.dto.LedgerPostRequest;
import com.ray.web3.model.dto.LedgerReverseRequest;
import com.ray.web3.model.dto.LedgerSettleRequest;
import com.ray.web3.model.dto.LedgerUnfreezeRequest;
import com.ray.web3.model.entity.LedgerEntry;
import com.ray.web3.model.ledger.LedgerGroup;
import com.ray.web3.model.ledger.Posting;
import com.ray.web3.model.vo.ApiResponse;
import com.ray.web3.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 复式账本接口（第 2 周 Day 3-6）：把 {@code LedgerService} 的写入口暴露成 HTTP，便于联调与压测
 * <p>
 * 路径前缀 {@code /api/ledger} 纳入 JWT 拦截范围：POST 需要 {@code ledger:write}，
 * 只读 token 无法写账本（记账属于花钱动作，和提币提交同级）。
 * 借贷不平返回 500（代码 bug/数据错误，不可重试），余额不足/账户不存在/并发冲突返回 409。
 * <p>
 * 参数校验分两层：请求形状（非空/长度/枚举值/金额格式）由 DTO 上的 Bean Validation
 * 在 {@code @Valid} 处完成并统一返回 400；借贷平衡、余额、账户等业务不变量仍在服务层/领域对象。
 * 该接口只做“把请求翻译成 {@link LedgerGroup} 或直接调场景方法”，Controller 里出现任何余额计算
 * 都意味着记账逻辑被复制到了第二处。
 * <p>
 * 六个端点与 {@link LedgerService} 一一对应：
 * <ul>
 *   <li>{@code POST /post}：通用分录组入口；</li>
 *   <li>{@code POST /deposit}：① 充值落账；</li>
 *   <li>{@code POST /freeze}：② 下单冻结；</li>
 *   <li>{@code POST /settle}：③ 成交清算（本金 + 可选手续费，同事务两组分录）；</li>
 *   <li>{@code POST /unfreeze}：④ 提币失败解冻；</li>
 *   <li>{@code POST /reverse}：冲正（写反向分录，原分录不改）。</li>
 * </ul>
 * 联调数据见 {@code sql/ledger_test_data.sql}，一键调用见 {@code scripts/ledger-api-demo.sh}。
 */
@Tag(name = "复式账本", description = "分录组记账：借贷平衡校验 → 落 ledger_entry/ledger_posting → 乐观锁回写 account")
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @Operation(summary = "记一组分录",
            description = """
                    幂等：同一 (bizType, bizNo) 重复提交只会记一次账，返回首次落库的分录组（entryNo 相同）。
                    借贷不平衡直接 500 且不落库；余额不足/账户不存在/币种不符/并发冲突返回 409。
                    金额传十进制字符串，方向 DEBIT=减、CREDIT=加，balanceType 省略默认 AVAILABLE。
                    需 scope ledger:write""")
    @PostMapping("/post")
    public ApiResponse<LedgerEntry> post(@Valid @RequestBody LedgerPostRequest request) {
        return ApiResponse.ok(ledgerService.post(toGroup(request)));
    }

    @Operation(summary = "冲正",
            description = """
                    按 (bizType, bizNo) 找到原分录组，写一组方向相反的新分录：
                    bizType=REVERSE、bizNo=REV:<原 entryNo>、reverseOf=原 entry_id。
                    原分录不修改、不删除；重复冲正返回首次冲正组（幂等）。
                    冲正允许余额为负（更正既成事实优先于余额非负约束）。
                    需 scope ledger:write""")
    @PostMapping("/reverse")
    public ApiResponse<LedgerEntry> reverse(@Valid @RequestBody LedgerReverseRequest request) {
        return ApiResponse.ok(ledgerService.reverse(request.bizType(), request.bizNo()));
    }

    @Operation(summary = "① 充值落账",
            description = """
                    用户 available +amount / 平台代收负债 available -amount。
                    调用前必须确认充值单已到账（deposit_record.status=CREDITED），本接口不判断链上确认数。
                    同一 depositNo 重复提交幂等；平台负债账户允许负余额。
                    需 scope ledger:write""")
    @PostMapping("/deposit")
    public ApiResponse<LedgerEntry> deposit(@Valid @RequestBody LedgerDepositRequest request) {
        return ApiResponse.ok(ledgerService.deposit(
                request.depositNo(),
                request.userAccountId(),
                request.platformLiabilityAccountId(),
                new BigDecimal(request.amount()),
                request.currency()));
    }

    @Operation(summary = "② 下单冻结",
            description = """
                    用户 available -amount / 用户 frozen +amount，total=available+frozen 不变。
                    可用余额不足返回 409；同一 orderNo 重复提交幂等。
                    需 scope ledger:write""")
    @PostMapping("/freeze")
    public ApiResponse<LedgerEntry> freeze(@Valid @RequestBody LedgerFreezeRequest request) {
        return ApiResponse.ok(ledgerService.freeze(
                request.orderNo(),
                request.userAccountId(),
                new BigDecimal(request.amount()),
                request.currency()));
    }

    @Operation(summary = "④ 提币失败解冻",
            description = """
                    用户 frozen -amount / 用户 available +amount，与冻结方向相反。
                    bizType=UNFREEZE 与 FREEZE 分开，同一个提币单号可先冻结后解冻；
                    同一 withdrawNo 重复提交幂等。冻结余额不足返回 409。
                    需 scope ledger:write""")
    @PostMapping("/unfreeze")
    public ApiResponse<LedgerEntry> unfreeze(@Valid @RequestBody LedgerUnfreezeRequest request) {
        return ApiResponse.ok(ledgerService.unfreeze(
                request.withdrawNo(),
                request.userAccountId(),
                new BigDecimal(request.amount()),
                request.currency()));
    }

    @Operation(summary = "③ 成交清算（本金 + 手续费）",
            description = """
                    同一个事务里写两组分录：
                    本金 TRANSFER：taker frozen -amount / maker available +amount；
                    手续费 FEE（可省略或传 0，传 0 时只记本金）：付款方 available -fee / 平台手续费账户 available +fee。
                    两组 bizType 不同、共用 tradeNo，各自幂等；任一组失败整笔回滚。
                    需 scope ledger:write""")
    @PostMapping("/settle")
    public ApiResponse<LedgerService.Settlement> settle(@Valid @RequestBody LedgerSettleRequest request) {
        BigDecimal fee = request.fee() == null || request.fee().isBlank()
                ? null
                : new BigDecimal(request.fee());
        return ApiResponse.ok(ledgerService.settle(
                request.tradeNo(),
                request.takerAccountId(),
                request.makerAccountId(),
                request.feePayerAccountId(),
                request.platformFeeAccountId(),
                new BigDecimal(request.amount()),
                fee,
                request.currency()));
    }

    /** 通用入口：校验已在 DTO 上完成，这里只做类型转换；领域对象构造器仍会兜底拒绝非法值 */
    private static LedgerGroup toGroup(LedgerPostRequest request) {
        List<Posting> postings = request.postings().stream()
                .map(LedgerController::toPosting)
                .toList();
        return new LedgerGroup(request.bizType(), request.bizNo(), postings);
    }

    private static Posting toPosting(LedgerPostRequest.PostingRequest source) {
        BalanceType balanceType = source.balanceType() == null || source.balanceType().isBlank()
                ? BalanceType.AVAILABLE
                : BalanceType.valueOf(source.balanceType().trim().toUpperCase(Locale.ROOT));
        return new Posting(
                source.accountId(),
                Direction.valueOf(source.direction().trim().toUpperCase(Locale.ROOT)),
                new BigDecimal(source.amount()),
                source.currency(),
                balanceType);
    }
}
