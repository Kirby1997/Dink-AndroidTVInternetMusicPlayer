// Main app: state management, screen routing, scaling.

const { useState, useEffect, useRef } = React;
const { ALBUMS, SONGS, SHARES, QUEUE_IDS, LYRICS } = window.APP_DATA;

// ----------------------- TWEAK DEFAULTS -----------------------
const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "theme": "dark",
  "density": "roomy"
}/*EDITMODE-END*/;

// ----------------------- App -----------------------
function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);

  // Apply theme + density classes to body
  useEffect(() => {
    document.body.classList.toggle('light', t.theme === 'light');
    document.body.classList.toggle('density-compact', t.density === 'compact');
  }, [t.theme, t.density]);

  // ---- Player state ----
  const [player, setPlayer] = useState({
    current: window.songMap['s1'],
    t: 38,
    playing: true,
  });

  // Tick the playhead while playing
  useEffect(() => {
    if (!player.playing) return;
    const id = setInterval(() => {
      setPlayer(p => {
        if (!p.playing) return p;
        let nt = p.t + 1;
        if (nt > p.current.dur) {
          // advance to next in queue
          const idx = QUEUE_IDS.indexOf(p.current.id);
          const nextId = QUEUE_IDS[(idx + 1) % QUEUE_IDS.length];
          return { ...p, current: window.songMap[nextId], t: 0 };
        }
        return { ...p, t: nt };
      });
    }, 1000);
    return () => clearInterval(id);
  }, [player.playing]);

  // ---- Screen routing ----
  const [screen, setScreen] = useState('home');
  const [wizardOpen, setWizardOpen] = useState(false);
  const [cloudConnect, setCloudConnect] = useState(null); // provider obj being connected
  const [toast, setToast] = useState(null);

  const flashToast = (msg) => {
    setToast(msg);
    if (flashToast._t) clearTimeout(flashToast._t);
    flashToast._t = setTimeout(() => setToast(null), 2400);
  };

  const playSong = (songId, opt) => {
    const s = window.songMap[songId];
    if (!s) return;
    setPlayer(p => ({ ...p, current: s, t: 0, playing: true }));
    if (opt === 'open-now-playing') setScreen('nowplay');
    else flashToast(`Now playing — ${s.title}`);
  };

  const playPause = () => setPlayer(p => ({ ...p, playing: !p.playing }));
  const next = () => {
    const idx = QUEUE_IDS.indexOf(player.current.id);
    const nid = QUEUE_IDS[(idx + 1) % QUEUE_IDS.length];
    setPlayer(p => ({ ...p, current: window.songMap[nid], t: 0 }));
  };
  const prev = () => {
    const idx = QUEUE_IDS.indexOf(player.current.id);
    const nid = QUEUE_IDS[(idx - 1 + QUEUE_IDS.length) % QUEUE_IDS.length];
    setPlayer(p => ({ ...p, current: window.songMap[nid], t: 0 }));
  };
  const seek = (sec) => setPlayer(p => ({ ...p, t: Math.max(0, Math.min(p.current.dur, sec)) }));
  const jumpQueue = (sid) => setPlayer(p => ({ ...p, current: window.songMap[sid], t: 0, playing: true }));

  return (
    <div className="app">
      <SideRail screen={wizardOpen ? 'shares' : screen}
        onNavigate={(id) => { setWizardOpen(false); setScreen(id); }}
        onSettings={() => flashToast('Settings — coming soon')}
        onToast={flashToast}
      />

      <main className="main">
        {/* HOME */}
        <div className={`screen ${(!wizardOpen && screen==='home')?'active':''}`}>
          <HomeScreen player={player} onPlay={playSong}/>
        </div>

        {/* NOW PLAYING */}
        <div className={`screen ${(!wizardOpen && screen==='nowplay')?'active':''}`}>
          <NowPlayingScreen
            player={player}
            onPlayPause={playPause}
            onNext={next} onPrev={prev}
            onSeek={seek}
            onJumpQueue={jumpQueue}
          />
        </div>

        {/* SONGS */}
        <div className={`screen ${(!wizardOpen && screen==='songs')?'active':''}`}>
          <SongsScreen player={player} onPlay={playSong}/>
        </div>

        {/* SHARES */}
        <div className={`screen ${(!wizardOpen && screen==='shares')?'active':''}`}>
          <SharesScreen onAddShare={() => setWizardOpen(true)} onToast={flashToast}/>
        </div>

        {/* CLOUD */}
        <div className={`screen ${(!wizardOpen && screen==='cloud')?'active':''}`}>
          <CloudScreen onToast={flashToast} onConnect={(p) => setCloudConnect(p)}/>
        </div>

        {/* WIZARD */}
        <div className={`screen ${wizardOpen?'active':''}`}>
          <AddShareWizard
            onCancel={() => setWizardOpen(false)}
            onDone={(form) => {
              setWizardOpen(false);
              flashToast(`Mounted ${form.mountAs}. Indexing…`);
            }}
            onToast={flashToast}
          />
        </div>

        {/* MINI PLAYER — hidden on Now Playing screen */}
        {(screen !== 'nowplay' || wizardOpen) && (
          <MiniPlayer
            player={player} albumMap={window.albumMap}
            onPlayPause={playPause}
            onNext={next} onPrev={prev}
            onOpenNowPlaying={() => { setWizardOpen(false); setScreen('nowplay'); }}
          />
        )}
      </main>

      {/* Toast */}
      <div className={`toast ${toast?'show':''}`}>
        <span className="ico">{Icon.check({s:12})}</span>
        <span>{toast}</span>
      </div>

      {/* Cloud connect modal */}
      {cloudConnect && (
        <CloudConnectModal
          provider={cloudConnect}
          onCancel={() => setCloudConnect(null)}
          onDone={(p) => { setCloudConnect(null); flashToast(`${p.name} connected. Indexing…`); }}
          onToast={flashToast}
        />
      )}

      {/* Tweaks */}
      <TweaksPanel title="Tweaks">
        <TweakSection label="Theme">
          <TweakRadio
            label="Mode"
            value={t.theme} onChange={v => setTweak('theme', v)}
            options={['dark', 'light']}
          />
        </TweakSection>
        <TweakSection label="Density">
          <TweakRadio
            label="Cards"
            value={t.density} onChange={v => setTweak('density', v)}
            options={['roomy', 'compact']}
          />
        </TweakSection>
        <TweakSection label="Quick play">
          <TweakButton label="Open Now Playing" onClick={() => { setScreen('nowplay'); setWizardOpen(false); }}/>
          <TweakButton label="Open Add-share wizard" onClick={() => { setWizardOpen(true); }}/>
        </TweakSection>
      </TweaksPanel>
    </div>
  );
}

// ------------------------- Mount + scale -------------------------
function mountAndScale() {
  const root = ReactDOM.createRoot(document.getElementById('react-root'));
  root.render(<App/>);

  const stage = document.getElementById('stage');
  function scale() {
    const w = window.innerWidth, h = window.innerHeight;
    const s = Math.min(w / 1920, h / 1080);
    stage.style.transform = `translate(-50%, -50%) scale(${s})`;
  }
  window.addEventListener('resize', scale);
  scale();
}
mountAndScale();
