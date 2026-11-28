(function () {
  var cur = document.currentScript, base = '';
  if (cur && cur.src) base = cur.src.replace(/app\.js(\?.*)?$/, '');
  function modern() {
    if (/[?&]legacy=1/.test(location.search)) return false;
    try {
      new Function('"use strict"; let a = 1; const f = (x) => x; class C {} return `${f(a)}`;')();
      return !!(window.fetch && window.Promise && window.Symbol && window.CSS && CSS.supports && CSS.supports('(--a: 0)'));
    } catch (e) { return false; }
  }
  var useModern = modern();
  var legacy = true;
  var name = useModern || !legacy ? 'app.modern' : 'app.legacy';
  window.__EV_BUNDLE__ = name;
  if (name === 'app.legacy') {
    var links = document.getElementsByTagName('link');
    for (var i = 0; i < links.length; i++) if (/app\.css(\?|$)/.test(links[i].href)) links[i].href = base + 'app.legacy.css';
  }
  var s = document.createElement('script'); s.src = base + name + '.js';
  document.body.appendChild(s);
})();
