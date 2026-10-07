"""Freeze actual business persistence regression inputs before running them."""
import hashlib
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
name = sys.argv[1]
assert re.fullmatch(r'business-persistence-[a-z-]+-2026100[56]-r[1-9][0-9]*', name)
run = ROOT / 'observability/experiments/voice-frame-pipeline/runs' / name
run.mkdir()
(run / 'sources').mkdir()
files = [
    'app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java',
    'app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java',
    'app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewEvaluationService.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamConsumer.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/repository/VoiceInterviewSessionRepository.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/repository/VoiceInterviewEvaluationRepository.java',
    'app/src/test/java/interview/guide/modules/interview/service/InterviewSessionDeletionTest.java',
    'app/src/test/java/interview/guide/modules/interview/service/InterviewPersistenceServiceTest.java',
    'app/src/test/java/interview/guide/modules/interview/service/InterviewTrainingHistoryIntegrationTest.java',
    'observability/experiments/interview-adaptation/TEXT_DELETE_CACHE_DESIGN_2026-10-05.md',
    'observability/experiments/interview-adaptation/prepare_business_persistence.py',
]
optional = [
    'app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewEvaluationPersistenceService.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/controller/VoiceInterviewController.java',
    'app/src/test/java/interview/guide/modules/voiceinterview/service/VoiceEvaluationCommitIntegrationTest.java',
    'observability/experiments/interview-adaptation/VOICE_REPORT_COMMIT_DESIGN_2026-10-06.md',
]
absent = [name for name in optional if not (ROOT / name).exists()]
files += [name for name in optional if (ROOT / name).exists()]
manifest = []
for i, name in enumerate(files):
    path = (ROOT / name).resolve()
    raw = Path('\\\\?\\' + str(path)).read_bytes() if os.name == 'nt' else path.read_bytes()
    frozen = f'sources/{i:03d}-{path.name}'
    (run / frozen).write_bytes(raw)
    manifest.append(dict(path=name, frozen=frozen, sha256=hashlib.sha256(raw).hexdigest()))
(run / 'source-manifest.json').write_text(json.dumps(dict(files=manifest, absent=absent), indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, files=len(files), externalModelCalls=0)))
