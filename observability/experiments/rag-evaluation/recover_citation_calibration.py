"""One explicit recovery probe after a no-response ReadTimeout, not an automatic retry."""
from datetime import datetime, timezone
import json

from citation_judge_v2 import RUN, call, read, resolve_quote_ids, sha, validate_isolated, write


def main():
    source = RUN / 'calls/cal-missing-proxy-condition-coverage'
    failure = read(source.with_suffix('.result.json'))
    if not (failure['failure'] == 'ReadTimeout' and failure['usage'] is None and not failure['answer']
            and failure['httpStatus'] is None):
        raise ValueError('Only the captured no-response timeout may have this one recovery probe')
    plan = read(RUN / 'judge-plan.json')
    request = read(source.with_suffix('.request.json'))
    payload = read(source.with_suffix('.payload.json'))
    directory = RUN / 'explicit-recovery-probe'
    directory.mkdir(exist_ok=False)
    write(directory / 'recovery-plan.json', dict(at=datetime.now(timezone.utc).isoformat(),
        scope='one separate controlled public calibration request after ReadTimeout; primary failure remains',
        originalRequestSha256=sha(source.with_suffix('.request.json')),
        originalPayloadSha256=sha(source.with_suffix('.payload.json')),
        sourceUsageStatus='unknown, not zero', maximumExternalCalls=1,
        model=request['model'], maximumCompletionTokens=request['max_completion_tokens'],
        responseSchemaUnchanged=True, noAutomaticRetry=True, formalQualityEligible=False))
    prefix = directory / 'cal-missing-proxy-condition-coverage-recovery'
    write(prefix.with_suffix('.payload.json'), payload)
    parsed = call(prefix, plan, request['messages'], request['max_completion_tokens'],
                  request['response_format']['json_schema']['schema'])
    try:
        checked = validate_isolated('coverage', payload, resolve_quote_ids('coverage', payload, parsed))
        write(prefix.with_suffix('.validated.json'), checked)
    except ValueError as exception:
        write(prefix.with_suffix('.guard-failure.json'), dict(error=str(exception), scoreEligible=False))
        raise
    print(json.dumps(dict(separateRecoveryProbe=True, literalGuardsPassed=True, primaryFailurePreserved=True)))


if __name__ == '__main__':
    main()
