// Mock data for the AndroidTV SMB Music Player
// All artists, album titles, and song titles are invented.

window.APP_DATA = (() => {

  // ---- Albums (procedural art via palette + shape) ----
  const ALBUMS = [
    { id: 'a1', title: 'Anonymous Volumes',    artist: 'Veld Lights',          year: 2024, palette: ['#3a5dff','#9b6dff','#1a1030'], shape: 'orbits',  tag: 'AMBIENT / 24-96' },
    { id: 'a2', title: 'Slow Cartography',     artist: 'Marsh & The Tide',     year: 2023, palette: ['#e25a3a','#f0a23a','#241204'], shape: 'horizon', tag: 'INDIE FOLK' },
    { id: 'a3', title: 'Eolian',               artist: 'Helder Praia',         year: 2022, palette: ['#22c1c3','#3ddc97','#062018'], shape: 'wave',    tag: 'JAZZ / FLAC' },
    { id: 'a4', title: 'Postcards From Olho',  artist: 'Inês Coutto',          year: 2021, palette: ['#ff4d8a','#9b6dff','#180624'], shape: 'grid',    tag: 'BOSSA NOVA' },
    { id: 'a5', title: 'Static Garden',        artist: 'Northern Mannequin',   year: 2024, palette: ['#5b8dff','#22c1c3','#0a1430'], shape: 'rings',   tag: 'SHOEGAZE' },
    { id: 'a6', title: 'Plot Without Map',     artist: 'Owl Errand',           year: 2020, palette: ['#f0a23a','#ff5577','#1c0a10'], shape: 'diag',    tag: 'POST-ROCK' },
    { id: 'a7', title: 'Pale Inventory',       artist: 'Bryn & The Reader',    year: 2023, palette: ['#e5e3da','#a89e88','#1d1a14'], shape: 'paper',   tag: 'ACOUSTIC' },
    { id: 'a8', title: 'Underwriter',          artist: 'Lawful Geometry',      year: 2024, palette: ['#9b6dff','#5b8dff','#0f0a24'], shape: 'orbits',  tag: 'ELECTRONIC' },
    { id: 'a9', title: 'Wide Brimmed',         artist: 'Cooper Lake',          year: 2019, palette: ['#3ddc97','#22c1c3','#06201a'], shape: 'horizon', tag: 'AMERICANA' },
    { id: 'a10',title: 'No Forwarding Address',artist: 'The Tenant Council',   year: 2024, palette: ['#ff5577','#f0a23a','#1a0612'], shape: 'wave',    tag: 'INDIE ROCK' },
    { id: 'a11',title: 'Spring Returns',       artist: 'Kyoko Vesna',          year: 2022, palette: ['#9ce37d','#3ddc97','#0a1c12'], shape: 'grid',    tag: 'NEOCLASSICAL' },
    { id: 'a12',title: 'Quarter to Falsetto',  artist: 'Halverson',            year: 2021, palette: ['#5b8dff','#9b6dff','#080a18'], shape: 'rings',   tag: 'SYNTH POP' },
  ];

  // ---- Songs ----
  // Each song references an albumId and lives on a "share" (or local).
  const SONGS = [
    { id: 's1',  title: 'Continental Drift',     albumId: 'a1', dur: 242, plays: 41, source: 'attic-nas/music', bitrate: '24/96 FLAC' },
    { id: 's2',  title: 'Margin Notes',          albumId: 'a1', dur: 208, plays: 22, source: 'attic-nas/music', bitrate: '24/96 FLAC' },
    { id: 's3',  title: 'Halfway House',         albumId: 'a2', dur: 198, plays: 67, source: 'attic-nas/music', bitrate: '16/44 FLAC' },
    { id: 's4',  title: 'The Slow Way Down',     albumId: 'a2', dur: 314, plays: 12, source: 'attic-nas/music', bitrate: '16/44 FLAC' },
    { id: 's5',  title: 'Trade Winds',           albumId: 'a3', dur: 412, plays: 8,  source: 'family-nas/jazz',  bitrate: '24/192 FLAC' },
    { id: 's6',  title: 'After the Set',         albumId: 'a3', dur: 287, plays: 14, source: 'family-nas/jazz',  bitrate: '24/192 FLAC' },
    { id: 's7',  title: 'Bairro do Sol',         albumId: 'a4', dur: 224, plays: 39, source: 'family-nas/jazz',  bitrate: 'MP3 320' },
    { id: 's8',  title: 'Manhã na Avenida',      albumId: 'a4', dur: 196, plays: 28, source: 'family-nas/jazz',  bitrate: 'MP3 320' },
    { id: 's9',  title: 'Doorframe',             albumId: 'a5', dur: 305, plays: 4,  source: 'local/downloads',  bitrate: '16/44 FLAC' },
    { id: 's10', title: 'Roman Mileage',         albumId: 'a5', dur: 268, plays: 11, source: 'local/downloads',  bitrate: '16/44 FLAC' },
    { id: 's11', title: 'East Pier Reprise',     albumId: 'a6', dur: 488, plays: 17, source: 'attic-nas/music',  bitrate: 'OGG 256' },
    { id: 's12', title: 'Telegram, Unsent',      albumId: 'a6', dur: 372, plays: 7,  source: 'attic-nas/music',  bitrate: 'OGG 256' },
    { id: 's13', title: 'Pale Inventory',        albumId: 'a7', dur: 232, plays: 33, source: 'attic-nas/music',  bitrate: '16/44 FLAC' },
    { id: 's14', title: 'Five Boxes, Four Boxes',albumId: 'a7', dur: 254, plays: 19, source: 'attic-nas/music',  bitrate: '16/44 FLAC' },
    { id: 's15', title: 'Right of Use',          albumId: 'a8', dur: 348, plays: 52, source: 'office-share/lib', bitrate: '24/48 FLAC' },
    { id: 's16', title: 'Notarized Saturday',    albumId: 'a8', dur: 297, plays: 26, source: 'office-share/lib', bitrate: '24/48 FLAC' },
    { id: 's17', title: 'Wide Brimmed',          albumId: 'a9', dur: 211, plays: 88, source: 'attic-nas/music',  bitrate: 'MP3 320' },
    { id: 's18', title: 'Front Porch Mathematic',albumId: 'a9', dur: 188, plays: 71, source: 'attic-nas/music',  bitrate: 'MP3 320' },
    { id: 's19', title: 'No Forwarding Address', albumId: 'a10',dur: 244, plays: 9,  source: 'local/downloads',  bitrate: 'MP3 256' },
    { id: 's20', title: 'Subletter\u2019s Promise', albumId: 'a10', dur: 261, plays: 5, source: 'local/downloads', bitrate: 'MP3 256' },
    { id: 's21', title: 'Spring Returns',        albumId: 'a11',dur: 326, plays: 44, source: 'family-nas/jazz',  bitrate: '24/96 FLAC' },
    { id: 's22', title: 'Letter to the Janitor', albumId: 'a11',dur: 278, plays: 13, source: 'family-nas/jazz',  bitrate: '24/96 FLAC' },
    { id: 's23', title: 'Quarter to Falsetto',   albumId: 'a12',dur: 218, plays: 60, source: 'office-share/lib', bitrate: '16/44 FLAC' },
    { id: 's24', title: 'Telephoning a Cousin',  albumId: 'a12',dur: 234, plays: 31, source: 'office-share/lib', bitrate: '16/44 FLAC' },
  ];

  // ---- SMB shares ----
  const SHARES = [
    { id: 'sh1', name: 'attic-nas',     host: '192.168.1.42',  port: 445, share: 'music',     mount: '//attic-nas/music',     user: 'media',  protocol: 'SMB3', status: 'connected', tracks: 12483, size: '482 GB',  lastSync: '2 min ago',  signal: 0.94 },
    { id: 'sh2', name: 'family-nas',    host: 'family.local',  port: 445, share: 'jazz',      mount: '//family.local/jazz',   user: 'guest',  protocol: 'SMB3', status: 'connected', tracks: 3142,  size: '128 GB',  lastSync: '11 min ago', signal: 0.81 },
    { id: 'sh3', name: 'office-share',  host: '10.0.0.18',     port: 445, share: 'lib',       mount: '//10.0.0.18/lib',       user: 'rich',   protocol: 'SMB2', status: 'syncing',   tracks: 882,   size: '34 GB',   lastSync: 'syncing\u2026',     signal: 0.62 },
    { id: 'sh4', name: 'studio-pi',     host: 'pi.tail.ts.net',port: 445, share: 'master',    mount: '//pi.tail/master',      user: 'engineer',protocol: 'SMB3',status: 'offline',   tracks: 0,     size: '\u2014',         lastSync: '3 days ago', signal: 0.0 },
  ];

  // ---- Synced lyrics for "Continental Drift" ----
  const LYRICS = [
    { t:   0, text: '' },
    { t:   6, text: 'A radio left on in another room' },
    { t:  12, text: 'paints a slow rectangle on the floor' },
    { t:  19, text: 'and you, half-asleep, half\u00a0somewhere\u00a0else' },
    { t:  26, text: 'count the seconds out of habit, not\u00a0war.' },
    { t:  34, text: '' },
    { t:  38, text: 'Continental drift \u2014' },
    { t:  43, text: 'we were never meant to stand this still.' },
    { t:  50, text: 'Continental drift \u2014' },
    { t:  56, text: 'we were always going to lose the hill.' },
    { t:  64, text: '' },
    { t:  68, text: 'A train you didn\u2019t take leaves on time' },
    { t:  74, text: 'and a name you didn\u2019t call goes unsaid' },
    { t:  81, text: 'and the morning, ordinary, ordinary,' },
    { t:  88, text: 'sets a glass of water by the bed.' },
    { t:  96, text: '' },
    { t: 100, text: 'Continental drift \u2014' },
    { t: 106, text: 'inch by inch, the rooms rearrange themselves.' },
    { t: 113, text: 'Continental drift \u2014' },
    { t: 119, text: 'and you put your books on different shelves.' },
    { t: 128, text: '' },
  ];

  // ---- Queue order ----
  const QUEUE_IDS = ['s1','s2','s17','s7','s15','s23','s11','s5'];

  // ---- Recently played ----
  const RECENT_IDS = ['s17','s7','s3','s15','s1','s23'];

  // ---- Cloud storage providers ----
  // Original, generic provider tiles. No vendor branding/marks — just labels.
  const CLOUD_PROVIDERS = [
    { id: 'gdrive',    name: 'Google Drive',     auth: 'OAuth 2.0',     status: 'connected', account: 'rich@personal.example', tracks: 8421, size: '64 GB',  lastSync: '4 min ago', glyph: 'triangle' },
    { id: 'dropbox',   name: 'Dropbox',          auth: 'OAuth 2.0',     status: 'connected', account: 'r.wilkinsons',          tracks: 1240, size: '8.2 GB', lastSync: '12 min ago', glyph: 'diamond' },
    { id: 'onedrive',  name: 'OneDrive',         auth: 'Microsoft SSO', status: 'expired',   account: 'rich@office.example',   tracks: 0,    size: '\u2014', lastSync: 'token expired',    glyph: 'cloud' },
    { id: 'icloud',    name: 'iCloud Drive',     auth: 'App password',  status: 'connected', account: 'rich@icloud.example',   tracks: 542,  size: '3.1 GB', lastSync: '1 hr ago',    glyph: 'circle' },
    { id: 'webdav',    name: 'WebDAV',           auth: 'Basic',         status: 'idle',      account: 'nextcloud.home.lan',    tracks: 0,    size: '\u2014', lastSync: 'never',           glyph: 'square' },
    { id: 's3',        name: 'S3-compatible',    auth: 'Access key',    status: 'idle',      account: 'add bucket\u2026',      tracks: 0,    size: '\u2014', lastSync: 'never',           glyph: 'hexagon' },
    { id: 'jellyfin',  name: 'Jellyfin server',  auth: 'API key',       status: 'idle',      account: 'jellyfin.home.lan',     tracks: 0,    size: '\u2014', lastSync: 'never',           glyph: 'pentagon' },
    { id: 'box',       name: 'Box',              auth: 'OAuth 2.0',     status: 'idle',      account: 'sign in\u2026',         tracks: 0,    size: '\u2014', lastSync: 'never',           glyph: 'parallelogram' },
  ];

  return { ALBUMS, SONGS, SHARES, LYRICS, QUEUE_IDS, RECENT_IDS, CLOUD_PROVIDERS };
})();
