const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {runInNewContext} = require('node:vm');
const script = readFileSync('app/src/main/assets/vk-extractor.js', 'utf8');

// Small DOM fixtures model the web surface. Forbidden accessors throw immediately.
function fixture({host = 'vk.ru', path = '/audios123', rows = [], playlistLinks = [], root = true, login = false} = {}) {
  const scroll = {scrollTop: 0, clientHeight: 600, scrollHeight: 1600};
  const list = {
    parentElement: null, scrollHeight: 0, clientHeight: 0,
    querySelectorAll: () => rows.map(({title, artist, id, position}) => ({
      getAttribute: name => ({'data-full-id': id, 'data-index': position}[name] ?? null),
      querySelector: selector => {
        if (['.audio_row__title_inner', '.ai_title'].includes(selector)) return title ? {textContent: title} : null;
        if (['.audio_row__performers', '.ai_artist'].includes(selector)) return artist ? {textContent: artist} : null;
        return null;
      }
    })),
    querySelector: () => null
  };
  const doc = {
    scrollingElement: scroll,
    querySelector: selector => {
      if (selector.startsWith('input')) return login ? {} : null;
      if (selector.startsWith('.audio_layer')) return null;
      if (selector.startsWith('.audio_page__audio_rows')) return root ? list : null;
      if (selector.startsWith('.audio_page__playlists')) return playlistLinks.length ? {
        querySelectorAll: () => playlistLinks.map(url => ({getAttribute: n => n === 'href' ? url : null, textContent: 'Мой список'}))
      } : null;
      return null;
    },
    querySelectorAll: selector => selector.startsWith('.audio_row') ? list.querySelectorAll(selector) : [],
    get cookie() { throw Error('Cookie access is forbidden'); }
  };
  const href = 'https://' + host + path;
  const env = {document: doc, window: {vk: {id: 123}}, location: {protocol: 'https:', hostname: host, pathname: path, href},
    URL, getComputedStyle: () => ({overflowY: 'visible'})};
  return {run: (advance = true) => JSON.parse(runInNewContext(advance ? script : script.replace('})(true);', '})(false);'), env)), scroll};
}

test('extracts Unicode metadata and preserves repeated playlist entries', () => {
  const page = fixture({rows: [
    {id: '123_456', title: 'Песня 🎵', artist: 'Артист'},
    {id: '123_456', title: 'Песня 🎵', artist: 'Артист'}
  ]}).run();
  assert.equal(page.tracks.length, 2);
  assert.equal(page.tracks[0].title, 'Песня 🎵');
  assert.notEqual(page.tracks[0].key, page.tracks[1].key);
  assert.equal(page.ambiguousPositions, true);
});
test('stable positions support repeated reads of virtualized rows', () => {
  const f = fixture({rows: [{id: '123_456', title: 'Song', artist: 'Artist', position: '92'}]});
  assert.equal(f.run().tracks[0].key, 'pos:92');
  assert.equal(f.run().tracks[0].key, 'pos:92');
  assert.equal(f.run().ambiguousPositions, false);
});
test('missing artist is reported, not fabricated', () => {
  const page = fixture({rows: [{id: '123_456', title: 'Unknown'}]}).run();
  assert.equal(page.tracks.length, 0);
  assert.equal(page.unparsedRows, 1);
});
test('refuses auth and impersonation hosts before reading any DOM', () => {
  assert.equal(fixture({host: 'vk.ru.evil.example'}).run().error, 'NOT_MUSIC_PAGE');
  assert.equal(fixture({host: 'id.vk.com', path: '/authorize'}).run().error, 'NOT_MUSIC_PAGE');
  assert.equal(fixture({login: true}).run().error, 'LOGIN_OR_CAPTCHA');
});
test('playlist discovery filters external links and removes repeated links', () => {
  const page = fixture({playlistLinks: ['https://vk.ru/music/playlist/123_45_key', 'https://vk.ru/music/playlist/123_45_key', 'https://evil.example/music/playlist/1_2']}).run();
  assert.equal(page.playlists.length, 1);
  assert.equal(page.playlists[0].id, 'playlist:123:45');
  assert.equal(page.playlistsScoped, true);
});
test('does not treat an unknown page container as confirmed', () => {
  const page = fixture({root: false}).run();
  assert.equal(page.scoped, false);
  assert.equal(page.playlistsScoped, false);
});
test('metadata never includes cookies or audio stream fields', () => {
  const page = fixture({rows: [{id: '123_456', title: 'Song', artist: 'Artist'}]}).run();
  assert.deepEqual(Object.keys(page.tracks[0]).sort(), ['artist', 'key', 'title', 'vkId']);
  assert.equal('cookie' in page, false);
});
test('scrolls after capturing current rows; passive sample does not advance', () => {
  const f = fixture();
  f.run();
  assert.equal(f.scroll.scrollTop, 420);
  f.run(false);
  assert.equal(f.scroll.scrollTop, 420);
});
