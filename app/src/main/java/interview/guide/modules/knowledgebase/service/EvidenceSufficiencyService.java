package interview.guide.modules.knowledgebase.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * First-stage evidence gate for claims requiring explicit support.
 *
 * <p>Retrieval similarity alone cannot distinguish “the selected material mentions
 * Redis” from “the selected material contains the requested default value”. This
 * observer catches an objectively checkable subset: exact values, configuration,
 * production facts and absolute claims require a matching kind of evidence rather
 * than merely a topically related candidate.
 * It stays in observe mode until Gold-set calibration proves it is safe to enforce.</p>
 */
@Service
public class EvidenceSufficiencyService {

    private static final Pattern NUMERIC_CONSTRAINT = Pattern.compile(
        "(?i)(多少|几(?:个|条|秒|次|位)?|数量|参数量|版本|版本号|日期|默认\\s*(?:count|数量)|count|how\\s+many|version|\\d+\\s*维)"
    );
    private static final Pattern NUMERIC_EVIDENCE = Pattern.compile(
        "(?i)(?:\\b\\d+(?:\\.\\d+)?(?:\\s*(?:ms|毫秒|秒|分钟|小时|天|个|条|次|位|维|%))?\\b|(?:一[十百千万]|[二三四五六七八九十百千万][零一二三四五六七八九十百千万]*)(?:个|条|次|秒|毫秒|分钟|小时|天|位|维))"
    );
    private static final Pattern CONFIGURATION_CONSTRAINT = Pattern.compile(
        "(?i)(默认.*(?:模型|配置|count)|具体\\s*(?:bean|模型|实例)|(?:bean|模型)名称|最大(?:连接数|消息|消费者|保留))"
    );
    private static final Pattern CONFIGURATION_EVIDENCE = Pattern.compile(
        "(?i)(默认|配置|count|bean|模型名称|最大(?:连接数|消息|消费者|保留))"
    );
    private static final Pattern OPERATIONAL_CONSTRAINT = Pattern.compile(
        "(?i)(生产环境|当前线上|线上使用|线上环境|每天处理|(?:本|该)项目.*(?:配置|连接池)|rehevo.*(?:生产|线上))"
    );
    private static final Pattern OPERATIONAL_EVIDENCE = Pattern.compile(
        "(?i)(生产环境|当前线上|线上环境|部署|运行配置|实际运行|监控数据)"
    );
    private static final Pattern ABSOLUTE_CONSTRAINT = Pattern.compile("(所有|必然|一定|总是|从不|端到端恰好一次)");
    private static final Pattern ABSOLUTE_EVIDENCE = Pattern.compile("(所有|必然|一定|总是|从不|至少一次|恰好一次)");
    private static final Pattern TECHNICAL_ANCHOR = Pattern.compile("(?i)[a-z][a-z0-9_-]{2,}");

    public EvidenceAssessment assess(String question, List<Document> documents) {
        int candidateCount = documents == null ? 0 : documents.size();
        if (candidateCount == 0) {
            return new EvidenceAssessment(false, EvidenceAssessmentReason.NO_CANDIDATE, 0, false, false);
        }

        boolean numericConstraint = question != null && NUMERIC_CONSTRAINT.matcher(question).find();
        boolean numericEvidence = hasSupportedNumericEvidence(question, documents);
        if (numericConstraint && !numericEvidence) {
            return new EvidenceAssessment(false, EvidenceAssessmentReason.UNSUPPORTED_NUMERIC_CONSTRAINT,
                candidateCount, true, false);
        }
        if (question != null && CONFIGURATION_CONSTRAINT.matcher(question).find()
            && !contains(documents, CONFIGURATION_EVIDENCE)) {
            return new EvidenceAssessment(false, EvidenceAssessmentReason.UNSUPPORTED_CONFIGURATION_ASSERTION,
                candidateCount, numericConstraint, numericEvidence);
        }
        if (question != null && OPERATIONAL_CONSTRAINT.matcher(question).find()
            && !contains(documents, OPERATIONAL_EVIDENCE)) {
            return new EvidenceAssessment(false, EvidenceAssessmentReason.UNSUPPORTED_OPERATIONAL_ASSERTION,
                candidateCount, numericConstraint, numericEvidence);
        }
        if (question != null && ABSOLUTE_CONSTRAINT.matcher(question).find()
            && !contains(documents, ABSOLUTE_EVIDENCE)) {
            return new EvidenceAssessment(false, EvidenceAssessmentReason.UNSUPPORTED_ABSOLUTE_ASSERTION,
                candidateCount, numericConstraint, numericEvidence);
        }
        return new EvidenceAssessment(true, EvidenceAssessmentReason.CANDIDATE_EVIDENCE,
            candidateCount, numericConstraint, numericEvidence);
    }

    private boolean contains(List<Document> documents, Pattern pattern) {
        return documents.stream()
            .map(Document::getText)
            .filter(java.util.Objects::nonNull)
            .anyMatch(text -> pattern.matcher(text).find());
    }

    private boolean hasSupportedNumericEvidence(String question, List<Document> documents) {
        List<String> anchors = question == null ? List.of() : TECHNICAL_ANCHOR.matcher(question).results()
            .map(match -> match.group().toLowerCase(Locale.ROOT))
            .distinct()
            .toList();
        return documents.stream()
            .map(Document::getText)
            .filter(java.util.Objects::nonNull)
            .anyMatch(text -> {
                if (!NUMERIC_EVIDENCE.matcher(text).find()) {
                    return false;
                }
                String normalizedText = text.toLowerCase(Locale.ROOT);
                return anchors.isEmpty() || anchors.stream().anyMatch(normalizedText::contains);
            });
    }
}
