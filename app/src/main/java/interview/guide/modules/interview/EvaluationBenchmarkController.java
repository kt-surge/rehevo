package interview.guide.modules.interview;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.interview.model.EvaluationBenchmarkRequest;
import interview.guide.modules.interview.model.EvaluationBenchmarkResponse;
import interview.guide.modules.interview.service.EvaluationBenchmarkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 离线评分稳定性入口，默认关闭，防止生产环境被误用为批量模型调用接口。
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "app.interview.evaluation",
    name = "benchmark-enabled",
    havingValue = "true"
)
public class EvaluationBenchmarkController {

    private final EvaluationBenchmarkService evaluationBenchmarkService;

    @PostMapping("/api/interview/evaluation/benchmark")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    public Result<EvaluationBenchmarkResponse> evaluate(
            @Valid @RequestBody EvaluationBenchmarkRequest request) {
        return Result.success(evaluationBenchmarkService.evaluate(request));
    }
}
