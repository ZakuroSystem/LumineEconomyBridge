document.addEventListener('DOMContentLoaded', () => {
  const el = document.getElementById('log-data');
  if(!el) return;
  const chartData = JSON.parse(el.textContent);
  new Chart(document.getElementById('logChart'), {type:'bar', data: chartData});
});
