document.addEventListener('DOMContentLoaded', () => {
  const el = document.getElementById('shop-stats-data');
  if(!el) return;
  const data = JSON.parse(el.textContent);
  new Chart(document.getElementById('rankChart'),{
    type:'bar',
    data:{labels: data.labels, datasets:[{label:'Revenue', data: data.data }]}
  });
});
