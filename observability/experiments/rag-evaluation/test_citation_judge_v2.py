"""Reject evaluator hallucinations and preserve literal conditions; offline only."""
import copy
import unittest

from citation_judge_v2 import (build_request, evaluation_payload, isolated_payload, response_schema,
                              resolve_quote_ids, source_quote_spans, validate_evaluation, validate_isolated, validate_partition)


class LiteralJudgeGuardTest(unittest.TestCase):
    def setUp(self):
        self.row = dict(question='条件是什么？', answer='😀仅代理外部调用生效。[E1]',
            evidence=[dict(evidenceId='E1', text='Only external calls through the proxy are intercepted.')],
            referenceFacts=[dict(factId='F1', text='必须经由代理的外部调用')], missingRequirements=[],
            extraction=dict(units=[dict(unitId='U1', start=0, end=17,
                text='😀仅代理外部调用生效。[E1]', citations=[dict(edgeId='U1-C1', evidenceId='E1',
                    start=11, end=15)])]))
        self.row['extraction']['units'][0]['end'] = len(self.row['answer'])
        self.partition_raw = dict(units=[dict(unitId='U1', parts=[
            dict(text=self.row['answer'], kind='technical')])])
        self.partition = validate_partition(self.row, self.partition_raw)
        self.payload = evaluation_payload(self.row, self.partition)
        self.verdict = dict(claims=[dict(claimId='U1-P1', supported=True,
            support=[dict(evidenceId='E1', quotes=['Only external calls through the proxy'])])],
            edges=[dict(edgeId='U1-P1-U1-C1', supported=True, sourceQuotes=['external calls through the proxy'])],
            facts=[dict(factId='F1', covered=True, disposition='answered', answerQuotes=['仅代理外部调用生效'])],
            missing=[])

    def test_partition_keeps_conditions_and_unicode_positions(self):
        self.assertEqual(self.partition['claims'][0]['text'], self.row['answer'])
        self.assertEqual(self.partition['claims'][0]['end'], len(self.row['answer']))

    def test_partition_cannot_add_text(self):
        self.partition_raw['units'][0]['parts'][0]['text'] += '额外事实'
        with self.assertRaises(ValueError):
            validate_partition(self.row, self.partition_raw)

    def test_partition_cannot_drop_condition(self):
        self.partition_raw['units'][0]['parts'][0]['text'] = '调用生效。[E1]'
        with self.assertRaises(ValueError):
            validate_partition(self.row, self.partition_raw)

    def test_partition_cannot_assign_citation_ids(self):
        self.partition_raw['units'][0]['parts'][0]['citationIds'] = ['E99']
        with self.assertRaises(ValueError):
            validate_partition(self.row, self.partition_raw)

    def test_valid_exact_proof(self):
        self.assertEqual(validate_evaluation(self.payload, self.verdict), self.verdict)

    def test_json_mode_requirement_checked_before_request(self):
        with self.assertRaises(ValueError):
            build_request('qwen3.8-flash', [dict(role='system', content='只返回对象')], 2048)
        request = build_request('qwen3.8-flash', [dict(role='system', content='只返回 JSON 对象')], 2048)
        self.assertEqual(request['response_format'], dict(type='json_object'))

    def test_strict_schema_fixes_proof_field_case_and_allowed_ids(self):
        schema = response_schema('evaluate', self.payload)
        proof = schema['properties']['claims']['items']['properties']['support']['items']
        self.assertIn('evidenceId', proof['properties'])
        self.assertNotIn('EvidenceId', proof['properties'])
        self.assertEqual(proof['properties']['evidenceId']['enum'], ['E1'])
        self.assertFalse(proof['additionalProperties'])
        request = build_request('qwen3.8-flash', [dict(role='user', content='对象')], 4096, schema)
        self.assertEqual(request['response_format']['type'], 'json_schema')
        self.assertTrue(request['response_format']['json_schema']['strict'])

    def test_context_has_all_sources_but_no_actual_citation_choice(self):
        payload = isolated_payload('context', self.row, self.partition)
        self.assertNotIn('edges', payload)
        self.assertNotIn('answer', payload)
        self.assertNotIn('[E1]', payload['claims'][0]['text'])
        self.assertEqual([dict(evidenceId=e['evidenceId'], text=e['text']) for e in payload['evidence']], self.row['evidence'])
        self.assertEqual(payload['claims'][0]['text'], '😀仅代理外部调用生效。')

    def test_edge_has_only_its_fixed_source(self):
        payload = isolated_payload('edges', self.row, self.partition)
        self.assertNotIn('evidence', payload)
        self.assertEqual(payload['edges'][0]['sourceText'], self.row['evidence'][0]['text'])
        parsed = dict(edges=self.verdict['edges'])
        self.assertEqual(validate_isolated('edges', payload, parsed), parsed)

    def test_context_cannot_return_an_edge_verdict(self):
        payload = isolated_payload('context', self.row, self.partition)
        with self.assertRaises(ValueError):
            validate_isolated('context', payload, dict(claims=self.verdict['claims'], edges=[]))

    def test_quote_selector_resolves_original_newlines_without_model_rewriting(self):
        quotes = source_quote_spans('E1', 'Default\nsetting is\nREQUIRED.\nNext fact.')
        self.assertEqual(''.join(q['text'] for q in quotes), 'Default\nsetting is\nREQUIRED.\nNext fact.')
        payload = dict(evidence=[dict(evidenceId='E1', quotes=quotes)])
        parsed = dict(claims=[dict(claimId='C1', supported=True,
            support=[dict(evidenceId='E1', quoteIds=['E1-Q1'])])])
        result = resolve_quote_ids('context', payload, parsed)
        self.assertEqual(result['claims'][0]['support'][0]['quotes'], ['Default\nsetting is\nREQUIRED.'])

    def test_quote_selector_cannot_choose_other_edge_source(self):
        payload = dict(edges=[dict(edgeId='edge1', sourceQuotes=source_quote_spans('edge1', 'Fact.'))])
        parsed = dict(edges=[dict(edgeId='edge1', supported=True, sourceQuoteIds=['edge2-Q1'])])
        with self.assertRaises(ValueError):
            resolve_quote_ids('edges', payload, parsed)

    def test_no_citation_means_no_edge_tasks(self):
        self.row['extraction']['units'][0]['citations'] = []
        self.payload = evaluation_payload(self.row, self.partition)
        self.assertEqual(self.payload['edges'], [])
        with self.assertRaises(ValueError):
            validate_evaluation(self.payload, self.verdict)

    def test_unrelated_source_quote_rejected(self):
        self.verdict['edges'][0]['sourceQuotes'] = ['Read Committed is the default']
        with self.assertRaises(ValueError):
            validate_evaluation(self.payload, self.verdict)

    def test_paraphrased_quote_rejected(self):
        self.verdict['claims'][0]['support'][0]['quotes'] = ['External calls are always proxied.']
        with self.assertRaises(ValueError):
            validate_evaluation(self.payload, self.verdict)

    def test_invented_claim_id_rejected(self):
        self.verdict['claims'][0]['claimId'] = 'new-claim'
        with self.assertRaises(ValueError):
            validate_evaluation(self.payload, self.verdict)

    def test_unknown_citation_cannot_pass(self):
        self.payload['edges'][0]['sourceText'] = None
        with self.assertRaises(ValueError):
            validate_evaluation(self.payload, self.verdict)

    def test_covered_fact_requires_original_answer_quote(self):
        self.verdict['facts'][0]['answerQuotes'] = ['Gold 说经由代理才生效']
        with self.assertRaises(ValueError):
            validate_evaluation(self.payload, self.verdict)

    def test_missing_requirement_accepts_refusal_with_background(self):
        payload = copy.deepcopy(self.payload)
        payload['answer'] += ' 资料未提供秒数，不能确定。'
        payload['missingRequirements'] = [dict(requirementId='unknown', text='具体秒数')]
        verdict = copy.deepcopy(self.verdict)
        verdict['missing'] = [dict(requirementId='unknown', refused=True, answerQuotes=['资料未提供秒数，不能确定'])]
        self.assertEqual(validate_evaluation(payload, verdict), verdict)

    def test_boolean_cannot_be_truthy_string(self):
        self.verdict['edges'][0]['supported'] = 'false'
        with self.assertRaises(ValueError):
            validate_evaluation(self.payload, self.verdict)

    def test_disposition_refused_is_separate_from_omission(self):
        self.verdict['facts'][0] = dict(factId='F1', covered=False, disposition='refused', answerQuotes=[])
        with self.assertRaises(ValueError):
            validate_evaluation(self.payload, self.verdict)


if __name__ == '__main__':
    unittest.main()
