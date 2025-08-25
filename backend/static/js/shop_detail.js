document.addEventListener('DOMContentLoaded', () => {
  const el = document.getElementById('shop-detail-data');
  if(!el) return;
  const data = JSON.parse(el.textContent);
  new Chart(document.getElementById('salesChart'),{
    type:'line',
    data:{labels: data.labels, datasets:[{label:'Revenue', data: data.data }]}
  });
});
