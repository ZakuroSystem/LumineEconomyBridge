document.addEventListener('DOMContentLoaded', () => {
  const el = document.getElementById('issuance-data');
  if(!el) return;
  const totals = JSON.parse(el.textContent);
  const labels = totals.map(t => t.currency);
  const data = totals.map(t => t.total);
  new Chart(document.getElementById('supplyChart'), {
    type: 'doughnut',
    data: { labels: labels, datasets: [{ data: data }] },
  });
});
