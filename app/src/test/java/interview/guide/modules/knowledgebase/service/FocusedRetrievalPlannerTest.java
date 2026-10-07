package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("有限分题召回计划边界")
class FocusedRetrievalPlannerTest {
  @Test
  @DisplayName("辅助子句复制版本和否定条件，完整问题由调用方保留")
  void copiesSelfContainedClausesWithConstraints() {
    String question = "Redis 7.0 XAUTOCLAIM 是否不会恢复删除正文；Spring REQUIRED 默认是否不校验不同隔离级别？";
    assertThat(FocusedRetrievalPlanner.plan(question)).containsExactly(
        "Redis 7.0 XAUTOCLAIM 是否不会恢复删除正文", "Spring REQUIRED 默认是否不校验不同隔离级别");
  }

  @Test
  @DisplayName("无法拆句的比较问题只取完整标识作为辅助召回")
  void keepsQualifiedIdentifiers() {
    assertThat(FocusedRetrievalPlanner.plan(
        "等待全部结果时，CompletableFuture.allOf 和 ExecutorService.invokeAll 的结果形式有什么区别？"))
        .containsExactly("CompletableFuture.allOf", "ExecutorService.invokeAll");
  }

  @Test
  @DisplayName("指代不清的第二个子问题不单独召回")
  void declinesDependentClause() {
    assertThat(FocusedRetrievalPlanner.plan(
        "ExecutorService.invokeAll 支持哪些不同的等待方式；它与 CompletableFuture.allOf 是否完全相同？"))
        .isEmpty();
  }

  @Test
  @DisplayName("单主题、过多标识及超长问题保持原检索")
  void declinesUnboundedOrSingleSubjectPlans() {
    assertThat(FocusedRetrievalPlanner.plan("CompletableFuture.cancel(true) 到底能不能停止后台异步工作？")).isEmpty();
    assertThat(FocusedRetrievalPlanner.plan(
        "分别解释 CompletableFuture.allOf、ExecutorService.invokeAll 和 ForkJoinPool 的差异及使用条件。"))
        .isEmpty();
    assertThat(FocusedRetrievalPlanner.plan("比较 CompletableFuture.allOf 和 ExecutorService.invokeAll" + "条件".repeat(260)))
        .isEmpty();
  }
}
