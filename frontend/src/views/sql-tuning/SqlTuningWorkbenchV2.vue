<template>
  <div class="sql-tuning-v2-shell">
    <section class="sql-tuning-v2-flow" aria-label="SQL 튜닝 작업 흐름">
      <div class="sql-tuning-v2-flow-copy">
        <p class="sql-tuning-v2-kicker">QUERY MEDIC WORKFLOW</p>
        <h2>문제 SQL 탐지부터 개선 검증까지 한 흐름으로 확인해.</h2>
        <p>
          기존 DB 직접 조회와 AI 분석 기능은 그대로 유지하고,
          화면의 우선순위를 탐지 → 진단 → 권고 → 검증 순서로 재정리했어.
        </p>
      </div>

      <ol class="sql-tuning-v2-steps">
        <li>
          <span>1</span>
          <strong>부하 SQL 탐지</strong>
          <small>DB 연결 후 우선순위 SQL 조회</small>
        </li>
        <li>
          <span>2</span>
          <strong>근거 기반 진단</strong>
          <small>실행계획·읽기량·인덱스 확인</small>
        </li>
        <li>
          <span>3</span>
          <strong>개선안 검토</strong>
          <small>SQL 재작성과 인덱스 권고 확인</small>
        </li>
        <li>
          <span>4</span>
          <strong>효과 검증</strong>
          <small>적용 전후 수치 비교</small>
        </li>
      </ol>
    </section>

    <SqlTuningWorkbench />
  </div>
</template>

<script setup lang="ts">
import SqlTuningWorkbench from './SqlTuningWorkbench.vue'
</script>

<style src="./sql-tuning-workbench-v2.css"></style>
