// Dijalankan setelah `npx cap add android`:
// pasang plugin LAN, MainActivity, izin jaringan, dan layar potret.
const fs = require('fs');
const path = require('path');

const pkgDir = path.join('android', 'app', 'src', 'main', 'java', 'com', 'dzaky', 'fairblocks');
fs.mkdirSync(pkgDir, { recursive: true });
for (const f of ['LanPlugin.java', 'MainActivity.java']) {
  fs.copyFileSync(path.join('android-src', f), path.join(pkgDir, f));
  console.log('copied', f);
}

const mf = path.join('android', 'app', 'src', 'main', 'AndroidManifest.xml');
let xml = fs.readFileSync(mf, 'utf8');
const perms = [
  'android.permission.INTERNET',
  'android.permission.ACCESS_NETWORK_STATE',
  'android.permission.ACCESS_WIFI_STATE',
  'android.permission.CHANGE_WIFI_MULTICAST_STATE',
  'android.permission.VIBRATE',
];
for (const p of perms) {
  if (!xml.includes(`"${p}"`)) {
    xml = xml.replace('</manifest>', `    <uses-permission android:name="${p}" />\n</manifest>`);
  }
}
// orientasi diatur di MainActivity (HP potret, tablet bebas putar)
xml = xml.replace(/\s*android:screenOrientation="[^"]*"/, '');
fs.writeFileSync(mf, xml);
console.log('manifest patched');

// ---- ikon aplikasi + splash ----
const resDir = path.join('android', 'app', 'src', 'main', 'res');
// hapus splash.png bawaan Capacitor (diganti drawable/splash.xml)
for (const d of fs.readdirSync(resDir)) {
  const f = path.join(resDir, d, 'splash.png');
  if (d.startsWith('drawable') && fs.existsSync(f)) fs.unlinkSync(f);
}
function copyDir(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name), t = path.join(dst, e.name);
    if (e.isDirectory()) copyDir(s, t); else fs.copyFileSync(s, t);
  }
}
copyDir('android-res', resDir);
console.log('icons + splash copied');

// ---- versi naik tiap build → APK baru bisa dipasang menimpa yang lama ----
const gradle = path.join('android', 'app', 'build.gradle');
let g = fs.readFileSync(gradle, 'utf8');
const run = parseInt(process.env.GITHUB_RUN_NUMBER || '1', 10);
g = g.replace(/versionCode\s+\d+/, 'versionCode ' + (100 + run));
g = g.replace(/versionName\s+"[^"]*"/, 'versionName "1.' + run + '"');
fs.writeFileSync(gradle, g);
console.log('version set', 100 + run);
