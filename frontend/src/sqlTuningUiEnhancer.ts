function enhanceSqlTuningControls(): void {
  const panel = document.querySelector<HTMLElement>('.sql-workbench .input-panel')
  const controls = panel?.querySelector<HTMLElement>('.top-controls')
  const search = panel?.querySelector<HTMLInputElement>(':scope > input.awr-input')

  if (controls && search && search.parentElement !== controls) {
    const sortField = controls.querySelector('.awr-field:nth-of-type(2)')
    controls.insertBefore(search, sortField ?? controls.firstChild)
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
  enhanceSqlSource()
}

const observer = new MutationObserver(enhanceSqlTuningUi)
observer.observe(document.documentElement, { childList: true, subtree: true })

document.addEventListener('DOMContentLoaded', enhanceSqlTuningUi)
queueMicrotask(enhanceSqlTuningUi)
