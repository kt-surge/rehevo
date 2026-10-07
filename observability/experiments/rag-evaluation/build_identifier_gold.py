"""Source/theme test Gold frozen before any retrieval; same-Agent source review."""
import json
from datetime import datetime, timezone

from fact_gold import digest, load_gold
from prepare_identifier_sources import PACK, verify_strategy

M, H, B = 'java21-matcher', 'python313-heapq', 'python313-bisect'


def main():
    strategy = verify_strategy()
    if (PACK/'manifest.json').exists():
        raise ValueError('Frozen Gold may not be replaced')
    sources = json.loads((PACK/'sources.manifest.json').read_text(encoding='utf-8'))
    docs = sources['documents']
    texts = {d['documentId']:(PACK/d['canonicalPath']).read_text(encoding='utf-8') for d in docs}
    facts, cases = [], []
    reviewed = datetime.now(timezone.utc).isoformat()

    def fact(fid, doc, head, tail, after=None, before=None):
        text = texts[doc]
        left = text.index(after) if after else (text.index('Method Details') if doc == M else 0)
        right = text.index(before, left) if before else len(text)
        if text[left:right].count(head) != 1:
            raise ValueError('Missing/ambiguous pre-test fact: '+fid)
        start = text.index(head, left, right)
        end = text.index(tail, start, right)+len(tail)
        if not 20 < end-start < 2500:
            raise ValueError('Invalid fact size: '+fid)
        facts.append({'factId':fid,'documentId':doc,'start':start,'end':end,
                      'exactQuote':text[start:end],'textRole':'documentation_prose',
                      'allowInterChunkWhitespaceGap':True,
                      'mappingPolicyReason':'Pre-retrieval prose annotation: only whitespace lost between adjacent exact spans may bridge; no code or semantic loss accepted.'})

    def case(cid, question, answer, ids, category='multi_requirement'):
        lookup = {f['factId']:f['documentId'] for f in facts}
        cases.append({'id':cid,'split':'test','question':question,'questionLanguage':'zh','sourceLanguage':'en',
                      'category':category,'answerable':True,
                      'sourceDocumentIds':list(dict.fromkeys(lookup[f] for f in ids)),
                      'requirements':[{'id':cid+'-r'+str(i+1),'alternatives':[{'factIds':[fid]}]}
                                      for i,fid in enumerate(ids)],
                      'referenceAnswer':answer,'referenceFactIds':ids,
                      'review':{'status':'agent_verified','reviewerType':'agent','reviewer':'same-executing-agent',
                                'reviewedAt':reviewed},
                      'semanticReviewNotes':'Source conditions and exceptions reviewed by the same Agent before retrieval. Not human or independently blinded review.'})

    fact('m-matches',M,'Attempts to match the entire region against the pattern.','against the pattern.')
    fact('m-looking',M,'Attempts to match the input sequence, starting at the beginning of the',
         'require that the entire region be matched.')
    fact('m-find',M,"This method starts at the beginning of this matcher's region, or, if",
         'match.')
    fact('m-find-start',M,'Resets this matcher and then attempts to find the next subsequence of',
         'length of the input sequence.')
    fact('m-group',M,'If the match was successful but the group specified failed to match',
         'matches the empty string in the input.',after='group\npublic\nString\ngroup\n(int',
         before='group\npublic\nString\ngroup\n(\nString')
    fact('m-start',M,'Returns the start index of the previous match.',
         'or if the previous match operation failed',after='start\npublic\nint\nstart\n()',
         before='start\npublic\nint\nstart\n(int')
    fact('m-end',M,'Returns the offset after the last character matched.','last character matched.')
    fact('m-reset',M,'Resetting a matcher discards all of its explicit state information\n and sets its append position',
         'unaffected.',after='reset\npublic\nMatcher\nreset\n()',before='reset\npublic\nMatcher\nreset\n(\nCharSequence')
    fact('m-pattern',M,'This method causes this matcher to lose information',
         'last append position is unaffected.')
    fact('m-quote',M,'The\nString\nproduced will match the sequence of characters',
         "dollar signs ('$') will be given no special meaning.")
    fact('m-results',M,'This method does not reset this matcher.  Matching starts on',
         'modification is detected.')

    fact('h-pop',H,'Pop and return the smallest item from the','heap[0]\n.',
         after='heapq.\nheappop\n',before='heapq.\nheappushpop\n')
    fact('h-heapify',H,'Transform list\nx\ninto a heap, in-place, in linear time.','linear time.')
    fact('h-replace',H,'Pop and return the smallest item from the\nheap\n, and also push the new',
         'IndexError\nis raised.')
    fact('h-replace-smaller',H,'The value returned may be larger than the','on the heap.')
    fact('h-merge',H,'Merge multiple sorted inputs into a single sorted output',
         'streams is already sorted (smallest to largest).')
    fact('h-reverse',H,'reverse\nis a boolean value.  If set to',
         'be sorted from largest to smallest.')
    fact('h-small',H,'Return a list with the\nn\nsmallest elements from the dataset',
         'sorted(iterable,\nkey=key)[:n]\n.')
    fact('h-size',H,'The latter two functions perform best for smaller values of',
         'the iterable into an actual heap.')

    fact('b-left',B,'Locate the insertion point for\nx\nin\na\nto maintain sorted order.',
         'any existing entries.')
    fact('b-right',B,'Similar to\nbisect_left()\n, but returns an insertion point which comes',
         '\nin\na\n.')
    fact('b-search-key',B,'key\nspecifies a\nkey function\nof one argument that is used to',
         'no key function is called.',after='bisect.\nbisect_left\n',before='bisect.\nbisect_right\n')
    fact('b-insert-key',B,'To support inserting records in a table, the',
         'applied to\nx\nfor the search step but not for the insertion step.',
         after='bisect.\ninsort_left\n',before='bisect.\ninsort_right\n')
    fact('b-insert-complexity',B,'Keep in mind that the\nO\n(log',
         'insertion step.',after='bisect.\ninsort_left\n',before='bisect.\ninsort_right\n')
    fact('b-equality',B,'Accordingly, the functions never call an','point between values in an array.')
    fact('b-threads',B,'The functions in this module are not thread-safe.',
         'may result in the list becoming unsorted.')
    fact('b-key-cache',B,'The search functions are stateless and discard key function results after',
         '(as shown in the examples section below).')

    case('identifier-test-m-01','Matcher.matches 与 Matcher.lookingAt 都必须匹配整个 region 吗？',
         'matches 要匹配整个 region；lookingAt 从 region 开始匹配前缀，不要求整个 region。',['m-matches','m-looking'])
    case('identifier-test-m-02','Matcher.find() 成功后下一次从哪里找？find(int start) 会先 reset 吗，start 超过输入长度会怎样？',
         '未 reset 且前次成功时从上次未匹配首字符找；带 start 会先 reset，start 小于0或大于输入长度抛越界异常。',['m-find','m-find-start'],'condition')
    case('identifier-test-m-03','Matcher.group(int) 在整体匹配成功、该捕获组没有参与时返回什么，与该组成功匹配空串有什么区别？',
         '未参与返回 null；成功匹配空串返回空字符串。',['m-group'],'negation')
    case('identifier-test-m-04','Matcher.start 与 Matcher.end 返回的区间怎样表示？从未匹配或上次匹配失败时 start 能照常取索引吗？',
         'start 是首字符索引，end 是末字符后的偏移；未尝试或上次失败时 start 抛 IllegalStateException。',['m-start','m-end'],'condition')
    case('identifier-test-m-05','Matcher.reset 会清除 explicit state 并恢复完整输入 region 吗，会同时重置 anchoring 与 transparency 吗？',
         '清显式状态、append位置归零、region恢复完整输入；锚点与透明边界不受影响。',['m-reset'],'negation')
    case('identifier-test-m-06','Matcher.usePattern 与 Matcher.reset 对位置、上一次分组结果和 append position 的处理相同吗？',
         '换 Pattern 丢上次分组信息，保持输入位置与append位置；reset清显式状态、append归零、恢复region，边界配置不变。',['m-pattern','m-reset'])
    case('identifier-test-m-07','Matcher.quoteReplacement 对替换字符串里的反斜线和美元符号有什么作用？',
         '按字面序列处理，不赋予反斜线和美元符号特殊替换含义。',['m-quote'],'identifier')
    case('identifier-test-m-08','Matcher.results 会立即 reset 并开始匹配吗？处理流时修改 Matcher 是否有绝对的并发修改检测保证？',
         '不reset，终端操作开始时才匹配；流中不应修改状态，fail-fast仅best-effort，检测到则抛并发修改异常。',['m-results'],'condition')

    case('identifier-test-h-01','heapq.heappop 对空堆返回 None 吗？heapify 会创建新堆，时间复杂度是多少？',
         'heappop 空堆抛 IndexError；heapify原地线性时间转换列表。',['h-pop','h-heapify'],'negation')
    case('identifier-test-h-02','heapq.heapreplace 与 heapq.heappushpop 返回的值相同吗？前者能在空堆常规成功，返回值能比新 item 大吗？',
         'heapreplace空堆抛IndexError、大小不变，返回旧堆最小值可比新项大；heappushpop返回二者较小值，留下较大值。',['h-replace','h-replace-smaller'])
    case('identifier-test-h-03','heapq.merge 会先把全部数据读进内存吗，reverse=True 是否接受升序输入？',
         '返回迭代器而非全量预读，默认要求输入已升序；reverse=True要求每个输入降序。',['h-merge','h-reverse'],'condition')
    case('identifier-test-h-04','heapq.nsmallest 的返回形式和 key 语义是什么？n 很大或 n==1 时官方建议怎样选？',
         '返回最小n项列表，key提供比较键；n大时sorted更高效，n==1用min/max更高效。',['h-small','h-size'])

    case('identifier-test-b-01','bisect.bisect_left 与 bisect.bisect_right 遇到相等项时，插入点分别在相等项哪边？',
         'left在所有相等项前，right在相等项后。',['b-left','b-right'],'identifier')
    case('identifier-test-b-02','bisect_left(key=...) 与 insort_left(key=...) 会不会都把 key 应用到 x？插入时是否把 key(x) 放进列表？',
         'bisect搜索key仅应用列表项、不用于x；insort搜索用key(x)，插入保留原x。',['b-search-key','b-insert-key'],'condition')
    case('identifier-test-b-03','insort_left 既然用二分搜索，整体是否为 O(log n)？',
         'O(n)的插入支配O(log n)搜索。',['b-insert-complexity'],'negation')
    case('identifier-test-b-04','bisect 查找插入点会调用 __eq__ 确认相等吗，多线程对同一列表并发 insort_left 是否安全？',
         '不调用__eq__，只比较__lt__；模块不线程安全，同序列并发操作/修改结果未定义，并发插入可破坏有序性。',['b-equality','b-threads'],'condition')
    case('identifier-test-b-05','循环 bisect 搜索会保留 key 函数结果避免重复计算吗，key 昂贵时可怎样优化？',
         '无状态且丢弃key结果，可能反复计算；可用functools.cache或预先计算键数组。',['b-key-cache'],'negation')

    case('identifier-test-cross-01','分别说明 Matcher.matches 的匹配范围，以及 heapq.merge 是否要求输入已排序并返回惰性迭代器。',
         'matches覆盖整个region；merge默认已升序输入，返回迭代器而非全量读内存。',['m-matches','h-merge'],'cross_document')
    case('identifier-test-cross-02','Matcher.find(int start) 对 start 越界怎么处理；bisect_left 对相等项返回哪侧插入点？',
         'find负数或大于输入长度抛越界；bisect_left在相等项前。',['m-find-start','b-left'],'cross_document')
    case('identifier-test-cross-03','Matcher.start 上次匹配失败还能取索引吗；heapq.heappop 对空堆会怎样？',
         '分别抛IllegalStateException和IndexError。',['m-start','h-pop'],'cross_document')
    case('identifier-test-cross-04','Matcher.reset 对边界配置有什么影响；bisect 对 key 计算结果是否会自动缓存复用？',
         'reset不改锚点/透明边界；bisect丢弃key结果，可自己缓存或预计算。',['m-reset','b-key-cache'],'cross_document')
    case('identifier-test-cross-05','heapq.heapreplace 会改变堆大小吗；bisect.insort_left 的整体复杂度是否也仅为对数级？',
         'heapreplace大小不变、空堆抛异常；insort线性插入支配，整体O(n)。',['h-replace','b-insert-complexity'],'cross_document')
    case('identifier-test-cross-06','Matcher.quoteReplacement 是否保留美元符号特殊含义；bisect 会用 __eq__ 查找相等位置吗？',
         'quoteReplacement转为字面替换；bisect不调用__eq__而只用__lt__定位插入点。',['m-quote','b-equality'],'cross_document')

    for i,(question,doc) in enumerate([
        ('Rehevo 线上 Matcher 匹配错误率和正则处理 P95 是多少？',M),
        ('Rehevo 生产 heapq 队列实际积压峰值与吞吐是多少？',H),
        ('Rehevo 当前已缓存多少 bisect key 结果，命中率是多少？',B),
        ('Rehevo 实际使用 Matcher.results 后内存用量降低了百分之多少？',M),
    ],1):
        case(f'identifier-test-unavailable-{i:02d}',question,'官方资料不能确定项目线上配置或实测数据。',[],'unanswerable')
        cases[-1].update(answerable=False,sourceDocumentIds=[doc],
                         searchedSourceDocumentIds=[d['documentId'] for d in docs],
                         unanswerableReason='Official API reference contains no Rehevo production measurements or effective configuration.')
    for name,rows in [('facts.jsonl',facts),('test.jsonl',cases)]:
        (PACK/name).write_text(''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in rows),encoding='utf-8')
    manifest={'schemaVersion':'rehevo-fact-gold-v1','packId':'identifier-source-test-20261002-r1',
              'documents':docs,'factsPath':'facts.jsonl','factsSha256':digest(PACK/'facts.jsonl'),
              'splits':{'test':{'path':'test.jsonl','sha256':digest(PACK/'test.jsonl'),'cases':len(cases),
                               'answerable':sum(c['answerable'] for c in cases)}},
              'frozenAt':reviewed,'humanReviewed':False,'independentReviewer':False,
              'freezeScope':'new-source-input-after-strategy-freeze-before-any-test-retrieval',
              'strategyFreezeSha256':strategy,'excludedGoldManifests':sources['excludedGoldManifests'],
              'limitations':['Same Agent knows the candidate; no independent blinded review or random real-user sample',
                             'Chinese questions over English official texts; bounded controlled corpus',
                             'Retrieval coverage is not generation quality, refusal success or production gain']}
    (PACK/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    gold=load_gold(PACK/'manifest.json','agent_verified')
    print(json.dumps({'documents':len(gold.documents),'facts':len(gold.facts),'cases':len(gold.cases),
                      'answerable':sum(c['answerable'] for c in cases),'sha256':digest(PACK/'manifest.json')}),flush=True)


if __name__=='__main__':main()
