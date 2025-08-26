document.addEventListener('DOMContentLoaded', () => {
  const el = document.getElementById('analytics-data');
  if(!el) return;
  const data = JSON.parse(el.textContent);
  const supply = JSON.parse(data.supply);
  const tx = JSON.parse(data.tx);
  const top = JSON.parse(data.top);
  new Chart(document.getElementById('supplyChart'),{type:'line',data:supply});
  new Chart(document.getElementById('txChart'),{type:'bar',data:tx});
  new Chart(document.getElementById('topChart'),{type:'bar',data:top});
});
