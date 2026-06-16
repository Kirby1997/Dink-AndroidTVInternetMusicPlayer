// Shared components: icons, album art, side rail, mini player, focus tiles.

// ----------------------------- Icons -----------------------------
// Simple stroke glyphs. Each takes className.
const Icon = {
  home:    ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M3 11.5 12 4l9 7.5"/><path d="M5 10v10h14V10"/></svg>,
  play:    ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="currentColor"><path d="M7 5v14l12-7z"/></svg>,
  pause:   ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>,
  next:    ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="currentColor"><path d="M5 5v14l10-7z"/><rect x="16" y="5" width="3" height="14" rx="1"/></svg>,
  prev:    ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="currentColor"><path d="M19 5v14l-10-7z"/><rect x="5" y="5" width="3" height="14" rx="1"/></svg>,
  shuffle: ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M16 4h4v4"/><path d="M4 20l16-16"/><path d="M16 20h4v-4"/><path d="M4 4l5 5"/><path d="M14 14l6 6"/></svg>,
  repeat:  ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M17 1l4 4-4 4"/><path d="M3 11V9a4 4 0 014-4h14"/><path d="M7 23l-4-4 4-4"/><path d="M21 13v2a4 4 0 01-4 4H3"/></svg>,
  queue:   ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"><path d="M4 6h12M4 12h12M4 18h8"/><path d="M19 14v6M16 17l3 3 3-3"/></svg>,
  song:    ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M9 18V5l10-2v13"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="16" r="2"/></svg>,
  album:   ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2"/></svg>,
  artist:  ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/></svg>,
  playlist:({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"><path d="M4 6h16M4 12h12M4 18h8"/><path d="M18 14v8M22 18l-4 4-4-4"/></svg>,
  folder:  ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round"><path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z"/></svg>,
  share:   ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round"><rect x="3" y="4" width="18" height="6" rx="1.5"/><rect x="3" y="14" width="18" height="6" rx="1.5"/><circle cx="7" cy="7" r="1" fill="currentColor"/><circle cx="7" cy="17" r="1" fill="currentColor"/></svg>,
  search:  ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"><circle cx="11" cy="11" r="7"/><path d="M16 16l4 4"/></svg>,
  cloud:   ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" strokeLinecap="round"><path d="M7 18a4 4 0 010-8 6 6 0 0111.5 2A4.5 4.5 0 0117 18H7z"/></svg>,
  settings:({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.9-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1-1.5 1.7 1.7 0 00-1.9.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.9 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1 1.7 1.7 0 00-.3-1.9l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.9.3h.1a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.9-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.9v.1a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z"/></svg>,
  nowplay: ({s=22}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round"><path d="M4 14v-4M8 18V6M12 16V8M16 19V5M20 13v-2"/></svg>,
  plus:    ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M12 5v14M5 12h14"/></svg>,
  vol:     ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="currentColor"><path d="M3 10v4h4l5 4V6L7 10H3z"/><path d="M16 8a5 5 0 010 8" stroke="currentColor" strokeWidth="1.6" fill="none" strokeLinecap="round"/></svg>,
  more:    ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="currentColor"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>,
  check:   ({s=14}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M4 12l5 5L20 6"/></svg>,
  back:    ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M15 5l-7 7 7 7"/></svg>,
  forward: ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 5l7 7-7 7"/></svg>,
  spin:    ({s=18}) => <svg viewBox="0 0 24 24" width={s} height={s} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M12 3a9 9 0 109 9" /></svg>,
};

// ----------------------------- Album Art -----------------------------
// Procedural album art generated from a palette + shape.
function AlbumArt({ album, showTitle = false }) {
  if (!album) return <div className="albumart" style={{background:'#222'}}/>;
  const [c1, c2, c3] = album.palette;
  const id = 'aa-' + album.id;
  const shapes = {
    orbits: (
      <>
        <defs>
          <radialGradient id={id+'g'} cx="0.7" cy="0.25" r="0.9">
            <stop offset="0%" stopColor={c1} stopOpacity="0.9"/>
            <stop offset="60%" stopColor={c2} stopOpacity="0.5"/>
            <stop offset="100%" stopColor={c3} stopOpacity="1"/>
          </radialGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${id+'g'})`}/>
        <circle cx="78" cy="22" r="14" fill="none" stroke={c1} strokeWidth="0.6" opacity="0.8"/>
        <circle cx="78" cy="22" r="24" fill="none" stroke={c1} strokeWidth="0.4" opacity="0.5"/>
        <circle cx="78" cy="22" r="36" fill="none" stroke={c2} strokeWidth="0.3" opacity="0.4"/>
        <circle cx="78" cy="22" r="6" fill={c1}/>
      </>
    ),
    horizon: (
      <>
        <defs>
          <linearGradient id={id+'g'} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={c2}/>
            <stop offset="55%" stopColor={c1}/>
            <stop offset="100%" stopColor={c3}/>
          </linearGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${id+'g'})`}/>
        <circle cx="50" cy="62" r="22" fill={c1} opacity="0.92"/>
        <rect x="0" y="62" width="100" height="38" fill={c3} opacity="0.55"/>
        <rect x="0" y="64" width="100" height="0.4" fill="rgba(255,255,255,0.15)"/>
      </>
    ),
    wave: (
      <>
        <defs>
          <linearGradient id={id+'g'} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={c3}/>
            <stop offset="100%" stopColor={c1}/>
          </linearGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${id+'g'})`}/>
        {[0,1,2,3,4,5].map(i => (
          <path key={i} d={`M-5 ${30+i*10} Q 25 ${20+i*10} 50 ${30+i*10} T 105 ${30+i*10}`}
            fill="none" stroke={c2} strokeWidth={0.5+i*0.05} opacity={0.7-i*0.08}/>
        ))}
      </>
    ),
    grid: (
      <>
        <defs>
          <linearGradient id={id+'g'} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={c1}/>
            <stop offset="100%" stopColor={c3}/>
          </linearGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${id+'g'})`}/>
        {Array.from({length:8}).map((_,i) => Array.from({length:8}).map((__,j) => (
          <rect key={i+'-'+j} x={i*12+2} y={j*12+2} width="9" height="9" rx="1.2"
            fill={(i+j)%3===0 ? c2 : 'none'}
            stroke={c2} strokeWidth="0.2" opacity={(i+j)%3===0 ? 0.6 : 0.18}/>
        )))}
      </>
    ),
    rings: (
      <>
        <defs>
          <radialGradient id={id+'g'} cx="0.5" cy="0.5" r="0.7">
            <stop offset="0%" stopColor={c1}/>
            <stop offset="100%" stopColor={c3}/>
          </radialGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${id+'g'})`}/>
        {[10,20,30,40,50].map(r => (
          <circle key={r} cx="50" cy="50" r={r} fill="none" stroke={c2} strokeWidth="0.6" opacity={0.9 - r*0.012}/>
        ))}
      </>
    ),
    diag: (
      <>
        <defs>
          <linearGradient id={id+'g'} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor={c1}/>
            <stop offset="100%" stopColor={c3}/>
          </linearGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${id+'g'})`}/>
        {Array.from({length:10}).map((_,i) => (
          <rect key={i} x={-50+i*16} y="-10" width="6" height="160" fill={c2} opacity={0.10+i*0.02} transform={`rotate(-22 ${-50+i*16} 50)`}/>
        ))}
      </>
    ),
    paper: (
      <>
        <defs>
          <linearGradient id={id+'g'} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={c1}/>
            <stop offset="100%" stopColor={c2}/>
          </linearGradient>
        </defs>
        <rect width="100" height="100" fill={`url(#${id+'g'})`}/>
        <rect x="14" y="18" width="72" height="64" fill={c3} opacity="0.55"/>
        {Array.from({length:10}).map((_,i) => (
          <rect key={i} x="20" y={26+i*5} width={i%3===0?56:i%3===1?40:48} height="1.4" fill={c2} opacity="0.65"/>
        ))}
      </>
    ),
  };

  return (
    <div className="albumart">
      <svg viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice" style={{width:'100%',height:'100%',display:'block'}}>
        {shapes[album.shape] || shapes.orbits}
      </svg>
      {showTitle && (
        <>
          <div className="tag">{album.tag}</div>
          <div className="ttl">{album.title}</div>
        </>
      )}
    </div>
  );
}

// ----------------------------- Side Rail -----------------------------
function SideRail({ screen, onNavigate, onSettings, onToast }) {
  const [expanded, setExpanded] = React.useState(false);
  const items = [
    { id: 'home',     label: 'Home',         icon: 'home' },
    { id: 'search',   label: 'Search',       icon: 'search',  toast: 'Voice search — coming in 0.3' },
    { id: 'nowplay',  label: 'Now Playing',  icon: 'nowplay' },
  ];
  const library = [
    { id: 'songs',    label: 'Songs',     icon: 'song',     badge: '24' },
    { id: 'albums',   label: 'Albums',    icon: 'album',    badge: '12', toast: 'Albums view — coming soon' },
    { id: 'artists',  label: 'Artists',   icon: 'artist',   badge: '12', toast: 'Artists view — coming soon' },
    { id: 'playlists',label: 'Playlists', icon: 'playlist', badge: '4',  toast: 'Playlists — coming soon' },
    { id: 'folders',  label: 'Folders',   icon: 'folder',   toast: 'Folder browser — coming soon' },
  ];
  const sources = [
    { id: 'shares',  label: 'SMB Shares',    icon: 'share', badge: '4' },
    { id: 'cloud',   label: 'Cloud Storage', icon: 'cloud', badge: '3' },
  ];

  const click = (item) => {
    if (item.toast) { onToast(item.toast); return; }
    onNavigate(item.id);
  };

  return (
    <aside
      className={`rail ${expanded ? 'expanded' : ''}`}
      onMouseEnter={() => setExpanded(true)}
      onMouseLeave={() => setExpanded(false)}
    >
      <div className="rail-logo">
        <div className="mark">
          <svg viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.8" strokeLinecap="round"><path d="M6 19V7l12-2v12"/><circle cx="6" cy="19" r="2.5" fill="#fff" stroke="none"/><circle cx="18" cy="17" r="2.5" fill="#fff" stroke="none"/></svg>
        </div>
        <div className="name">Aether<span className="sub">SMB · v0.2</span></div>
      </div>

      {items.map(it => (
        <button key={it.id} className={`rail-item ${screen===it.id?'active':''}`} onClick={() => click(it)}>
          <span className="ico">{Icon[it.icon]({})}</span>
          <span className="label">{it.label}</span>
        </button>
      ))}

      <div className="rail-section">Library</div>
      {library.map(it => (
        <button key={it.id} className={`rail-item ${screen===it.id?'active':''}`} onClick={() => click(it)}>
          <span className="ico">{Icon[it.icon]({})}</span>
          <span className="label">{it.label}</span>
          {it.badge && <span className="badge">{it.badge}</span>}
        </button>
      ))}

      <div className="rail-section">Sources</div>
      {sources.map(it => (
        <button key={it.id} className={`rail-item ${screen===it.id?'active':''}`} onClick={() => click(it)}>
          <span className="ico">{Icon[it.icon]({})}</span>
          <span className="label">{it.label}</span>
          {it.badge && <span className="badge">{it.badge}</span>}
        </button>
      ))}

      <button className="rail-item" style={{marginTop:'auto'}} onClick={onSettings}>
        <span className="ico">{Icon.settings({})}</span>
        <span className="label">Settings</span>
      </button>

      <div className="rail-footer">
        <div className="av">R</div>
        <div className="who">
          <div className="nm">Living Room</div>
          <div className="ip">192.168.1.18 · WI-FI</div>
        </div>
      </div>
    </aside>
  );
}

// ----------------------------- Mini Player -----------------------------
function MiniPlayer({ player, onPlayPause, onNext, onPrev, onOpenNowPlaying, albumMap }) {
  const track = player.current;
  if (!track) return null;
  const album = albumMap[track.albumId];
  const pct = (player.t / track.dur) * 100;
  return (
    <div className="miniplayer">
      <div className="art" onClick={onOpenNowPlaying} style={{cursor:'pointer'}}><AlbumArt album={album}/></div>
      <div className="meta" onClick={onOpenNowPlaying} style={{cursor:'pointer'}}>
        <div className="t">{track.title}</div>
        <div className="a">{album?.artist} — {album?.title}</div>
      </div>
      <div className="ctrls">
        <button className="btn" onClick={() => onPrev()}>{Icon.prev({})}</button>
        <button className="btn play" onClick={onPlayPause}>{player.playing ? Icon.pause({s:22}) : Icon.play({s:22})}</button>
        <button className="btn" onClick={() => onNext()}>{Icon.next({})}</button>
      </div>
      <div className="progress">
        <span className="t font-mono">{fmtTime(player.t)}</span>
        <div className="bar"><div className="fill" style={{width: pct+'%'}}/></div>
        <span className="t font-mono">{fmtTime(track.dur)}</span>
      </div>
      <div className="vol">
        {Icon.vol({})}
        <div className="vbar"><div className="vfill"/></div>
      </div>
    </div>
  );
}

function fmtTime(sec) {
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${s.toString().padStart(2,'0')}`;
}

// ----------------------------- Top bar -----------------------------
function TopBar({ crumbs, right }) {
  const [time, setTime] = React.useState(() => new Date());
  React.useEffect(() => {
    const t = setInterval(() => setTime(new Date()), 30000);
    return () => clearInterval(t);
  }, []);
  const hh = time.getHours().toString().padStart(2,'0');
  const mm = time.getMinutes().toString().padStart(2,'0');
  return (
    <div className="topbar">
      <div className="crumbs">
        {crumbs.map((c, i) => (
          <React.Fragment key={i}>
            {i > 0 && <span className="sep">/</span>}
            <span className={i === crumbs.length-1 ? 'here' : ''}>{c}</span>
          </React.Fragment>
        ))}
      </div>
      {right}
      <div className="clock">
        <span><span className="dot"></span> attic-nas connected</span>
        <span>{hh}:{mm}</span>
      </div>
    </div>
  );
}

// Export to window for other Babel scripts
Object.assign(window, { Icon, AlbumArt, SideRail, MiniPlayer, TopBar, fmtTime });
