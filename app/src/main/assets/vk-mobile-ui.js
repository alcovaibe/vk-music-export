// UI only: never access form values, credentials, cookies, or page network APIs.
(function () {
  if (location.protocol !== 'https:' || !/^(?:m\.|music\.)?vk\.(?:ru|com)$/.test(location.hostname)) return;
  if (window.__vkMusicMobileUi) return;
  function hideDesktopLink() {
    document.querySelectorAll('a, button').forEach(function (element) {
      var label = (element.textContent || '').replace(/\s+/g, ' ').trim();
      if (/^(?:Версия для компьютера|Версия для ПК|Полная версия|Desktop version|Desktop site)$/i.test(label)) {
        element.style.setProperty('display', 'none', 'important');
      }
    });
  }
  // A WebView commit callback may run before documentElement exists.
  // The document itself already exists and observes later DOM construction.
  new MutationObserver(hideDesktopLink).observe(document, {childList: true, subtree: true, characterData: true});
  window.__vkMusicMobileUi = true;
  hideDesktopLink();
})();
