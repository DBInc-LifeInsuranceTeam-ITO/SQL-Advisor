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
      button.innerHTML = `<span>${label}</span><span class="table-sort-indicator">↕</span>`

      button.addEventListener('click', () => {
        const tbody = table.tBodies.item(0)
        if (!tbody) return

        const nextDirection = button.dataset.direction === 'desc' ? 'asc' : 'desc'
        headers.forEach((otherHeader) => {
          const otherButton = otherHeader.querySelector<HTMLButtonElement>('.table-sort-button')
          if (!otherButton || otherButton === button) return
          otherButton.dataset.direction = ''
          const indicator = otherButton.querySelector<HTMLElement>('.table-sort-indicator')
          if (indicator) indicator.textContent = '↕'
        })

        button.dataset.direction = nextDirection
        const indicator = button.querySelector<HTMLElement>('.table-sort-indicator')
        if (indicator) indicator.textContent = nextDirection === 'desc' ? '▼' : '▲'

        const rows = Array.from(tbody.rows)
        rows.sort((left, right) => {
          const leftValue = numericCellValue(left.cells[columnIndex]?.textContent ?? '')
          const rightValue = numericCellValue(right.cells[columnIndex]?.textContent ?? '')
          return nextDirection === 'desc' ? rightValue - leftValue : leftValue - rightValue
        })
        rows.forEach((row) => tbody.appendChild(row))
      })

      header.textContent = ''
      header.appendChild(button)
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
  enhanceSortableTopSqlTable()
  enhanceSqlSource()
}

const observer = new MutationObserver(enhanceSqlTuningUi)
observer.observe(document.documentElement, { childList: true, subtree: true })

document.addEventListener('DOMContentLoaded', enhanceSqlTuningUi)
queueMicrotask(enhanceSqlTuningUi)
