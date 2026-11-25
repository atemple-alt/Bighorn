#!/usr/bin/env python3
"""Build the Samsung (Tizen) copy of Bighorn from the app's single index.html.

Samsung TVs block inline <script> and <style> blocks in packaged apps, which leaves
a black screen. So this moves them into separate files (app.css, app.js) and adds a
small diag.js that prints any startup error on screen.

usage: build_wgt.py <index.html> <out_dir>
"""
import re, sys, pathlib

src, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
html = src.read_text(encoding='utf-8')

# styles -> app.css
css = re.findall(r'<style>(.*?)</style>', html, re.S)
html = re.sub(r'\s*<style>.*?</style>', '', html, flags=re.S)
(out / 'app.css').write_text('\n'.join(css), encoding='utf-8')

# the CDN fallback for hls.js isn't needed: hls.min.js ships inside the package
html = re.sub(r'<script>window\.__hlsCdn=.*?</script>\s*', '', html, flags=re.S)
html = html.replace('<script src="hls.min.js" onerror="__hlsCdn()"></script>', '<script src="hls.min.js"></script>')

# remaining inline scripts -> app.js
js = re.findall(r'<script>(.*?)</script>', html, re.S)
html = re.sub(r'<script>.*?</script>', '', html, flags=re.S)
(out / 'app.js').write_text('\n;\n'.join(js), encoding='utf-8')
assert '<script>' not in html and '<style>' not in html and 'onerror=' not in html

(out / 'diag.js').write_text(r"""/* shows startup errors on the TV screen instead of a silent black screen */
(function () {
  var shown = 0;
  function show(msg) {
    if (shown++ > 4) return;
    var d = document.getElementById('bhErr');
    if (!d) {
      d = document.createElement('div'); d.id = 'bhErr';
      d.setAttribute('style', 'position:fixed;left:40px;right:40px;bottom:40px;z-index:99999;background:#3a0d0d;color:#fff;font:22px/1.4 sans-serif;padding:20px 26px;border-radius:10px;white-space:pre-wrap');
      (document.body || document.documentElement).appendChild(d);
      d.textContent = 'Bighorn hit an error (take a photo of this):';
    }
    d.textContent += '\n' + msg;
  }
  window.addEventListener('error', function (e) {
    if (e.target && e.target !== window && e.target.src) { show('Could not load ' + e.target.src); return; }
    show((e.message || e.error) + ' @' + (e.lineno || '?') + ':' + (e.colno || '?'));
  }, true);
  window.addEventListener('unhandledrejection', function (e) { show('Promise: ' + (e.reason && (e.reason.stack || e.reason.message) || e.reason)); });
  window.addEventListener('securitypolicyviolation', function (e) { show('Blocked by TV security policy: ' + e.violatedDirective + ' ' + (e.blockedURI || '')); });
  setTimeout(function () {
    var app = document.getElementById('app');
    if (app && !app.children.length && !document.querySelector('.intro')) show('The app never started (nothing rendered after 12s).');
  }, 12000);
})();
""", encoding='utf-8')

html = html.replace('</title>', '</title>\n<script src="diag.js"></script>\n<link rel="stylesheet" href="app.css">', 1)
html = html.replace('</body>', '<script src="app.js"></script>\n</body>', 1)
(out / 'index.html').write_text(html, encoding='utf-8')
print('wgt files:', sorted(p.name for p in out.iterdir()))
