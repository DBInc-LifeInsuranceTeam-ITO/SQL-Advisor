function enhanceSqlTuningControls(): void {
  const panel = document.querySelector<HTMLElement>('.sql-workbench .input-panel')
  const controls = panel?.querySelector<HTMLElement>('.top-controls')
  const search = panel?.querySelector<HTMLInputElement>(':scope > input.awr-input')

  if (controls && search && search.parentElement !== controls) {
    controls.insertBefore(search, controls.firstChild)
  }
}

function numericCellValue(value: string): number {
  const normalized = value.replace(/,/g, '').replace(/초/g, '').trim()
  const parsed = Number(normalized)
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY
}

function enhanceSortableTopSqlTable(): void {
  document.querySelectorAll<HTMLTableElement>('.sql-workbench .top-sql-table table').forEach((table) => {
    if (table.dataset.sortableHeaders === 'true') return
    table.dataset.sortableHeaders = 'true'

    const headers = Array.from(table.querySelectorAll<HTMLTableCellElement>('thead th'))
    const sortableColumns = [2, 3, 4, 5]

    sortableColumns.forEach((columnIndex) => {
      const header = headers[columnIndex]
      if (!header) return

      header.classList.add('sortable-header')
      header.dataset.sortDirection = ''
      header.tabIndex = 0
      header.setAttribute('role', 'button')
      header.setAttribute('aria-label', `${header.textContent?.trim() ?? ''} 기준 정렬`)

      const sort = () => {
        const tbody = table.tBodies.item(0)
        if (!tbody) return

        const nextDirection = header.dataset.sortDirection === 'desc' ? 'asc' : 'desc'
        sortableColumns.forEach((otherIndex) => {
          const otherHeader = headers[otherIndex]
          if (!otherHeader || otherHeader === header) return
          otherHeader.dataset.sortDirection = ''
        })
        header.dataset.sortDirection = nextDirection

        const rows = Array.from(tbody.rows)
        rows.sort((left, right) => {
          const leftValue = numericCellValue(left.cells[columnIndex]?.textContent ?? '')
          const rightValue = numericCellValue(right.cells[columnIndex]?.textContent ?? '')
          return nextDirection === 'desc' ? rightValue - leftValue : leftValue - rightValue
        })
        rows.forEach((row) => tbody.appendChild(row))
      }

      header.addEventListener('click', sort)
      header.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault()
          sort()
        }
      })
    })
  })
}

function enhanceAutomaticTopSqlQuery(): void {
  const panel = document.querySelector<HTMLElement>('.sql-workbench .input-panel')
  if (!panel) return

  const connectionSelect = panel.querySelector<HTMLSelectElement>(':scope > label.awr-field select')
  const queryButton = panel.querySelector<HTMLButtonElement>('.query-button')
  if (!connectionSelect || !queryButton) return

  if (connectionSelect.dataset.autoQueryBound !== 'true') {
    connectionSelect.dataset.autoQueryBound = 'true'
    connectionSelect.addEventListener('change', () => {
      const selectedValue = connectionSelect.value
      panel.dataset.autoQueriedConnection = ''
      if (!selectedValue) return
      window.setTimeout(() => {
        if (!queryButton.disabled) {
          panel.dataset.autoQueriedConnection = selectedValue
          queryButton.click()
        }
      }, 0)
    })
  }

  if (
    connectionSelect.value &&
    panel.dataset.autoQueriedConnection !== connectionSelect.value &&
    !panel.querySelector('.top-sql-table tbody tr') &&
    !queryButton.disabled
  ) {
    panel.dataset.autoQueriedConnection = connectionSelect.value
    window.setTimeout(() => queryButton.click(), 0)
  }
}

function enhanceTerminology(): void {
  const modeButtons = document.querySelectorAll<HTMLButtonElement>('.sql-workbench .mode-switch button')
  if (modeButtons[0]?.textContent?.trim() === 'DB 자동 진단') modeButtons[0].textContent = 'DB 연계 분석'

  const heroDescription = document.querySelector<HTMLElement>('.sql-workbench .awr-upload-hero p:last-child')
  if (heroDescription?.textContent?.includes('자동 진단')) {
    heroDescription.textContent = 'DB에 직접 연결해 SQL 성능 지표와 실행계획을 분석하거나 SQL을 직접 입력해 상세 분석합니다.'
  }

  document.querySelectorAll<HTMLElement>('.sql-workbench .result-panel .awr-panel-title').forEach((title) => {
    if (title.textContent?.trim() === '자동 진단 결과') title.textContent = 'DB 연계 분석 결과'
  })

  const resultSummary = document.querySelector<HTMLElement>('.sql-workbench .diagnosis-copy h3')
  if (resultSummary?.textContent?.includes('성능 점검 항목')) {
    resultSummary.textContent = resultSummary.textContent.replace('성능 점검 항목', '성능 분석 항목')
  }
}

function enhanceSqlSource(): void {
  document.querySelectorAll<HTMLPreElement>('.sql-workbench pre.sql-box').forEach((sqlBox) => {
    if (sqlBox.dataset.expandable === 'true') return

    sqlBox.dataset.expandable = 'true'
    sqlBox.classList.add('sql-collapsible')

    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'sql-expand-button'
    button.textContent = '전체 SQL 보기'
    button.setAttribute('aria-expanded', 'false')

    button.addEventListener('click', () => {
      const expanded = sqlBox.classList.toggle('expanded')
      button.textContent = expanded ? 'SQL 접기' : '전체 SQL 보기'
      button.setAttribute('aria-expanded', String(expanded))
    })

    sqlBox.insertAdjacentElement('afterend', button)
  })
}

function enhanceSqlTuningUi(): void {
  enhanceSqlTuningControls()
  enhanceSortableTopSqlTable()
  enhanceAutomaticTopSqlQuery()
  enhanceTerminology()
  enhanceSqlSource()
}

const observer = new MutationObserver(enhanceSqlTuningUi)
observer.observe(document.documentElement, { childList: true, subtree: true })

document.addEventListener('DOMContentLoaded', enhanceSqlTuningUi)
queueMicrotask(enhanceSqlTuningUi)
