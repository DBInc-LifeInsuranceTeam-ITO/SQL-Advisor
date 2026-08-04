function enhanceSqlTuningControls(): void {
  const panel = document.querySelector<HTMLElement>('.sql-workbench .input-panel')
  const controls = panel?.querySelector<HTMLElement>('.top-controls')
  const search = panel?.querySelector<HTMLInputElement>(':scope > input.awr-input')

  if (controls && search && search.parentElement !== controls) {
    controls.insertBefore(search, controls.firstChild)
  }
}

function enhanceAutoLoadOnConnectionChange(): void {
  const panel = document.querySelector<HTMLElement>('.sql-workbench .input-panel')
  const connectionSelect = panel?.querySelector<HTMLSelectElement>(':scope > label.awr-field select')
  if (!connectionSelect || connectionSelect.dataset.autoLoadBound === 'true') return

  connectionSelect.dataset.autoLoadBound = 'true'
  connectionSelect.addEventListener('change', () => {
    if (!connectionSelect.value) return
    window.setTimeout(() => {
      const queryButton = panel?.querySelector<HTMLButtonElement>('.query-button')
      if (queryButton && !queryButton.disabled) queryButton.click()
    }, 0)
  })

  if (connectionSelect.value) {
    window.setTimeout(() => {
      const table = panel?.querySelector('.top-sql-table tbody tr')
      const queryButton = panel?.querySelector<HTMLButtonElement>('.query-button')
      if (!table && queryButton && !queryButton.disabled) queryButton.click()
    }, 0)
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
    const sortableColumns = new Map<number, string>([
      [2, '평균시간'],
      [3, 'Buffer'],
      [4, 'Disk'],
      [5, 'Rows']
    ])

    sortableColumns.forEach((label, columnIndex) => {
      const header = headers[columnIndex]
      if (!header) return

      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'table-sort-button'
      button.dataset.direction = ''
      button.setAttribute('aria-label', `${label} 정렬`)

      const text = document.createElement('span')
      text.className = 'table-sort-label'
      text.textContent = label

      const indicator = document.createElement('span')
      indicator.className = 'table-sort-indicator'
      indicator.setAttribute('aria-hidden', 'true')

      button.append(text, indicator)

      button.addEventListener('click', () => {
        const tbody = table.tBodies.item(0)
        if (!tbody) return

        const nextDirection = button.dataset.direction === 'desc' ? 'asc' : 'desc'
        headers.forEach((otherHeader) => {
          const otherButton = otherHeader.querySelector<HTMLButtonElement>('.table-sort-button')
          if (!otherButton) return
          const otherIndicator = otherButton.querySelector<HTMLElement>('.table-sort-indicator')
          if (otherButton !== button) {
            otherButton.dataset.direction = ''
            otherButton.classList.remove('active')
            if (otherIndicator) otherIndicator.textContent = ''
            otherHeader.removeAttribute('aria-sort')
          }
        })

        button.dataset.direction = nextDirection
        button.classList.add('active')
        indicator.textContent = nextDirection === 'desc' ? '▼' : '▲'
        header.setAttribute('aria-sort', nextDirection === 'desc' ? 'descending' : 'ascending')

        const rows = Array.from(tbody.rows)
        rows.sort((left, right) => {
          const leftValue = numericCellValue(left.cells[columnIndex]?.textContent ?? '')
          const rightValue = numericCellValue(right.cells[columnIndex]?.textContent ?? '')
          return nextDirection === 'desc' ? rightValue - leftValue : leftValue - rightValue
        })
        rows.forEach((row) => tbody.appendChild(row))
      })

      header.replaceChildren(button)
    })
  })
}

function enhanceSqlSource(): void {
  document.querySelectorAll<HTMLPreElement>('.sql-workbench pre.sql-box').forEach((sqlBox) => {
    if (sqlBox.dataset.expandable === 'true') return

    sqlBox.dataset.expandable = 'true'
    sqlBox.classList.add('sql-collapsible')

    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'sql-expand-button'
    button.textContent = '전체 보기'
    button.setAttribute('aria-expanded', 'false')

    button.addEventListener('click', () => {
      const expanded = sqlBox.classList.toggle('expanded')
      button.textContent = expanded ? '접기' : '전체 보기'
      button.setAttribute('aria-expanded', String(expanded))
    })

    sqlBox.insertAdjacentElement('afterend', button)
  })
}

function enhanceSqlTuningUi(): void {
  enhanceSqlTuningControls()
  enhanceAutoLoadOnConnectionChange()
  enhanceSortableTopSqlTable()
  enhanceSqlSource()
}

const observer = new MutationObserver(enhanceSqlTuningUi)
observer.observe(document.documentElement, { childList: true, subtree: true })

document.addEventListener('DOMContentLoaded', enhanceSqlTuningUi)
queueMicrotask(enhanceSqlTuningUi)
