const host=document.getElementById('channels');
const names=['Ambiente','Música principal','Batida / Ritmo','Efeitos','Voz / Extra'];
const tracks=names.map(name=>({name,file:'Nenhum áudio selecionado',volume:100,playing:false}));
let library=[];
let selectedSong=null;
function native(action,i,value=1,duration=3){if(window.NativeAudio)NativeAudio.command(action,i,value,duration)}
function showPage(page){document.getElementById('libraryPage').hidden=page!=='library';document.getElementById('mixerPage').hidden=page!=='mixer';document.querySelectorAll('.tab').forEach(b=>b.classList.toggle('active',b.dataset.page===page))}
document.querySelectorAll('.tab').forEach(b=>b.addEventListener('click',()=>showPage(b.dataset.page)));
document.getElementById('pickFolder').addEventListener('click',()=>{if(window.NativeAudio)NativeAudio.chooseFolder();else alert('A escolha de pasta está disponível no aplicativo Android.')});
function libraryLoaded(songs){library=songs||[];document.getElementById('libraryStatus').textContent='Pasta carregada com sucesso';renderLibrary()}
function libraryError(message){document.getElementById('libraryStatus').textContent=message}
function renderLibrary(){
 const q=document.getElementById('searchMusic').value.trim().toLowerCase();
 const visible=library.filter(s=>s.name.toLowerCase().includes(q));
 document.getElementById('songCount').textContent=library.length+' músicas';
 const list=document.getElementById('musicList');
 if(!visible.length){list.innerHTML='<div class="empty">Nenhuma música encontrada.</div>';return}
 list.innerHTML=visible.map((s)=>{const idx=library.indexOf(s);return `<div class="songRow ${selectedSong===idx?'selected':''}"><div class="songIcon">♫</div><div class="songInfo"><b>${escapeHtml(s.name)}</b><small>Arquivo de áudio</small></div><button class="assignBtn" onclick="selectSong(${idx})">${selectedSong===idx?'Selecionada':'Escolher canal'}</button></div>`}).join('');
}
function escapeHtml(v){return String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function selectSong(idx){selectedSong=idx;renderLibrary();document.getElementById('channelPicker').hidden=false;document.getElementById('selectedSongName').textContent=library[idx].name}
function assignSong(channel){if(selectedSong===null)return;const song=library[selectedSong];if(window.NativeAudio)NativeAudio.assign(song.uri,channel,song.name);else alert('A atribuição de canal funciona no aplicativo Android.');tracks[channel].file=song.name;tracks[channel].playing=false;render();document.getElementById('channelPicker').hidden=true;selectedSong=null;renderLibrary();showPage('mixer')}
document.getElementById('searchMusic').addEventListener('input',renderLibrary);
function render(){host.innerHTML=tracks.map((t,i)=>`<article class="channel"><div class="channel-top"><div class="channel-name"><div class="number">0${i+1}</div><div><h3>${t.name}</h3><small>CANAL ${String(i+1).padStart(2,'0')}</small></div></div><span class="state">${t.playing?'TOCANDO':'EM ESPERA'}</span></div><div class="track"><span>${escapeHtml(t.file)}</span><button onclick="showPage('library')">＋ BIBLIOTECA</button></div><div class="controls"><button class="play" onclick="toggle(${i})">${t.playing?'Ⅱ PAUSAR':'▶ REPRODUZIR'}</button><button onclick="stop(${i})">■</button></div><div class="fadeControls"><label>DURAÇÃO <input id="fadeTime${i}" type="number" min="0.2" max="60" step="0.5" value="3"> s</label><button onclick="fade(${i},1)">Fade In</button><button onclick="fade(${i},0)">Fade Out</button></div><label class="vol">VOLUME <input type="range" min="0" max="100" value="${t.volume}" oninput="volume(${i},this.value)"><b id="vol${i}">${t.volume}%</b></label></article>`).join('')}
function trackLoaded(i,name){tracks[i].file=name;render()}
function toggle(i){if(!tracks[i].playing){native('play',i);tracks[i].playing=true}else{native('pause',i);tracks[i].playing=false}render()}
function stop(i){native('stop',i);tracks[i].playing=false;render()}
function fade(i,direction){const seconds=Math.max(0.2,Math.min(60,Number(document.getElementById('fadeTime'+i).value)||3));native(direction?'fade_in':'fade_out',i,1,seconds);tracks[i].playing=!!direction;render()}
function volume(i,v){tracks[i].volume=+v;native('volume',i,+v/100);document.getElementById('vol'+i).textContent=v+'%'}
document.getElementById('master').addEventListener('input',e=>{const v=+e.target.value;document.getElementById('masterValue').textContent=v+'%';tracks.forEach((t,i)=>{native('volume',i,v/100);t.volume=v});render()});
document.getElementById('stopAll').addEventListener('click',()=>{if(window.NativeAudio)NativeAudio.stopAll();tracks.forEach(t=>t.playing=false);render()});
const picker=document.createElement('div');picker.id='channelPicker';picker.className='pickerOverlay';picker.hidden=true;picker.innerHTML='<div class="pickerPanel"><div class="pickerTop"><div><span class="eyebrow">ATRIBUIR MÚSICA</span><h3 id="selectedSongName">Música</h3></div><button class="closePicker" onclick="closePicker()">×</button></div><p>Em qual canal deseja colocar esta música?</p><div class="pickerChannels">'+names.map((n,i)=>`<button onclick="assignSong(${i})"><b>0${i+1}</b><span>${n}</span><span>›</span></button>`).join('')+'</div></div>';document.body.appendChild(picker);
function closePicker(){picker.hidden=true}
render();showPage('library');