// All app screens: Home, NowPlaying, Songs, Shares, AddShare wizard.

const { ALBUMS, SONGS, SHARES, LYRICS, QUEUE_IDS, RECENT_IDS, CLOUD_PROVIDERS } = window.APP_DATA;

// Lookups
const albumMap = Object.fromEntries(ALBUMS.map(a => [a.id, a]));
const songMap = Object.fromEntries(SONGS.map(s => [s.id, s]));
window.albumMap = albumMap; window.songMap = songMap;

// =========================================================================
//  HOME / HUB
// =========================================================================
function HomeScreen({ player, onPlay }) {
  const featured = albumMap['a1']; // Continental Drift's album
  const featuredTrack = songMap['s1'];
  const recent = RECENT_IDS.map(id => songMap[id]);
  const sharePicks = ['s7','s23','s17','s5','s11'].map(id => songMap[id]);
  const newSyncs   = ['a8','a11','a5','a10','a12','a4'].map(id => albumMap[id]);

  return (
    <div className="screen-home scrollY" style={{height:'100%', overflowY:'auto'}}>
      {/* HERO */}
      <section className="home-hero">
        <div className="home-hero-bg">
          <AlbumArt album={featured}/>
          <div className="home-hero-veil"/>
        </div>
        <div className="home-hero-content">
          <div className="home-hero-eyebrow">
            <span className="pill good"><span className="dot"></span> Resume</span>
            <span className="font-mono" style={{fontSize:13, color:'var(--ink-2)', letterSpacing:'0.14em'}}>FROM ATTIC-NAS · 24/96 FLAC</span>
          </div>
          <div className="home-hero-album font-mono">{featured.tag}  ·  {featured.year}</div>
          <h1 className="home-hero-title font-serif">{featuredTrack.title}</h1>
          <div className="home-hero-meta">
            <span>{featured.artist}</span>
            <span className="dot-sep"></span>
            <span>{featured.title}</span>
            <span className="dot-sep"></span>
            <span className="font-mono">{fmtTime(featuredTrack.dur)}</span>
          </div>
          <div className="home-hero-buttons">
            <button className="btn-pill primary" onClick={() => onPlay(featuredTrack.id, 'open-now-playing')}>
              {Icon.play({s:18})} Continue Playing
            </button>
            <button className="btn-pill" onClick={() => onPlay(featuredTrack.id)}>
              {Icon.queue({s:16})} Add to Queue
            </button>
            <button className="btn-pill">
              {Icon.album({s:16})} View Album
            </button>
          </div>
        </div>
      </section>

      {/* RECENTLY PLAYED */}
      <Shelf title="Recently played" sub="across all your shares · last 7 days">
        {recent.map(s => (
          <SongCard key={s.id} song={s} onPlay={() => onPlay(s.id)}/>
        ))}
      </Shelf>

      {/* FRESH FROM YOUR SHARES */}
      <Shelf title="New on your shares" sub="6 albums synced this week from attic-nas + family-nas">
        {newSyncs.map(a => (
          <AlbumCard key={a.id} album={a} onPlay={() => {
            const trk = SONGS.find(s => s.albumId === a.id);
            if (trk) onPlay(trk.id);
          }}/>
        ))}
      </Shelf>

      {/* PICKED FROM SHARES */}
      <Shelf title="Across your shares" sub="files Aether thinks belong together">
        {sharePicks.map(s => (
          <SongCard key={s.id} song={s} onPlay={() => onPlay(s.id)}/>
        ))}
      </Shelf>

      <div style={{height: 140}}/>
    </div>
  );
}

function Shelf({ title, sub, children }) {
  return (
    <section className="shelf">
      <div className="shelf-head">
        <div>
          <h2 className="shelf-title">{title}</h2>
          {sub && <div className="shelf-sub font-mono">{sub}</div>}
        </div>
        <button className="shelf-more">View all {Icon.forward({s:14})}</button>
      </div>
      <div className="shelf-row">{children}</div>
    </section>
  );
}

function AlbumCard({ album, onPlay }) {
  return (
    <div className="album-card focus-tile" tabIndex={0} onClick={onPlay}>
      <div className="album-card-art"><AlbumArt album={album}/></div>
      <div className="album-card-meta">
        <div className="album-card-tag font-mono">{album.tag}</div>
        <div className="album-card-title">{album.title}</div>
        <div className="album-card-artist">{album.artist}</div>
      </div>
      <div className="album-card-play">{Icon.play({s:18})}</div>
    </div>
  );
}

function SongCard({ song, onPlay }) {
  const album = albumMap[song.albumId];
  return (
    <div className="song-card focus-tile" tabIndex={0} onClick={onPlay}>
      <div className="song-card-art"><AlbumArt album={album}/></div>
      <div className="song-card-meta">
        <div className="song-card-title">{song.title}</div>
        <div className="song-card-sub">{album.artist}</div>
        <div className="song-card-src font-mono">{song.source}</div>
      </div>
    </div>
  );
}

// =========================================================================
//  NOW PLAYING — lyric forward
// =========================================================================
function NowPlayingScreen({ player, onPlayPause, onNext, onPrev, onSeek, onJumpQueue }) {
  const track = player.current;
  const album = albumMap[track.albumId];

  // Find current line
  const currentLineIdx = (() => {
    let idx = 0;
    for (let i = 0; i < LYRICS.length; i++) {
      if (LYRICS[i].t <= player.t) idx = i;
      else break;
    }
    return idx;
  })();

  const lyricsRef = React.useRef(null);
  React.useEffect(() => {
    if (!lyricsRef.current) return;
    const el = lyricsRef.current.querySelector('.lyr-line.current');
    if (el) {
      // smooth scroll the inner element within the lyrics container
      const container = lyricsRef.current;
      const top = el.offsetTop - container.clientHeight / 2 + el.clientHeight / 2;
      container.scrollTo({ top, behavior: 'smooth' });
    }
  }, [currentLineIdx]);

  const queue = QUEUE_IDS.map(id => songMap[id]);
  const currentQueueIdx = QUEUE_IDS.indexOf(track.id);

  return (
    <div className="screen-nowplaying">
      {/* Background album-art bleed */}
      <div className="np-bg">
        <AlbumArt album={album}/>
        <div className="np-bg-veil"/>
      </div>

      <div className="np-layout">
        {/* LEFT — art + meta + controls */}
        <div className="np-left">
          <div className="np-eyebrow font-mono">
            <span className="pill good"><span className="dot"></span> Now Playing</span>
            <span>FROM {song(player.current.id).source.toUpperCase()}</span>
          </div>

          <div className="np-art"><AlbumArt album={album}/></div>

          <div className="np-meta">
            <div className="np-tag font-mono">{album.tag} · {song(track.id).bitrate}</div>
            <h1 className="np-title font-serif">{track.title}</h1>
            <div className="np-artist">{album.artist}</div>
            <div className="np-album">{album.title} ({album.year})</div>
          </div>

          <div className="np-progress">
            <div className="np-bar" onClick={(e) => {
              const r = e.currentTarget.getBoundingClientRect();
              onSeek((e.clientX - r.left) / r.width * track.dur);
            }}>
              <div className="np-fill" style={{width: (player.t/track.dur*100)+'%'}}/>
              <div className="np-handle" style={{left: (player.t/track.dur*100)+'%'}}/>
            </div>
            <div className="np-times font-mono">
              <span>{fmtTime(player.t)}</span>
              <span style={{color:'var(--ink-3)'}}>−{fmtTime(track.dur - player.t)}</span>
            </div>
          </div>

          <div className="np-controls">
            <button className="np-cbtn ghost">{Icon.shuffle({s:20})}</button>
            <button className="np-cbtn" onClick={onPrev}>{Icon.prev({s:24})}</button>
            <button className="np-cbtn play" onClick={onPlayPause}>{player.playing ? Icon.pause({s:30}) : Icon.play({s:30})}</button>
            <button className="np-cbtn" onClick={onNext}>{Icon.next({s:24})}</button>
            <button className="np-cbtn ghost active">{Icon.repeat({s:20})}</button>
          </div>
        </div>

        {/* MIDDLE — synced lyrics */}
        <div className="np-lyrics" ref={lyricsRef}>
          <div className="np-lyr-fade-top"/>
          <div className="np-lyr-inner">
            {/* Top spacer so first line can center */}
            <div style={{height: '40%'}}/>
            {LYRICS.map((l, i) => {
              if (!l.text) return <div key={i} className="lyr-blank"/>;
              const status = i === currentLineIdx ? 'current' : (i < currentLineIdx ? 'past' : 'future');
              const dist = Math.abs(i - currentLineIdx);
              return (
                <div key={i} className={`lyr-line ${status}`} style={{
                  opacity: status === 'current' ? 1 : Math.max(0.18, 0.7 - dist * 0.14),
                }}>
                  {l.text}
                </div>
              );
            })}
            <div style={{height: '40%'}}/>
          </div>
          <div className="np-lyr-fade-bot"/>
        </div>

        {/* RIGHT — queue */}
        <div className="np-queue">
          <div className="np-queue-head">
            <div>
              <div className="np-queue-eyebrow font-mono">UP NEXT</div>
              <div className="np-queue-title">Play Queue</div>
            </div>
            <div className="np-queue-count font-mono">{queue.length} tracks · {fmtTime(queue.reduce((s,t)=>s+t.dur,0))}</div>
          </div>
          <div className="np-queue-list scrollY">
            {queue.map((q, i) => {
              const al = albumMap[q.albumId];
              const past = i < currentQueueIdx;
              const cur = i === currentQueueIdx;
              return (
                <div key={q.id} className={`q-item ${cur?'current':''} ${past?'past':''}`} onClick={() => onJumpQueue(q.id)}>
                  <div className="q-num font-mono">{cur ? <Bars/> : (i+1).toString().padStart(2,'0')}</div>
                  <div className="q-art"><AlbumArt album={al}/></div>
                  <div className="q-meta">
                    <div className="q-title">{q.title}</div>
                    <div className="q-sub">{al.artist}</div>
                  </div>
                  <div className="q-dur font-mono">{fmtTime(q.dur)}</div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

function song(id) { return songMap[id]; }

function Bars() {
  return (
    <span className="eq-bars">
      <span></span><span></span><span></span><span></span>
    </span>
  );
}

// =========================================================================
//  SONGS — magazine list
// =========================================================================
function SongsScreen({ player, onPlay }) {
  const [focus, setFocus] = React.useState('s1');
  const [sort, setSort] = React.useState('recent');
  const [filter, setFilter] = React.useState('all');

  const filtered = React.useMemo(() => {
    let arr = SONGS.slice();
    if (filter === 'flac') arr = arr.filter(s => s.bitrate.includes('FLAC'));
    if (filter === 'lossy') arr = arr.filter(s => !s.bitrate.includes('FLAC'));
    if (sort === 'recent') arr.sort((a,b) => b.plays - a.plays);
    if (sort === 'title') arr.sort((a,b) => a.title.localeCompare(b.title));
    if (sort === 'artist') arr.sort((a,b) => albumMap[a.albumId].artist.localeCompare(albumMap[b.albumId].artist));
    if (sort === 'longest') arr.sort((a,b) => b.dur - a.dur);
    return arr;
  }, [sort, filter]);

  return (
    <div className="screen-songs">
      <TopBar crumbs={['Library', 'Songs']}/>

      <div className="songs-head">
        <div>
          <h1 className="screen-title">Songs</h1>
          <div className="screen-sub">
            {SONGS.length} tracks across {SHARES.filter(s=>s.status==='connected').length} connected shares.
            Combined library size: {SHARES.reduce((s,sh)=>s+sh.tracks, 0).toLocaleString()} files indexed.
          </div>
        </div>
        <div className="songs-actions">
          <button className="shuffle-all" onClick={() => {
            const arr = filtered.length ? filtered : SONGS;
            const pick = arr[Math.floor(Math.random() * arr.length)];
            onPlay(pick.id, 'open-now-playing');
          }}>
            <span className="sa-ico">{Icon.shuffle({s:20})}</span>
            <span className="sa-text">
              <span className="sa-label">Shuffle all</span>
              <span className="sa-meta font-mono">{filtered.length} tracks · {fmtTime(filtered.reduce((s,t)=>s+t.dur,0))}</span>
            </span>
          </button>
          <div className="songs-toolbar">
            <Seg value={sort} onChange={setSort} options={[
              { v:'recent', l:'Most played'},{ v:'title', l:'Title'},{ v:'artist', l:'Artist'},{ v:'longest', l:'Length'}
            ]}/>
            <Seg value={filter} onChange={setFilter} options={[
              { v:'all', l:'All'},{ v:'flac', l:'FLAC'},{ v:'lossy', l:'Lossy'}
            ]}/>
          </div>
        </div>
      </div>

      <div className="songs-list scrollY">
        {filtered.map((s, i) => {
          const al = albumMap[s.albumId];
          const isPlaying = player.current.id === s.id;
          const isFocused = focus === s.id;
          return (
            <div key={s.id}
              className={`song-row ${isFocused?'focused':''} ${isPlaying?'playing':''}`}
              onMouseEnter={() => setFocus(s.id)}
              onClick={() => onPlay(s.id)}
              tabIndex={0}
            >
              <div className="song-row-num font-mono">{isPlaying ? <Bars/> : (i+1).toString().padStart(2,'0')}</div>
              <div className="song-row-art"><AlbumArt album={al}/></div>
              <div className="song-row-text">
                <div className="song-row-title font-serif">{s.title}</div>
                <div className="song-row-sub">
                  <span className="artist">{al.artist}</span>
                  <span className="sep">·</span>
                  <span className="album">{al.title}</span>
                  <span className="sep">·</span>
                  <span className="year font-mono">{al.year}</span>
                </div>
              </div>
              <div className="song-row-source">
                <div className="src-bitrate font-mono">{s.bitrate}</div>
                <div className="src-path font-mono">{s.source}</div>
              </div>
              <div className="song-row-stats">
                <div className="stat-plays font-mono">{s.plays}<span> plays</span></div>
                <div className="stat-dur font-mono">{fmtTime(s.dur)}</div>
              </div>
              <button className="song-row-more">{Icon.more({})}</button>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function Seg({ value, onChange, options }) {
  return (
    <div className="seg">
      {options.map(o => (
        <button key={o.v} className={value===o.v?'on':''} onClick={() => onChange(o.v)}>{o.l}</button>
      ))}
    </div>
  );
}

// =========================================================================
//  SHARES
// =========================================================================
function SharesScreen({ onAddShare, onToast }) {
  const totalTracks = SHARES.reduce((s, sh) => s + sh.tracks, 0);
  const connected   = SHARES.filter(s => s.status === 'connected').length;

  return (
    <div className="screen-shares">
      <TopBar crumbs={['Sources', 'SMB Shares']}/>
      <div className="shares-head">
        <div>
          <h1 className="screen-title">SMB Shares</h1>
          <div className="screen-sub">
            Music libraries mounted from your network. {connected} of {SHARES.length} connected ·
            {' '}{totalTracks.toLocaleString()} tracks indexed.
          </div>
        </div>
        <div style={{display:'flex', gap:12}}>
          <button className="btn-pill" onClick={() => onToast('Rescanning all shares…')}>
            {Icon.spin({s:16})} Rescan all
          </button>
          <button className="btn-pill primary" onClick={onAddShare}>
            {Icon.plus({s:16})} Add Share
          </button>
        </div>
      </div>

      <div className="shares-stats">
        <Stat label="Connected" value={`${connected} / ${SHARES.length}`} accent/>
        <Stat label="Total tracks" value={totalTracks.toLocaleString()}/>
        <Stat label="On disk" value="644 GB"/>
        <Stat label="Protocol" value="SMB3 · CIFS"/>
        <Stat label="Last full sync" value="2 min ago"/>
      </div>

      <div className="shares-grid scrollY">
        {SHARES.map(sh => <ShareCard key={sh.id} share={sh} onToast={onToast}/>)}
        <AddShareCard onClick={onAddShare}/>
      </div>
    </div>
  );
}

function Stat({ label, value, accent }) {
  return (
    <div className={`stat ${accent?'accent':''}`}>
      <div className="stat-l font-mono">{label}</div>
      <div className="stat-v">{value}</div>
    </div>
  );
}

function ShareCard({ share, onToast }) {
  const statusMap = {
    connected: { cls: 'good', label: 'Connected' },
    syncing:   { cls: 'warn', label: 'Syncing' },
    offline:   { cls: 'bad',  label: 'Offline' },
  };
  const s = statusMap[share.status];
  return (
    <div className="share-card" tabIndex={0}>
      <div className="share-card-head">
        <div className="share-icon">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round">
            <rect x="3" y="4" width="18" height="6" rx="1.5"/>
            <rect x="3" y="14" width="18" height="6" rx="1.5"/>
            <circle cx="7" cy="7" r="1" fill="currentColor"/>
            <circle cx="7" cy="17" r="1" fill="currentColor"/>
          </svg>
        </div>
        <div style={{flex:1, minWidth:0}}>
          <div className="share-card-name">{share.name}</div>
          <div className="share-card-mount font-mono">{share.mount}</div>
        </div>
        <span className={`pill ${s.cls}`}><span className="dot"></span>{s.label}</span>
      </div>

      <div className="share-card-rows">
        <div><span className="font-mono lbl">HOST</span> <span className="font-mono">{share.host}:{share.port}</span></div>
        <div><span className="font-mono lbl">USER</span> <span className="font-mono">{share.user}</span></div>
        <div><span className="font-mono lbl">PROTO</span> <span className="font-mono">{share.protocol}</span></div>
        <div><span className="font-mono lbl">TRACKS</span> <span className="font-mono">{share.tracks.toLocaleString()}</span></div>
        <div><span className="font-mono lbl">SIZE</span> <span className="font-mono">{share.size}</span></div>
        <div><span className="font-mono lbl">SYNCED</span> <span className="font-mono">{share.lastSync}</span></div>
      </div>

      <div className="share-card-signal">
        <div className="signal-label font-mono">SIGNAL</div>
        <div className="signal-bar"><div className="signal-fill" style={{width: (share.signal*100)+'%'}}/></div>
        <div className="signal-val font-mono">{Math.round(share.signal*100)}%</div>
      </div>

      <div className="share-card-actions">
        <button className="btn-pill" onClick={() => onToast(`Browsing ${share.name}…`)}>
          {Icon.folder({s:14})} Browse
        </button>
        <button className="btn-pill" onClick={() => onToast(`Resyncing ${share.name}…`)}>
          {Icon.spin({s:14})} Resync
        </button>
        <button className="btn-pill icon-only" onClick={() => onToast(`Settings for ${share.name}`)}>
          {Icon.more({s:18})}
        </button>
      </div>
    </div>
  );
}

function AddShareCard({ onClick }) {
  return (
    <div className="share-card add" onClick={onClick} tabIndex={0}>
      <div className="add-icon">{Icon.plus({s:36})}</div>
      <div className="add-title">Add a share</div>
      <div className="add-sub">Mount an SMB / CIFS path from your network.<br/>Works with NAS, file servers, Tailscale & ZeroTier hosts.</div>
    </div>
  );
}

// =========================================================================
//  CLOUD STORAGE
// =========================================================================
function CloudScreen({ onToast, onConnect }) {
  const connected = CLOUD_PROVIDERS.filter(p => p.status === 'connected');
  const expired   = CLOUD_PROVIDERS.filter(p => p.status === 'expired');
  const idle      = CLOUD_PROVIDERS.filter(p => p.status === 'idle');
  const totalTracks = connected.reduce((s,p)=>s+p.tracks, 0);

  return (
    <div className="screen-cloud">
      <TopBar crumbs={['Sources', 'Cloud Storage']}/>
      <div className="shares-head">
        <div>
          <h1 className="screen-title">Cloud Storage</h1>
          <div className="screen-sub">
            Stream from anywhere your music already lives. {connected.length} connected ·
            {' '}{totalTracks.toLocaleString()} tracks ·
            {' '}cached locally on first play.
          </div>
        </div>
        <div style={{display:'flex', gap:12}}>
          <button className="btn-pill" onClick={() => onToast('Refreshing all tokens…')}>
            {Icon.spin({s:16})} Refresh tokens
          </button>
        </div>
      </div>

      <div className="shares-stats">
        <Stat label="Connected"    value={`${connected.length} / ${CLOUD_PROVIDERS.length}`} accent/>
        <Stat label="Tracks"       value={totalTracks.toLocaleString()}/>
        <Stat label="In cloud"     value={connected.reduce((s,p) => s + parseFloat(p.size), 0).toFixed(1) + ' GB'}/>
        <Stat label="Cache size"   value="12.4 GB / 64 GB"/>
        <Stat label="Auth issues"  value={expired.length}/>
      </div>

      <div className="cloud-sections scrollY">
        {connected.length > 0 && (
          <CloudSection title="Connected" sub={`${connected.length} provider${connected.length===1?'':'s'} streaming now`}>
            {connected.map(p => <CloudCard key={p.id} provider={p} onToast={onToast} onConnect={onConnect}/>)}
          </CloudSection>
        )}
        {expired.length > 0 && (
          <CloudSection title="Needs attention" sub="Re-authenticate to keep streaming">
            {expired.map(p => <CloudCard key={p.id} provider={p} onToast={onToast} onConnect={onConnect}/>)}
          </CloudSection>
        )}
        {idle.length > 0 && (
          <CloudSection title="Available providers" sub={`${idle.length} more sources you can sign into`}>
            {idle.map(p => <CloudCard key={p.id} provider={p} onToast={onToast} onConnect={onConnect}/>)}
          </CloudSection>
        )}
        <div style={{height: 140}}/>
      </div>
    </div>
  );
}

function CloudSection({ title, sub, children }) {
  return (
    <section className="cloud-section">
      <div className="cloud-section-head">
        <h2 className="cloud-section-title">{title}</h2>
        <div className="cloud-section-sub font-mono">{sub}</div>
      </div>
      <div className="cloud-grid">{children}</div>
    </section>
  );
}

// Provider glyphs — abstract geometric marks, NOT real vendor logos.
function ProviderGlyph({ provider }) {
  // Each provider gets a distinct gradient + abstract shape.
  const palettes = {
    gdrive:        ['#5b8dff', '#9b6dff', '#2a1f5d'],
    dropbox:       ['#22c1c3', '#5b8dff', '#062a3d'],
    onedrive:      ['#9b6dff', '#ff4d8a', '#2a0a24'],
    icloud:        ['#3ddc97', '#22c1c3', '#06201a'],
    webdav:        ['#f0a23a', '#e25a3a', '#241204'],
    s3:            ['#ff5577', '#9b6dff', '#1a0612'],
    jellyfin:      ['#9b6dff', '#3ddc97', '#0f0a24'],
    box:           ['#5b8dff', '#22c1c3', '#0a1430'],
  };
  const [c1, c2, c3] = palettes[provider.id] || palettes.gdrive;
  const id = 'cg-' + provider.id;
  const shapes = {
    triangle:      <polygon points="50,15 85,75 15,75" fill="rgba(255,255,255,0.92)"/>,
    diamond:       <polygon points="50,12 88,50 50,88 12,50" fill="rgba(255,255,255,0.92)"/>,
    cloud:         <path d="M30 60a13 13 0 010-26 18 18 0 0135 6 13 13 0 015 25H30z" fill="rgba(255,255,255,0.92)"/>,
    circle:        <circle cx="50" cy="50" r="32" fill="rgba(255,255,255,0.92)"/>,
    square:        <rect x="20" y="20" width="60" height="60" rx="6" fill="rgba(255,255,255,0.92)"/>,
    hexagon:       <polygon points="50,12 86,32 86,68 50,88 14,68 14,32" fill="rgba(255,255,255,0.92)"/>,
    pentagon:      <polygon points="50,12 88,40 73,82 27,82 12,40" fill="rgba(255,255,255,0.92)"/>,
    parallelogram: <polygon points="22,22 88,22 78,78 12,78" fill="rgba(255,255,255,0.92)"/>,
  };
  return (
    <div className="provider-glyph">
      <svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet" style={{width:'100%',height:'100%',display:'block'}}>
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={c1}/>
            <stop offset="100%" stopColor={c3}/>
          </linearGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${id})`}/>
        <g opacity="0.9">{shapes[provider.glyph] || shapes.circle}</g>
        <circle cx="50" cy="50" r="6" fill={c1}/>
      </svg>
    </div>
  );
}

function CloudCard({ provider, onToast, onConnect }) {
  const statusMap = {
    connected: { cls: 'good', label: 'Connected' },
    expired:   { cls: 'warn', label: 'Token expired' },
    idle:      { cls: 'idle', label: 'Not connected' },
  };
  const s = statusMap[provider.status];
  const isConnected = provider.status === 'connected';

  return (
    <div className="cloud-card" tabIndex={0}>
      <div className="cloud-card-top">
        <ProviderGlyph provider={provider}/>
        <span className={`pill ${s.cls}`}><span className="dot"></span>{s.label}</span>
      </div>

      <div className="cloud-card-meta">
        <div className="cloud-card-name">{provider.name}</div>
        <div className="cloud-card-account font-mono">{provider.account}</div>
      </div>

      {isConnected && (
        <div className="cloud-card-stats">
          <div>
            <div className="cs-l font-mono">TRACKS</div>
            <div className="cs-v">{provider.tracks.toLocaleString()}</div>
          </div>
          <div>
            <div className="cs-l font-mono">CACHED</div>
            <div className="cs-v">{provider.size}</div>
          </div>
          <div>
            <div className="cs-l font-mono">SYNC</div>
            <div className="cs-v">{provider.lastSync}</div>
          </div>
        </div>
      )}

      {!isConnected && (
        <div className="cloud-card-pitch">
          <div><span className="font-mono lbl">AUTH</span> <span className="font-mono">{provider.auth}</span></div>
          <div><span className="font-mono lbl">VIA</span>  <span className="font-mono">{provider.account}</span></div>
        </div>
      )}

      <div className="cloud-card-actions">
        {provider.status === 'connected' && (
          <>
            <button className="btn-pill" onClick={() => onToast(`Browsing ${provider.name}…`)}>
              {Icon.folder({s:14})} Browse
            </button>
            <button className="btn-pill" onClick={() => onToast(`Re-syncing ${provider.name}…`)}>
              {Icon.spin({s:14})} Resync
            </button>
            <button className="btn-pill icon-only" onClick={() => onToast(`Settings for ${provider.name}`)}>
              {Icon.more({s:18})}
            </button>
          </>
        )}
        {provider.status === 'expired' && (
          <button className="btn-pill primary" onClick={() => onConnect(provider)}>
            {Icon.spin({s:14})} Re-authenticate
          </button>
        )}
        {provider.status === 'idle' && (
          <button className="btn-pill primary" onClick={() => onConnect(provider)}>
            {Icon.plus({s:14})} Connect
          </button>
        )}
      </div>
    </div>
  );
}

// Cloud connect modal — OAuth-style device-code flow.
function CloudConnectModal({ provider, onCancel, onDone, onToast }) {
  const [phase, setPhase] = React.useState('code'); // code | waiting | success
  const code = React.useMemo(() => {
    // 4-4 device code (random-looking but stable per provider)
    const seed = (provider.id.charCodeAt(0) * 173 + provider.id.length * 41) % 9999;
    const a = (seed * 7 % 9999).toString().padStart(4,'0');
    const b = (seed * 13 % 9999).toString().padStart(4,'0');
    return `${a}-${b}`.toUpperCase().replace(/(\d)/g, c => 'ABCDEFGHJKLMNPQRSTUVWXYZ'[parseInt(c)*2 % 24]);
  }, [provider]);

  // Simulate the device claim
  React.useEffect(() => {
    if (phase === 'waiting') {
      const t = setTimeout(() => setPhase('success'), 2400);
      return () => clearTimeout(t);
    }
    if (phase === 'success') {
      const t = setTimeout(() => onDone(provider), 1600);
      return () => clearTimeout(t);
    }
  }, [phase, provider, onDone]);

  return (
    <div className="cloud-modal-scrim" onClick={onCancel}>
      <div className="cloud-modal" onClick={e => e.stopPropagation()}>
        <button className="cloud-modal-close" onClick={onCancel} aria-label="Close">✕</button>

        <div className="cloud-modal-header">
          <div className="cloud-modal-glyph"><ProviderGlyph provider={provider}/></div>
          <div>
            <div className="cloud-modal-eyebrow font-mono">CONNECT VIA {provider.auth.toUpperCase()}</div>
            <h2 className="cloud-modal-title">Sign into {provider.name}</h2>
          </div>
        </div>

        {phase === 'code' && (
          <>
            <p className="cloud-modal-p">
              On your phone or laptop, open the URL below and enter this device code.
              We use the OAuth device-flow so you never type a password on the TV.
            </p>
            <div className="cloud-modal-code">
              <div className="cm-url font-mono">aether.tv/code</div>
              <div className="cm-code font-mono">{code}</div>
              <div className="cm-hint">Code valid for 15 min · {provider.name}</div>
            </div>
            <div className="cloud-modal-footer">
              <button className="btn-pill" onClick={onCancel}>Cancel</button>
              <button className="btn-pill primary" onClick={() => setPhase('waiting')}>
                {Icon.check({s:14})} I've entered the code
              </button>
            </div>
          </>
        )}

        {phase === 'waiting' && (
          <div className="cm-state">
            <div className="cm-spinner"><Bars/></div>
            <h3 className="cm-state-title">Waiting for confirmation\u2026</h3>
            <p className="cm-state-p">{provider.name} is verifying the code.</p>
          </div>
        )}

        {phase === 'success' && (
          <div className="cm-state success">
            <div className="cm-success-mark">{Icon.check({s:32})}</div>
            <h3 className="cm-state-title">Connected to {provider.name}</h3>
            <p className="cm-state-p">Indexing your music library\u2026</p>
          </div>
        )}
      </div>
    </div>
  );
}

// =========================================================================
//  ADD SMB SHARE — wizard
// =========================================================================
function AddShareWizard({ onCancel, onDone, onToast }) {
  const [step, setStep] = React.useState(0);
  const [form, setForm] = React.useState({
    discoveryMode: 'manual',
    host: '',
    port: 445,
    share: '',
    proto: 'SMB3',
    authMode: 'password',
    user: '',
    password: '',
    domain: '',
    mountAs: '',
    test: 'idle', // idle | testing | ok | fail
  });

  const steps = ['Discovery', 'Address', 'Credentials', 'Mount', 'Verify'];
  const set = (k, v) => setForm(f => ({...f, [k]: v}));

  const canNext = () => {
    if (step === 0) return true;
    if (step === 1) return form.host.length > 2 && form.share.length > 0;
    if (step === 2) return form.authMode === 'guest' || (form.user && form.password);
    if (step === 3) return form.mountAs.length > 0;
    if (step === 4) return form.test === 'ok';
    return true;
  };

  const next = () => {
    if (step < steps.length - 1) setStep(step + 1);
    else onDone(form);
  };

  // Auto-fill mountAs based on host/share
  React.useEffect(() => {
    if (!form.mountAs && form.host && form.share) {
      const cleanHost = form.host.replace(/\./g,'-').replace(/\d+/g,'').replace(/-+/g,'-').replace(/^-|-$/g,'') || 'host';
      set('mountAs', `${cleanHost}-${form.share}`);
    }
  }, [form.host, form.share]);

  const runTest = () => {
    set('test', 'testing');
    setTimeout(() => {
      const ok = form.host.length > 2 && form.share.length > 0;
      set('test', ok ? 'ok' : 'fail');
    }, 1800);
  };

  return (
    <div className="screen-wizard">
      <TopBar crumbs={['Sources', 'SMB Shares', 'Add share']} right={
        <div style={{display:'flex', gap:12}}>
          <button className="btn-pill" onClick={onCancel}>Cancel</button>
        </div>
      }/>

      <div className="wiz-layout">
        {/* Steps rail */}
        <div className="wiz-steps">
          <div className="wiz-eyebrow font-mono">SMB / CIFS WIZARD</div>
          <h1 className="screen-title" style={{fontSize: 42, marginTop: 8}}>Mount a network share</h1>
          <div className="screen-sub" style={{maxWidth: 360}}>
            Aether speaks SMB2 and SMB3 directly — no Samba install on the TV.
            Credentials are stored in the system keyring.
          </div>

          <ol className="wiz-step-list">
            {steps.map((s, i) => (
              <li key={i} className={i === step ? 'active' : (i < step ? 'done' : '')}>
                <span className="wiz-bullet">{i < step ? Icon.check({s:14}) : (i+1).toString().padStart(2,'0')}</span>
                <span className="wiz-step-label">{s}</span>
              </li>
            ))}
          </ol>
        </div>

        {/* Panel */}
        <div className="wiz-panel">
          {step === 0 && <DiscoveryStep form={form} set={set} onToast={onToast}/>}
          {step === 1 && <AddressStep form={form} set={set}/>}
          {step === 2 && <CredsStep form={form} set={set}/>}
          {step === 3 && <MountStep form={form} set={set}/>}
          {step === 4 && <VerifyStep form={form} runTest={runTest}/>}

          <div className="wiz-footer">
            <button className="btn-pill" onClick={() => step > 0 ? setStep(step-1) : onCancel()}>
              {Icon.back({s:14})} {step > 0 ? 'Back' : 'Cancel'}
            </button>
            <div className="wiz-dots font-mono">Step {step+1} of {steps.length}</div>
            <button className="btn-pill primary" disabled={!canNext()} style={{opacity: canNext() ? 1 : 0.4, pointerEvents: canNext() ? 'auto' : 'none'}} onClick={next}>
              {step === steps.length-1 ? 'Mount share' : 'Continue'} {Icon.forward({s:14})}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// --- Wizard steps ---

function DiscoveryStep({ form, set, onToast }) {
  const [scanning, setScanning] = React.useState(false);
  const [found, setFound] = React.useState([]);
  const discover = () => {
    setScanning(true); setFound([]);
    const hits = [
      { host: '192.168.1.42',   name: 'attic-nas',     shares: ['music','photos','timeline'] },
      { host: '192.168.1.71',   name: 'living-imac',   shares: ['Music','Public'] },
      { host: 'pi.tail.ts.net', name: 'studio-pi',     shares: ['master','demos'] },
    ];
    hits.forEach((h, i) => setTimeout(() => setFound(f => [...f, h]), 600 + i * 600));
    setTimeout(() => setScanning(false), 600 + hits.length * 600);
  };

  return (
    <div className="wiz-body">
      <h2 className="wiz-h">How do you want to find the share?</h2>
      <p className="wiz-p">Scan picks up SMB hosts advertising over mDNS and NetBIOS on the local subnet. Manual entry lets you type any host, including VPN endpoints.</p>

      <div className="wiz-tile-row">
        <RadioTile selected={form.discoveryMode==='scan'} onClick={() => set('discoveryMode','scan')}
          title="Scan local network" sub="Auto-find SMB hosts on 192.168.1.0/24"
          ico={<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="12" cy="12" r="2"/><path d="M8 12a4 4 0 018 0M5 12a7 7 0 0114 0M2 12a10 10 0 0120 0"/></svg>}
        />
        <RadioTile selected={form.discoveryMode==='manual'} onClick={() => set('discoveryMode','manual')}
          title="Enter manually" sub="IP, hostname, or VPN-routed FQDN"
          ico={<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"><path d="M4 17l6-6 4 4 6-6"/><path d="M4 21h16"/></svg>}
        />
      </div>

      {form.discoveryMode === 'scan' && (
        <div className="wiz-scan">
          <div className="wiz-scan-head">
            <div className="font-mono" style={{fontSize:12, letterSpacing:'0.14em', color:'var(--ink-2)'}}>SCANNING 192.168.1.0/24 — UDP 137, TCP 445</div>
            <button className="btn-pill" onClick={discover} disabled={scanning}>
              {Icon.spin({s:14})} {scanning ? 'Scanning…' : (found.length ? 'Rescan' : 'Start scan')}
            </button>
          </div>
          <div className="wiz-scan-list">
            {found.length === 0 && !scanning && (
              <div className="wiz-scan-empty">Press <b>Start scan</b> to discover SMB hosts.</div>
            )}
            {found.map((h, i) => (
              <div key={i} className="wiz-scan-row" onClick={() => {
                set('host', h.host); set('share', h.shares[0]);
                onToast(`Picked //${h.host}/${h.shares[0]} — continue to credentials`);
              }}>
                <div className="scan-dot"><span className="pill good"><span className="dot"></span></span></div>
                <div className="scan-host">
                  <div className="scan-name">{h.name}</div>
                  <div className="scan-ip font-mono">{h.host} · {h.shares.length} shares</div>
                </div>
                <div className="scan-shares font-mono">
                  {h.shares.map(s => <span key={s} className="scan-share">{s}</span>)}
                </div>
                <div className="scan-go">{Icon.forward({s:14})}</div>
              </div>
            ))}
            {scanning && <div className="wiz-scan-row scanning"><div className="scan-dot"><Bars/></div><div className="scan-name">Probing 445…</div></div>}
          </div>
        </div>
      )}
    </div>
  );
}

function AddressStep({ form, set }) {
  return (
    <div className="wiz-body">
      <h2 className="wiz-h">Where is the share?</h2>
      <p className="wiz-p">Aether speaks plain SMB. No mount tools needed.</p>

      <div className="wiz-field-row">
        <Field label="Host" hint="IP, hostname, or fully-qualified name" flex={3}>
          <input className="wiz-input font-mono" placeholder="192.168.1.42  /  attic-nas.local"
            value={form.host} onChange={e => set('host', e.target.value)}/>
        </Field>
        <Field label="Port" hint="445 is standard SMB" flex={1}>
          <input className="wiz-input font-mono" type="number" value={form.port}
            onChange={e => set('port', parseInt(e.target.value||'0',10))}/>
        </Field>
      </div>

      <Field label="Share name" hint="The export on the host. e.g. music or Volume1/audio">
        <div className="wiz-input-prefix">
          <span className="prefix font-mono">//{form.host || 'host'}/</span>
          <input className="wiz-input font-mono" placeholder="music" value={form.share} onChange={e => set('share', e.target.value)}/>
        </div>
      </Field>

      <Field label="Protocol">
        <Seg value={form.proto} onChange={v => set('proto', v)} options={[
          { v:'auto', l:'Auto-negotiate'},{ v:'SMB3', l:'SMB3 only'},{ v:'SMB2', l:'SMB2 only'}
        ]}/>
      </Field>

      <div className="wiz-hint-banner">
        <div className="hint-ico">{Icon.check({s:16})}</div>
        <div>
          <div style={{fontWeight:600, fontSize:14}}>Detected: SMB3 supported</div>
          <div className="font-mono" style={{fontSize:12, color:'var(--ink-2)', marginTop:4}}>
            negotiate.dialect = 3.1.1 · encryption = aes-128-gcm · signing = on
          </div>
        </div>
      </div>
    </div>
  );
}

function CredsStep({ form, set }) {
  return (
    <div className="wiz-body">
      <h2 className="wiz-h">How should we sign in?</h2>
      <p className="wiz-p">Saved in the Android TV keystore, never written to disk in plaintext.</p>

      <div className="wiz-tile-row">
        <RadioTile selected={form.authMode==='password'} onClick={() => set('authMode','password')}
          title="Username & password" sub="Standard SMB auth"
          ico={<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.6"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 018 0v3"/></svg>}/>
        <RadioTile selected={form.authMode==='guest'} onClick={() => set('authMode','guest')}
          title="Guest" sub="No credentials"
          ico={<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/></svg>}/>
        <RadioTile selected={form.authMode==='kerberos'} onClick={() => set('authMode','kerberos')}
          title="Kerberos" sub="Domain-joined hosts"
          ico={<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 3v18M3 12h18"/></svg>}/>
      </div>

      {form.authMode === 'password' && (
        <>
          <div className="wiz-field-row">
            <Field label="Username" flex={2}>
              <input className="wiz-input font-mono" placeholder="media" value={form.user} onChange={e => set('user', e.target.value)}/>
            </Field>
            <Field label="Domain (optional)" flex={2}>
              <input className="wiz-input font-mono" placeholder="WORKGROUP" value={form.domain} onChange={e => set('domain', e.target.value)}/>
            </Field>
          </div>
          <Field label="Password">
            <input className="wiz-input font-mono" type="password" placeholder="••••••••••••" value={form.password} onChange={e => set('password', e.target.value)}/>
          </Field>
          <label className="wiz-check">
            <span className="wiz-cb"><Icon.check s={12}/></span>
            <span>Save in Android keystore and reuse across reboots</span>
          </label>
        </>
      )}
      {form.authMode === 'guest' && (
        <div className="wiz-banner">Guest sign-in is convenient but visible to anyone on your network. Recommended only for shares you know are read-only.</div>
      )}
      {form.authMode === 'kerberos' && (
        <div className="wiz-banner">Kerberos requires an existing /etc/krb5.conf on the device or a realm advertised over DNS. <b>Coming in v0.3.</b></div>
      )}
    </div>
  );
}

function MountStep({ form, set }) {
  return (
    <div className="wiz-body">
      <h2 className="wiz-h">Where should it live in your library?</h2>
      <p className="wiz-p">Pick a short alias. This is what shows up in the Songs source column and in the sidebar.</p>

      <Field label="Display name" hint="What you'll see in the app">
        <input className="wiz-input" placeholder="attic-music" value={form.mountAs} onChange={e => set('mountAs', e.target.value)}/>
      </Field>

      <Field label="What to index">
        <div className="wiz-checks">
          {[
            ['audio', 'Audio files (mp3, flac, ogg, m4a, opus, wav)', true],
            ['art', 'Embedded artwork + cover.jpg / folder.jpg', true],
            ['tags', 'ID3v2 + Vorbis comments', true],
            ['playlists','.m3u and .pls playlist files', false],
            ['lyrics','.lrc lyric files', true],
          ].map(([k,l,d]) => (
            <label key={k} className="wiz-check">
              <span className={`wiz-cb ${d?'on':''}`}>{d && <Icon.check s={12}/>}</span>
              <span>{l}</span>
            </label>
          ))}
        </div>
      </Field>

      <Field label="Sync schedule">
        <Seg value={'auto'} onChange={()=>{}} options={[
          { v:'auto', l:'Auto (on idle + change)'},{ v:'hourly', l:'Every hour'},{ v:'daily', l:'Daily 03:00'},{ v:'manual', l:'Manual only'}
        ]}/>
      </Field>
    </div>
  );
}

function VerifyStep({ form, runTest }) {
  const states = {
    idle:    { color: 'var(--ink-2)', label: 'Not tested yet'},
    testing: { color: 'var(--warn)',  label: 'Testing connection…'},
    ok:      { color: 'var(--good)',  label: 'Connected — share is reachable'},
    fail:    { color: 'var(--bad)',   label: 'Could not connect. Check host & credentials.'},
  };
  const s = states[form.test];

  return (
    <div className="wiz-body">
      <h2 className="wiz-h">Verify and mount.</h2>
      <p className="wiz-p">A dry-run that opens the share, lists the root directory, and reads one file's ID3 tags. Nothing is written.</p>

      <div className="verify-summary">
        <SummaryLine label="MOUNT"    value={`//${form.host || 'host'}/${form.share || 'share'}`}/>
        <SummaryLine label="AS"       value={form.mountAs || '—'}/>
        <SummaryLine label="PROTOCOL" value={form.proto}/>
        <SummaryLine label="AUTH"     value={form.authMode === 'password' ? `${form.user || 'user'}${form.domain?'@'+form.domain:''}` : form.authMode}/>
      </div>

      <div className="verify-runner">
        <div className="runner-head">
          <div className="font-mono" style={{fontSize:11, letterSpacing:'0.18em', color:'var(--ink-3)'}}>CONNECTION TEST</div>
          <button className="btn-pill" onClick={runTest} disabled={form.test === 'testing'}>
            {form.test === 'testing' ? 'Testing…' : (form.test === 'ok' ? 'Test again' : 'Run test')}
          </button>
        </div>

        <div className="runner-log font-mono">
          <LogLine state={form.test} steps={['dns_lookup',`Resolving ${form.host || 'host'}…`, 'OK 12ms']}/>
          <LogLine state={form.test} steps={['tcp_connect',`Connecting tcp/${form.port}…`, 'OK 24ms']}/>
          <LogLine state={form.test} steps={['negotiate', 'NEGOTIATE_PROTOCOL → SMB 3.1.1', 'OK']}/>
          <LogLine state={form.test} steps={['session_setup', 'SESSION_SETUP / NTLMSSP', form.test==='fail' ? 'FAIL: STATUS_LOGON_FAILURE' : 'OK']}/>
          <LogLine state={form.test} steps={['tree_connect', `TREE_CONNECT \\\\${form.host}\\${form.share}`, form.test==='fail' ? '—' : 'OK']}/>
          <LogLine state={form.test} steps={['enumerate', 'Listing root directory…', form.test==='fail' ? '—' : 'OK · 247 entries · 4 audio folders']}/>
        </div>

        <div className="runner-result" style={{borderColor: s.color, color: s.color}}>
          <span className="pill" style={{background: s.color+'22', color: s.color}}>
            <span className="dot" style={{background: s.color, boxShadow: form.test==='ok' ? `0 0 8px ${s.color}` : 'none'}}></span>
            {form.test.toUpperCase()}
          </span>
          <span style={{color: 'var(--ink-1)', fontSize:15}}>{s.label}</span>
        </div>
      </div>
    </div>
  );
}

// --- Wizard helpers ---

function Field({ label, hint, flex, children }) {
  return (
    <div className="wiz-field" style={flex ? { flex } : {}}>
      <div className="wiz-field-label">
        <span>{label}</span>
        {hint && <span className="wiz-field-hint">{hint}</span>}
      </div>
      {children}
    </div>
  );
}

function RadioTile({ selected, onClick, title, sub, ico }) {
  return (
    <button className={`wiz-tile ${selected?'on':''}`} onClick={onClick}>
      <div className="wiz-tile-ico">{ico}</div>
      <div className="wiz-tile-title">{title}</div>
      <div className="wiz-tile-sub">{sub}</div>
      <div className="wiz-tile-check">{selected && <Icon.check s={14}/>}</div>
    </button>
  );
}

function SummaryLine({ label, value }) {
  return (
    <div className="summary-line">
      <span className="font-mono summary-l">{label}</span>
      <span className="font-mono summary-v">{value}</span>
    </div>
  );
}

function LogLine({ state, steps }) {
  const [tag, msg, result] = steps;
  const ok = result.startsWith('OK');
  const fail = result.startsWith('FAIL');
  const dash = result === '—';
  return (
    <div className={`log-line ${state==='testing' ? 'pending' : ''}`}>
      <span className="log-tag">[{tag}]</span>
      <span className="log-msg">{msg}</span>
      {state === 'idle'  && <span className="log-result idle">—</span>}
      {state === 'testing' && <span className="log-result idle">…</span>}
      {(state === 'ok' || state === 'fail') && (
        <span className={`log-result ${dash?'idle':ok?'good':fail?'bad':'good'}`}>{result}</span>
      )}
    </div>
  );
}

// =========================================================================
//  Export
// =========================================================================
Object.assign(window, {
  HomeScreen, NowPlayingScreen, SongsScreen, SharesScreen, AddShareWizard,
  CloudScreen, CloudConnectModal,
});
