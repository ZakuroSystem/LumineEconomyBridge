document.addEventListener('DOMContentLoaded', () => {
  const el = document.getElementById('tx-data');
  if(!el) return;
  const data = JSON.parse(el.textContent);
  const labels = data.labels.reverse();
  const amounts = data.amounts.reverse();
  new Chart(document.getElementById('txChart'), {
    type: 'line',
    data: { labels: labels, datasets: [{ label: 'Amount', data: amounts, borderColor: 'rgb(75, 192, 192)', tension: 0.1 }] },
    options: { scales: { y: { beginAtZero: true } } }
  });
});
