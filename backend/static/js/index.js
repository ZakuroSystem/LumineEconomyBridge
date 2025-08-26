document.addEventListener('DOMContentLoaded', () => {
  const el = document.getElementById('totals-data');
  if(!el) return;
  const totals = JSON.parse(el.textContent);
  const labels = Object.keys(totals);
  const data = Object.values(totals);
  new Chart(document.getElementById('balanceChart'), {
    type: 'bar',
    data: { labels: labels, datasets: [{ label: 'Total Balance', data: data, backgroundColor: 'rgba(54,162,235,0.5)', borderColor: 'rgb(54,162,235)', borderWidth: 1 }]},
    options: { scales: { y: { beginAtZero: true } } }
  });
});
