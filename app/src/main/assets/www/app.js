const host = document.getElementById('channels');
const tracks = Array.from({length:10}, (_,i) => ({
  name: i === 0 ? 'Música principal' : 'Canal ' + String(i+1).padStart(2,'0'),
  file: 'Nenhuma música carregada', loaded:false, playing:false, volume:100, fadeSeconds:3
}));
let library = [];

function native(action, channel, value=1, seconds=3) {
  if (!window.NativeAudio) { setStatus('Controle nativo indisponível.'); return; }
  NativeAudio.command(action, channel, value, seconds);
}
function setStatus(message) {
  const el=document.getElementById('statusMessage');
  if(el) el.textContent=message;
}
function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
function showPage(page) {
  document.getElementById('libraryPage').hidden = page !== 'library';
  document.getElementById('mixerPage').hidden = page !== 'mixer';
  document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.page === page));
}
document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => showPage(b.dataset.page)));
document.getElementById('pickFolder').addEventListener('click', () => {
  if(window.NativeAudio) NativeAudio.chooseFolder();
  else setStatus('Abra esta tela pelo aplicativo Android.');
});
document.getElementById('searchMusic').addEventListener('input', renderLibrary);

window.libraryLoaded = function(songs) {
  library = Array.isArray(songs) ? songs : [];
  document.getElementById('libraryStatus').textContent = library.length + ' músicas encontradas na pasta selecionada';
  setStatus(library.length ? 'Pasta carregada. Escolha uma música e o canal.' : 'Nenhum arquivo de áudio encontrado nesta pasta.');
  renderLibrary();
};
window.libraryError = function(message) { setStatus(message); };
window.trackLoaded = function(channel, title) {
  if(!tracks[channel]) return;
  tracks[channel].file = title;
  tracks[channel].loaded = true;
  renderChannels();
  setStatus('Carregada em ' + tracks[channel].name + '. Use Play ou Fade In para tocar.');
};

function renderLibrary() {
  const query = document.getElementById('searchMusic').value.trim().toLowerCase();
  const list = document.getElementById('musicList');
  const filtered = library.map((song,index)=>({song,index})).filter(x=>x.song.name.toLowerCase().includes(query));
  document.getElementById('songCount').textContent = library.length + ' ARQUIVOS';
  if(!filtered.length) {
    list.innerHTML = '<div class="empty">' + (library.length ? 'NENHUMA MÚSICA COM ESSE NOME' : 'SELECIONE UMA PASTA PARA CARREGAR SUAS MÚSICAS') + '</div>';
    return;
  }
  list.innerHTML = filtered.map(({song,index}) => `
    <article class="songRow">
      <div class="songIcon">♫</div>
      <div class="songInfo"><b>${escapeHtml(song.name)}</b><small>ARQUIVO LOCAL</small></div>
      <button class="assignBtn" data-action="make-main" data-song="${index}">USAR COMO PRINCIPAL</button>
      <select class="channelSelect" data-select-song="${index}" aria-label="Escolher canal">
        ${tracks.map((t,i)=>`<option value="${i}">${i===0?'Principal':'Canal '+String(i+1).padStart(2,'0')}</option>`).join('')}
      </select>
      <button class="assignBtn" data-action="load-channel" data-song="${index}">CARREGAR</button>
    </article>`).join('');
}

function renderChannels() {
  host.innerHTML = tracks.map((t,i)=>`
    <article class="channel">
      <div class="channel-top">
        <div class="channel-name"><div class="number">${String(i+1).padStart(2,'0')}</div>
          <div><h3>${escapeHtml(t.name)}</h3><small>${i===0?'CANAL PRINCIPAL':'CANAL INDEPENDENTE'}</small></div>
        </div>
        <span class="state">${t.playing?'● TOCANDO':t.loaded?'● CARREGADO':'○ VAZIO'}</span>
      </div>
      <div class="track"><span>${escapeHtml(t.file)}</span></div>
      <div class="controls">
        <button class="play" data-action="play" data-channel="${i}" ${t.loaded?'':'disabled'}>▶ PLAY</button>
        <button data-action="pause" data-channel="${i}" ${t.loaded?'':'disabled'}>Ⅱ PAUSAR</button>
        <button data-action="stop" data-channel="${i}" ${t.loaded?'':'disabled'}>■ PARAR</button>
      </div>
      <div class="fadeControls">
        <label>FADE (SEG) <input type="number" min="0.2" max="60" step="0.5" value="${t.fadeSeconds}" data-fade-duration="${i}"></label>
        <button data-action="fade-in" data-channel="${i}" ${t.loaded?'':'disabled'}>↗ FADE IN</button>
        <button data-action="fade-out" data-channel="${i}" ${t.loaded?'':'disabled'}>↘ FADE OUT</button>
      </div>
      <label class="vol">VOLUME DO CANAL <input type="range" min="0" max="100" value="${t.volume}" data-volume="${i}" ${t.loaded?'':'disabled'}><b>${t.volume}%</b></label>
    </article>`).join('');
}
function assignSong(songIndex, channel) {
  const song=library[songIndex];
  if(!song || !window.NativeAudio) return;
  NativeAudio.assign(song.uri, channel, song.name);
  tracks[channel].file=song.name;
  tracks[channel].loaded=true;
  tracks[channel].playing=false;
  renderChannels();
  setStatus('Carregando "'+song.name+'" em '+tracks[channel].name+'.');
}
document.getElementById('musicList').addEventListener('click', event => {
  const button=event.target.closest('button[data-action]');
  if(!button) return;
  const songIndex=Number(button.dataset.song);
  if(button.dataset.action==='make-main') assignSong(songIndex,0);
  if(button.dataset.action==='load-channel') {
    const select=document.querySelector('[data-select-song="'+songIndex+'"]');
    assignSong(songIndex,Number(select.value));
  }
});
host.addEventListener('click', event => {
  const button=event.target.closest('button[data-action]');
  if(!button) return;
  const i=Number(button.dataset.channel);
  const action=button.dataset.action;
  if(!tracks[i] || !tracks[i].loaded) return;
  if(action==='play') {
    native('play',i);
    tracks.forEach((track,index)=>track.playing=index===i);
    setStatus('Trocando para '+tracks[i].file+'. A faixa atual será encerrada antes da próxima começar.');
  }
  if(action==='pause') { native('pause',i); tracks[i].playing=false; }
  if(action==='stop') { native('stop',i); tracks[i].playing=false; }
  if(action==='fade-in') {
    native('fade_in',i,1,tracks[i].fadeSeconds);
    tracks.forEach((track,index)=>track.playing=index===i);
    setStatus('Fade In iniciado em '+tracks[i].file+'.');
  }
  if(action==='fade-out') {
    const loaded=tracks.map((track,index)=>({track,index})).filter(x=>x.track.loaded).map(x=>x.index);
    const position=loaded.indexOf(i);
    const next=position>=0 && loaded.length>1 ? loaded[(position+1)%loaded.length] : -1;
    native('fade_out',i,1,tracks[i].fadeSeconds);
    tracks.forEach((track,index)=>track.playing=index===next);
    setStatus(next>=0 ? 'Fade Out de '+tracks[i].file+'; próxima: '+tracks[next].file+'.' : 'Fade Out iniciado. Não há outra música carregada para entrar.');
  }
  renderChannels();
});
host.addEventListener('change', event => {
  const duration=event.target.dataset.fadeDuration;
  if(duration!==undefined) tracks[Number(duration)].fadeSeconds=Math.max(.2,Math.min(60,Number(event.target.value)||3));
});
host.addEventListener('input', event => {
  const volume=event.target.dataset.volume;
  if(volume===undefined) return;
  const i=Number(volume), value=Number(event.target.value);
  tracks[i].volume=value;
  native('volume',i,value/100);
  event.target.nextElementSibling.textContent=value+'%';
});
document.getElementById('master').addEventListener('input', event => {
  const value=Number(event.target.value);
  document.getElementById('masterValue').textContent=value+'%';
  native('master',0,value/100);
});
document.getElementById('stopAll').addEventListener('click', () => {
  if(window.NativeAudio) NativeAudio.stopAll();
  tracks.forEach(t=>t.playing=false);
  renderChannels();
  setStatus('Todos os canais foram parados.');
});
renderChannels();
showPage('mixer');
