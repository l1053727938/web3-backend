package com.ray.web3.controller;

import com.ray.web3.enums.BalanceType;
import com.ray.web3.enums.Direction;
import com.ray.web3.model.dto.LedgerPostRequest;
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
 * 复式账本接口（第 2 周 Day 3-4）：把 {@code LedgerService.post()} 暴露成 HTTP，便于联调与压测
 * <p>
 * 路径前缀 {@code /api/ledger} 纳入 JWT 拦截范围：POST 需要 {@code ledger:write}，
 * 只读 token 无法写账本（记账属于花钱动作，和提币提交同级）。
 * 借贷不平返回 500（代码 bug/数据错误，不可重试），余额不足/账户不存在/并发冲突返回 409。
 * <p>
 * 参数校验分两层：请求形状（非空/长度/枚举值/金额格式）由 DTO 上的 Bean Validation
 * 在 {@code @Valid} 处完成并统一返回 400；借贷平衡、余额、账户等业务不变量仍在服务层/领域对象。
 * 该接口只做“把请求翻译成 {@link LedgerGroup}”，Controller 里出现任何余额计算都意味着
 * 记账逻辑被复制到了第二处。
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

    /** 校验已在 DTO 上完成，这里只做类型转换；领域对象构造器仍会兜底拒绝非法值 */
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
