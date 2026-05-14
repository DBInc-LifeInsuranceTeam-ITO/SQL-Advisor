<template>
  <footer class="site-footer">
    <div class="footer-content">
      <div class="footer-line footer-company">DB Inc. Life Insurance Infra Team</div>
      <div class="footer-line footer-copy">©2026. All Rights Reserved.</div>
    </div>
  </footer>
</template>


<style scoped>
.site-footer {
  padding-top: 1rem;

  font-weight: 400;
  color: #555;
  margin-top: auto;

  cursor:default;
  user-select: none;
}

.footer-content {
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.2rem;
}

.footer-line {
  text-align: center;
}

.footer-company {
  font-weight: 500;
  font-size: 0.6rem;
  color: #222;
}

.footer-copy {
  font-size: 0.6rem;
  color: #777;
}

.footer-company:hover {
  color: #3598DB;
  transition: color 0.3s ease;
}
</style>
