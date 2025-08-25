document.addEventListener('DOMContentLoaded', async () => {
  const tokenMeta = document.querySelector('meta[name="le-token"]');
  const token = tokenMeta ? tokenMeta.content : '';
  const apiBaseMeta = document.querySelector('meta[name="api-base"]');
  let apiBase = apiBaseMeta ? apiBaseMeta.content : '';
  if(apiBase.endsWith('/')) apiBase = apiBase.slice(0,-1);
  const apiUrl = apiBase ? new URL(apiBase, location.href) : null;
  const params = new URLSearchParams(location.search);
  let world = params.get('world');
  let tileBase = '';
  let tilesPrefix = '';
  const canvas = document.getElementById('mapCanvas');
  const ctx = canvas.getContext('2d');
  const tileCache = {};
  let palette = [];

  async function resolveWorld(){
    if(apiBase){
      let resp;
      try {
        resp = await fetch(`${apiBase}/tiles/worlds`, {headers: token? {'X-LE-Token': token} : {}});
      } catch {}
      if(resp && resp.ok){
        if(!world){
          const js = await resp.json();
          world = (js.worlds && js.worlds.length) ? js.worlds[0] : 'world';
        }
      } else {
        world = world || 'world';
      }
      tilesPrefix = `${apiBase}/tiles`;
      tileBase = `${tilesPrefix}/${encodeURIComponent(world)}`;
    } else {
      const prefixes = ['/api/tiles', '/tiles', '/plugin/tiles'];
      let resp;
      for(const p of prefixes){
        try{ resp = await fetch(`${p}/worlds`, {headers: token? {'X-LE-Token': token} : {}}); }
        catch{ resp = null; }
        if(resp && resp.ok){ tilesPrefix = p; break; }
      }
      if(!tilesPrefix){
        tilesPrefix = '/tiles';
        world = world || 'world';
      } else {
        if(!world){
          const js = await resp.json();
          world = (js.worlds && js.worlds.length) ? js.worlds[0] : 'world';
        }
      }
      tileBase = `${tilesPrefix}/${encodeURIComponent(world)}`;
    }
  }

  function loadTile(tx, tz){
    const key = `${tx},${tz}`;
    fetch(`${tileBase}/${tx}/${tz}`, {headers: token? {'X-LE-Token': token} : {}})
      .then(r => r.arrayBuffer())
      .then(buf => {
        if(buf.byteLength < 24) return;
        const payload = new Uint8Array(buf,24);
        const unpack = pako.inflate(payload);
        const img = ctx.createImageData(64,64);
        let bit=0;
        for(let i=0;i<4096;i++){
          const byte = bit>>3;
          const shift = bit&7;
          const val = ((unpack[byte]>>shift) | (unpack[byte+1]<<(8-shift))) & 0x3f;
          const [r,g,b] = palette[val];
          const p=i*4;
          img.data[p]=r; img.data[p+1]=g; img.data[p+2]=b; img.data[p+3]=255;
          bit+=6;
        }
        tileCache[key]=img;
        drawTile(tx,tz);
      });
  }

  function drawTile(tx,tz){
    const key=`${tx},${tz}`;
    const img = tileCache[key];
    if(img) ctx.putImageData(img,(tx+4)*64,(tz+4)*64);
  }

  function updateCharts(){
    const metricsUrl = apiBase ? `${apiBase}/metrics` : '/metrics';
    fetch(metricsUrl,{headers:{'X-LE-Token': token}}).then(r=>r.text()).then(t=>{
      const m=parseMetrics(t);
      const labels=Object.keys(m), vals=Object.values(m);
      if(metricsChart){metricsChart.data.labels=labels;metricsChart.data.datasets[0].data=vals;metricsChart.update();}
      else metricsChart=new Chart(document.getElementById('metricsChart'),{type:'bar',data:{labels:labels,datasets:[{label:'value',data:vals}]}});
    });
    const logsUrl = apiBase ? `${apiBase}/logs/summary` : '/logs/summary';
    fetch(logsUrl,{headers:{'X-LE-Token': token}}).then(r=>r.json()).then(sum=>{
      const labels=Object.keys(sum); const counts=labels.map(k=>sum[k].count);
      if(logChart){logChart.data.labels=labels;logChart.data.datasets[0].data=counts;logChart.update();}
      else logChart=new Chart(document.getElementById('logChart'),{type:'bar',data:{labels:labels,datasets:[{label:'count',data:counts}]}});
    });
  }

  function parseMetrics(text){
    const data={};
    text.trim().split(/\n/).forEach(line=>{const [k,v]=line.split(/\s+/);data[k]=parseFloat(v);});
    return data;
  }

  let metricsChart, logChart;

  async function init(){
    await resolveWorld();
    const paletteUrl = apiBase ? `${apiBase}/mapcolor/palette` : '/api/mapcolor/palette';
    const resp = await fetch(paletteUrl, {headers:{'X-LE-Token': token}});
    const data = await resp.json();
    palette = data.palette;
    for(let tx=-4;tx<4;tx++) for(let tz=-4;tz<4;tz++) loadTile(tx,tz);
    const wsHost = apiUrl ? apiUrl.host : location.host;
    const wsScheme = (apiUrl ? apiUrl.protocol : location.protocol) === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${wsScheme}://${wsHost}/ws/tiles`);
    ws.onmessage = ev => {
      const msg = JSON.parse(ev.data);
      if(msg.world===world) loadTile(msg.tx, msg.tz);
    };
    updateCharts();
    setInterval(updateCharts,5000);
  }

  init();
});
