/* Metadata only. No form values, cookies, tokens, audio URLs or page HTML are returned. */
(function (advance) {
  'use strict';
  const host = location.hostname.toLowerCase();
  const hosts = ['vk.com', 'vk.ru', 'm.vk.com', 'm.vk.ru', 'music.vk.com', 'music.vk.ru'];
  if (location.protocol !== 'https:' || !hosts.includes(host) || !/^\/(audio(?:s[0-9]+)?|music)(\/|$)/.test(location.pathname)) {
    return JSON.stringify({error: 'NOT_MUSIC_PAGE'});
  }
  if (document.querySelector('input[type="password"], #captcha, .Captcha, [data-testid="captcha"]')) {
    return JSON.stringify({error: 'LOGIN_OR_CAPTCHA'});
  }
  const clean = text => String(text || '').replace(/\s+/g, ' ').trim();
  const text = (node, selectors) => {
    for (const selector of selectors) {
      const found = node.querySelector(selector);
      if (found && clean(found.textContent)) return clean(found.textContent);
    }
    return '';
  };
  function playlistId(url) {
    const match = decodeURIComponent(url).match(/(?:audio_playlist|\/music\/playlist\/)(-?\d+)_(\d+)/);
    return match ? 'playlist:' + match[1] + ':' + match[2] : null;
  }
  const sourceId = playlistId(location.href);
  const dialog = document.querySelector('.audio_layer, .audio_playlist_layer');
  const root = dialog || document.querySelector('.audio_page__audio_rows, .audio_page__audios, #audio_list, [data-testid="audio-list"]');
  const scope = root || document;
  const rows = Array.from(scope.querySelectorAll('.audio_row, .audio_item, [data-testid="audio-row"]'));
  const occurrence = new Map();
  let unparsedRows = 0;
  let ambiguousPositions = false;
  const tracks = [];
  rows.forEach(row => {
    const title = text(row, ['.audio_row__title_inner', '.audio_row__title', '.ai_title', '[data-testid="audio-title"]']);
    const artist = text(row, ['.audio_row__performers', '.audio_row__performer', '.ai_artist', '[data-testid="audio-artist"]']);
    if (!title || !artist) { unparsedRows++; return; }
    const rawId = row.getAttribute('data-full-id') || row.getAttribute('data-audio') || '';
    const vkId = /^-?\d+_\d+$/.test(rawId) ? rawId : null;
    const identity = vkId || JSON.stringify([artist, title]);
    const count = occurrence.get(identity) || 0;
    occurrence.set(identity, count + 1);
    const position = row.getAttribute('data-index') || row.getAttribute('data-position');
    if (position == null) ambiguousPositions = true;
    const key = position != null ? 'pos:' + position : identity + '#occ:' + count;
    tracks.push({key, title, artist, vkId});
  });
  const playlists = [];
  // Only the user's dedicated playlist container; recommendations elsewhere are excluded.
  const playlistRoot = document.querySelector('.audio_page__playlists, .audio_page__playlists_list, #audio_playlists, [data-testid="playlist-list"]');
  const links = playlistRoot ? playlistRoot.querySelectorAll('a[href*="audio_playlist"], a[href*="/music/playlist/"]') : [];
  const seen = new Set();
  links.forEach(link => {
    try {
      const url = new URL(link.getAttribute('href'), location.href);
      const id = playlistId(url.href);
      if (!id || seen.has(id) || url.protocol !== 'https:' || !hosts.includes(url.hostname)) return;
      seen.add(id);
      playlists.push({id, title: clean(link.getAttribute('title') || link.textContent) || id, url: url.href});
    } catch (_) { /* Unsupported URL is not followed. */ }
  });
  let expectedCount = null;
  const counter = scope.querySelector('[data-testid="audio-count"], .audio_page__count, .audio_playlist__count');
  if (counter) {
    const match = clean(counter.textContent).match(/^(\d[\d\s]*)\s*(?:трек\S*|аудиозапис\S*|songs?|tracks?)?$/i);
    if (match) expectedCount = Number(match[1].replace(/\s/g, ''));
  }
  let scrollable = root;
  while (scrollable && !(scrollable.scrollHeight > scrollable.clientHeight + 3 && /auto|scroll/.test(getComputedStyle(scrollable).overflowY))) {
    scrollable = scrollable.parentElement;
  }
  scrollable = scrollable || document.scrollingElement;
  const atBottom = !scrollable || scrollable.scrollTop + scrollable.clientHeight >= scrollable.scrollHeight - 8;
  if (advance && scrollable) scrollable.scrollTop += Math.max(240, scrollable.clientHeight * 0.7);
  const uid = Number(window.vk && window.vk.id);
  return JSON.stringify({
    userId: Number.isSafeInteger(uid) && uid > 0 ? uid : null,
    sourceId,
    sourceTitle: sourceId ? text(document, ['.audio_playlist__title', '.audio_pl_snippet__title', 'h1']) : 'Моя музыка',
    expectedCount, scoped: !!root, playlistsScoped: !!playlistRoot, atBottom, tracks, playlists, unparsedRows, ambiguousPositions
  });
})(true);
