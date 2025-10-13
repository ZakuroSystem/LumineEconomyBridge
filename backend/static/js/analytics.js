document.addEventListener('DOMContentLoaded', () => {
  const el = document.getElementById('analytics-data');
  if(!el) return;
  const data = JSON.parse(el.textContent);
  const supply = JSON.parse(data.supply);
  const tx = JSON.parse(data.tx);
  const top = JSON.parse(data.top);
  const shopRank = data.shopRank ? JSON.parse(data.shopRank) : null;
  const shopDaily = data.shopDaily ? JSON.parse(data.shopDaily) : null;
  new Chart(document.getElementById('supplyChart'), { type: 'line', data: supply });
  new Chart(document.getElementById('txChart'), { type: 'bar', data: tx });
  new Chart(document.getElementById('topChart'), { type: 'bar', data: top });
  const rankCanvas = document.getElementById('shopRankChart');
  if (rankCanvas && shopRank && shopRank.labels && shopRank.labels.length) {
    new Chart(rankCanvas, {
      type: 'bar',
      data: shopRank,
      options: {
        responsive: true,
        scales: {
          x: { stacked: true },
          y: { stacked: true, beginAtZero: true },
        },
      },
    });
  }
  const dailyCanvas = document.getElementById('shopDailyChart');
  if (dailyCanvas && shopDaily && shopDaily.labels && shopDaily.labels.length) {
    const palette = ['#0d6efd', '#20c997', '#6610f2', '#fd7e14', '#198754'];
    shopDaily.datasets = (shopDaily.datasets || []).map((ds, idx) => ({
      ...ds,
      borderColor: palette[idx % palette.length],
      backgroundColor: palette[idx % palette.length],
      tension: 0.25,
      fill: false,
    }));
    new Chart(dailyCanvas, {
      type: 'line',
      data: shopDaily,
      options: {
        responsive: true,
        scales: {
          y: { beginAtZero: true },
        },
      },
    });
  }
});
