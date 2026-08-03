const EXACT_TEXT: Record<string, string> = {
  'SQL Performance Tuning': 'SQL 성능 분석',
  Input: '분석 대상 입력',
  'Direct DB': 'DB에서 SQL 조회',
  'Manual Input': 'SQL 직접 입력',
  'Target DB': '분석 대상 DB',
  Refresh: '새로고침',
  'Hide Form': '연결 등록 닫기',
  'New Connection': 'DB 연결 등록',
  'Saved Connection': '등록된 DB 연결',
  'Select connection': 'DB 연결을 선택하세요',
  'Connection Name': '연결 이름',
  Username: '사용자 계정',
  Password: '비밀번호',
  'Test Connection': '연결 확인',
  Testing: '연결 확인 중',
  'Testing...': '연결 확인 중...',
  'Save Connection': '연결 저장',
  'Saving...': '저장 중...',
  'Hide SQL Text': 'SQL 직접 입력 닫기',
  'SQL Text fallback': 'SQL을 직접 입력하기',
  'Delete Connection': '연결 삭제',
  'Deleting...': '삭제 중...',
  'SQL Count': '조회 건수',
  'Hide tuned SQL_ID': '분석 완료 SQL 숨기기',
  'Load SQL': '부하 SQL 조회',
  'Loading...': '조회 중...',
  Elapsed: '총 수행시간(초)',
  'Buffer Gets': '메모리 블록 조회량',
  'Disk Reads': '디스크 읽기량',
  Executions: '실행 횟수',
  Tuned: '분석 완료',
  'Existing Indexes': '관련 테이블의 기존 인덱스',
  'Used Indexes': '실행계획에서 사용된 인덱스',
  Table: '테이블',
  Index: '인덱스',
  Columns: '컬럼',
  Uniqueness: '고유 여부',
  Status: '상태',
  Visibility: '사용 가능 여부',
  Logging: '로깅',
  Stats: '통계정보',
  Access: '접근 방식',
  'Collected SQL Text': '수집된 SQL',
  'Bind Samples': '바인드 변수 예시',
  'Execution Plan': '실행계획',
  'Tuning Result': 'SQL 분석 결과',
  Confidence: '분석 신뢰도',
  'Index Candidates': '인덱스 검토안',
  'Missing Inputs': '추가 필요 정보',
  Symptoms: '확인된 성능 문제',
  'Rewrite Checks': 'SQL 개선 검토사항',
  Validation: '적용 전 확인사항',
  'Index candidate': '인덱스 검토안',
  'DDL Candidate': '인덱스 생성문 예시',
  'Large-table build option': '대용량 테이블 생성 옵션',
  'Expected benefit:': '예상 효과:',
  Copy: '복사',
  Copied: '복사 완료',
  'Ask About This Tuning': '분석 결과 추가 질문',
  Ask: '질문하기',
  'Asking...': '답변 생성 중...',
  'Loading questions...': '이전 질문을 불러오는 중...',
  'Tuning History': 'SQL 분석 이력',
  'No tuning result selected.': '분석할 SQL을 선택하거나 직접 입력한 뒤, 분석 실행 버튼을 눌러줘.',
  'No concrete index DDL candidate was generated.': '현재 수집된 정보만으로는 구체적인 인덱스 생성안을 만들 수 없어.',
  'No existing indexes collected for the referenced tables.': '관련 테이블의 기존 인덱스 정보를 수집하지 못했어.',
  'No used indexes found in the collected execution plan.': '수집된 실행계획에서 사용된 인덱스를 찾지 못했어.',
  'No SQL text collected.': 'SQL 문장을 수집하지 못했어.',
  'No bind samples collected.': '바인드 변수 값을 수집하지 못했어.',
  'No execution plan collected.': '실행계획을 수집하지 못했어.'
}

const DYNAMIC_TEXT: Array<[RegExp, (match: RegExpMatchArray) => string]> = [
  [/^SQL Tuning - (.+)$/, (match) => `SQL 분석 결과 · ${match[1]}`],
  [/^confidence\s+(.+)$/i, (match) => `분석 신뢰도 ${match[1]}`],
  [/^Tuning\.\.\.$/, () => '분석 중...'],
  [/^Tune SQL$/, () => 'SQL 분석 실행'],
  [/^Run Tuning$/, () => 'SQL 분석 실행'],
  [/^Load (\d+) SQL$/, (match) => `부하 SQL ${match[1]}건 조회`]
]

function translateText(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) return value

  const exact = EXACT_TEXT[trimmed]
  if (exact) {
    return value.replace(trimmed, exact)
  }

  for (const [pattern, replacement] of DYNAMIC_TEXT) {
    const match = trimmed.match(pattern)
    if (match) {
      return value.replace(trimmed, replacement(match))
    }
  }

  return value
}

function shouldSkip(node: Text): boolean {
  const parent = node.parentElement
  if (!parent) return true

  return Boolean(parent.closest('pre, code, textarea, input, .sql-tuning-markdown'))
}

function translateSqlTuningScreen(root: ParentNode): void {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []

  while (walker.nextNode()) {
    nodes.push(walker.currentNode as Text)
  }

  for (const node of nodes) {
    if (shouldSkip(node)) continue

    const translated = translateText(node.nodeValue ?? '')
    if (translated !== node.nodeValue) {
      node.nodeValue = translated
    }
  }
}

export function installSqlTuningKoreanUi(): void {
  const apply = () => {
    if (window.location.pathname !== '/sql-tuning') return

    const appRoot = document.querySelector('#app')
    if (appRoot) translateSqlTuningScreen(appRoot)
  }

  const observer = new MutationObserver(apply)
  observer.observe(document.body, {
    childList: true,
    subtree: true,
    characterData: true
  })

  window.addEventListener('popstate', apply)
  document.addEventListener('click', () => queueMicrotask(apply))
  queueMicrotask(apply)
}
