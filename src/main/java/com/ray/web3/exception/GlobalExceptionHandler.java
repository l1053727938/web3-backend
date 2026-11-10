package com.ray.web3.exception;

import com.ray.web3.model.vo.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.web3j.protocol.exceptions.TransactionException;

import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Bean Validation 失败：把 {@code postings[0].amount} 这样的字段路径与消息配对输出，
     * 而不是打印整个 FieldError 对象（那会把对象名、拒绝值等一并泄露且极难读）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = Stream.concat(
                        e.getBindingResult().getFieldErrors().stream()
                                .map(error -> error.getField() + ": " + error.getDefaultMessage()),
                        e.getBindingResult().getGlobalErrors().stream()
                                .map(ObjectError::getDefaultMessage))
                .distinct()
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "参数校验失败: " + detail));
    }

    /** 请求体不是合法 JSON / 字段类型不匹配：调用方输入错误，回 400，不能落到兜底 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "请求体格式错误或字段类型不匹配"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数非法: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, e.getMessage()));
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<ApiResponse<Void>> handleTransaction(TransactionException e) {
        log.error("链上交易异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "链上交易失败: " + e.getMessage()));
    }

    /**
     * 借贷不平：代码 bug 或数据错误，重试多少次都不会变成成功，所以回 500 而不是 4xx，
     * 也不吞掉消息——只有把差额原样给到调用方/监控，才能定位是哪一组分录写错了
     */
    @ExceptionHandler(UnbalancedLedgerException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnbalancedLedger(UnbalancedLedgerException e) {
        log.error("账本借贷不平，已阻断落库", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, e.getMessage()));
    }

    /**
     * 记账业务失败（余额不足/账户不存在/币种不符/乐观锁冲突）：属于当前状态冲突，
     * 修正入参或重试可能成功，回 409 让调用方区别于“程序员写错了”
     */
    @ExceptionHandler(LedgerPostingException.class)
    public ResponseEntity<ApiResponse<Void>> handleLedgerPosting(LedgerPostingException e) {
        log.warn("记账失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(409, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleRpc(IllegalStateException e) {
        log.error("链上 RPC 调用异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.fail(502, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "系统异常"));
    }
}
