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
  let originX = -4;
  let originZ = -4;
  const coordLabel = document.getElementById('coordDisplay');

  async function resolveWorld(){
    const prefixes = [];
    if(apiBase){
      prefixes.push(`${apiBase}/tiles`);
      // also try dropping a trailing /api for misconfigured bases
      if(apiBase.endsWith('/api')) prefixes.push(`${apiBase.slice(0,-4)}/tiles`);
    }
    prefixes.push('/api/tiles', '/tiles', '/plugin/tiles');
    let resp = null;
    for(const p of prefixes){
      try{ resp = await fetch(`${p}/worlds`, {headers: token? {'X-LE-Token': token} : {}}); }
      catch{ resp = null; }
      if(resp && resp.ok){ tilesPrefix = p; break; }
    }
    if(!tilesPrefix){
      tilesPrefix = prefixes[0] || '/tiles';
      world = world || 'world';
    } else {
      if(!world){
        try{
          const js = await resp.json();
          world = (js.worlds && js.worlds.length) ? js.worlds[0] : 'world';
        }catch{ world = world || 'world'; }
      }
    }
    tileBase = `${tilesPrefix}/${encodeURIComponent(world)}`;
  }

  function loadTile(tx, tz){
    const key = `${tx},${tz}`;
    fetch(`${tileBase}/${tx}/${tz}`, {headers: token? {'X-LE-Token': token} : {}})
      .then(r => {
        if(!r.ok) throw new Error();
        return r.arrayBuffer();
      })
      .then(buf => {
        if(buf.byteLength < 24) return;
        const payload = new Uint8Array(buf,24);
        if(payload[0] !== 0x78) return;
        let unpack;
        try{ unpack = pako.inflate(payload); }
        catch{ return; }
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
      })
      .catch(()=>{});
  }

  function drawTile(tx,tz){
    const key=`${tx},${tz}`;
    const img = tileCache[key];
    if(!img) return;
    const px=(tx-originX)*64;
    const pz=(tz-originZ)*64;
    if(px>=0&&px<512&&pz>=0&&pz<512) ctx.putImageData(img,px,pz);
  }

  function refreshTiles(){
    ctx.clearRect(0,0,512,512);
    for(let tx=originX;tx<originX+8;tx++)
      for(let tz=originZ;tz<originZ+8;tz++){
        const key=`${tx},${tz}`;
        if(!tileCache[key]) loadTile(tx,tz);
        else drawTile(tx,tz);
      }
  }

  function shift(dx,dz){
    originX+=dx; originZ+=dz; refreshTiles();
  }

  function updateCharts(){
    const metricsUrl = apiBase ? `${apiBase}/metrics` : '/metrics';
    fetch(metricsUrl,{headers:{'X-LE-Token': token}})
      .then(r=>{if(!r.ok) throw new Error(); return r.text();})
      .then(t=>{
        const m=parseMetrics(t);
        const labels=Object.keys(m), vals=Object.values(m);
        if(metricsChart){metricsChart.data.labels=labels;metricsChart.data.datasets[0].data=vals;metricsChart.update();}
        else metricsChart=new Chart(document.getElementById('metricsChart'),{type:'bar',data:{labels:labels,datasets:[{label:'value',data:vals}]}});
      })
      .catch(()=>{});
    const logsUrl = apiBase ? `${apiBase}/logs/summary` : '/logs/summary';
    fetch(logsUrl,{headers:{'X-LE-Token': token}})
      .then(r=>{if(!r.ok) throw new Error(); return r.json();})
      .then(sum=>{
        const labels=Object.keys(sum); const counts=labels.map(k=>sum[k].count);
        if(logChart){logChart.data.labels=labels;logChart.data.datasets[0].data=counts;logChart.update();}
        else logChart=new Chart(document.getElementById('logChart'),{type:'bar',data:{labels:labels,datasets:[{label:'count',data:counts}]}});
      })
      .catch(()=>{});
  }

  function parseMetrics(text){
    const data={};
    text.trim().split(/\n/).forEach(line=>{const [k,v]=line.split(/\s+/);data[k]=parseFloat(v);});
    return data;
  }

  let metricsChart, logChart;

  async function init(){
    await resolveWorld();
    const paletteUrls = [];
    if(apiBase){
      paletteUrls.push(`${apiBase}/mapcolor/palette`);
      if(apiBase.endsWith('/api')) paletteUrls.push(`${apiBase.slice(0,-4)}/mapcolor/palette`);
    }
    paletteUrls.push('/api/mapcolor/palette', '/plugin/mapcolor/palette', '/mapcolor/palette');
    let pResp = null;
    for(const url of paletteUrls){
      try{ pResp = await fetch(url, {headers:{'X-LE-Token': token}}); } catch{ pResp=null; }
      if(pResp && pResp.ok) break;
    }
    if(!pResp || !pResp.ok){
      palette = Array.from({length:64}, ()=>[0x40,0x40,0x40]);
      palette[1] = [0x9B,0xEC,0x77];
      palette[2] = [0x79,0xD4,0x5C];
      palette[3] = [0x89,0xB9,0xCD];
      palette[4] = [0xF5,0xF5,0xF5];
      palette[5] = [0xA5,0xA5,0xA5];
      palette[6] = [0xF8,0x92,0x21];
    }else{
      try{
        const data = await pResp.json();
        palette = data.palette;
      } catch {
        palette = Array.from({length:64}, ()=>[0x40,0x40,0x40]);
        palette[1] = [0x9B,0xEC,0x77];
        palette[2] = [0x79,0xD4,0x5C];
        palette[3] = [0x89,0xB9,0xCD];
        palette[4] = [0xF5,0xF5,0xF5];
        palette[5] = [0xA5,0xA5,0xA5];
        palette[6] = [0xF8,0x92,0x21];
      }
    }
    refreshTiles();
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

  canvas.addEventListener('mousemove', e => {
    const rect = canvas.getBoundingClientRect();
    const x = e.clientX-rect.left;
    const y = e.clientY-rect.top;
    const tx = Math.floor(x/64)+originX;
    const tz = Math.floor(y/64)+originZ;
    coordLabel.textContent = `${tx},${tz}`;
  });

  document.getElementById('btnUp').addEventListener('click', ()=>shift(0,-1));
  document.getElementById('btnDown').addEventListener('click', ()=>shift(0,1));
  document.getElementById('btnLeft').addEventListener('click', ()=>shift(-1,0));
  document.getElementById('btnRight').addEventListener('click', ()=>shift(1,0));

  document.addEventListener('keydown', e=>{
    if(e.key==='ArrowUp') shift(0,-1);
    else if(e.key==='ArrowDown') shift(0,1);
    else if(e.key==='ArrowLeft') shift(-1,0);
    else if(e.key==='ArrowRight') shift(1,0);
  });

  init();
});
