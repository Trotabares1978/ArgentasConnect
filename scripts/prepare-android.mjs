import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const android = path.join(root, 'android');
const native = path.join(root, 'native', 'android', 'cómo', 'argentas', 'app', 'ArgentasSyncPlugin.java');
const iconSource = path.join(root, 'native', 'android', 'icon', 'argentas_icon.jpg');

const targetDir = path.join(android, 'app', 'src', 'main', 'java', 'com', 'argentas', 'app');
fs.mkdirSync(targetDir, { recursive: true });
fs.copyFileSync(native, path.join(targetDir, 'ArgentasSyncPlugin.java'));

const resDir = path.join(android, 'app', 'src', 'main', 'res');
if (fs.existsSync(iconSource)) {
  // Replace Capacitor's generated launcher resources in every density bucket.
  // Also remove adaptive-icon XMLs: otherwise Android 8+ prefers those XMLs
  // over the bitmap and the custom Argentas icon is never shown.
  for (const file of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
    const p = path.join(resDir, 'mipmap-anydpi-v26', file);
    if (fs.existsSync(p)) fs.unlinkSync(p);
  }

  for (const density of ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']) {
    const iconDir = path.join(resDir, 'mipmap-' + density);
    fs.mkdirSync(iconDir, { recursive: true });
    for (const file of ['ic_launcher.png', 'ic_launcher.jpg', 'ic_launcher_round.png', 'ic_launcher_round.jpg']) {
      const p = path.join(iconDir, file);
      if (fs.existsSync(p)) fs.unlinkSync(p);
    }
    fs.copyFileSync(iconSource, path.join(iconDir, 'ic_launcher.jpg'));
    fs.copyFileSync(iconSource, path.join(iconDir, 'ic_launcher_round.jpg'));
  }
} else {
  throw new Error('No se encontró el icono de Argentas en ' + iconSource);
}

const mainActivity = path.join(targetDir, 'MainActivity.java');
if (fs.existsSync(mainActivity)) {
  let s = fs.readFileSync(mainActivity, 'utf8');
  if (!s.includes('ArgentasSyncPlugin')) {
    s = s.replace(/package ([^;]+);/, 'package $1;\n\nimport android.os.Bundle;\nimport com.argentas.app.ArgentasSyncPlugin;');
    s = s.replace(/public class MainActivity extends BridgeActivity \{/, 'public class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n        super.onCreate(savedInstanceState);\n        registerPlugin(ArgentasSyncPlugin.class);\n    }');
    fs.writeFileSync(mainActivity, s);
  }
}

// Nearby Connections is a Google Play services Android dependency.
// Keep it here because the Android project is generated fresh on every CI build.
const appGradle = path.join(android, 'app', 'build.gradle');
if (fs.existsSync(appGradle)) {
  let g = fs.readFileSync(appGradle, 'utf8');
  if (!g.includes('play-services-nearby')) {
    const deps = /dependencies\\s*\\{/;
    if (!deps.test(g)) throw new Error('No se encontró dependencies en app/build.gradle');
    g = g.replace(deps, 'dependencies {\\n    implementation \'com.google.android.gms:play-services-nearby:19.5.0\'');
    fs.writeFileSync(appGradle, g);
  }
}

const manifest = path.join(android, 'app', 'src', 'main', 'AndroidManifest.xml');
if (fs.existsSync(manifest)) {
  let s = fs.readFileSync(manifest, 'utf8');
  const perms = [
    '<uses-permission android:name="android.permission.INTERNET" />',
    '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
    '<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />',
    '<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />',
    '<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />',
    '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />',
    '<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />'
  ];
  const missing = perms.filter((p) => !s.includes(p));
  if (missing.length) {
    const applicationTag = /\n\s*<application\b/;
    if (!applicationTag.test(s)) throw new Error('No se encontró la etiqueta <application> en AndroidManifest.xml');
    s = s.replace(applicationTag, '\n    ' + missing.join('\n    ') + '\n\n    <application');
  }
  s = s.replace(/android:icon="[^"]*"/, 'android:icon="@mipmap/ic_launcher"');
  if (s.includes('android:roundIcon=')) {
    s = s.replace(/android:roundIcon="[^"]*"/, 'android:roundIcon="@mipmap/ic_launcher_round"');
  } else {
    s = s.replace(/(<application\b[^>]*)(>)/, '$1 android:roundIcon="@mipmap/ic_launcher_round"$2');
  }
  fs.writeFileSync(manifest, s);
}
