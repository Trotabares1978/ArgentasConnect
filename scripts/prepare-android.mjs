import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const root = process.cwd();
const android = path.join(root, 'android');
const native = path.join(root, 'native', 'android', 'cómo', 'argentas', 'app', 'ArgentasSyncPlugin.java');
const iconSource = path.join(root, 'native', 'android', 'icon', 'argentas_icon.jpg');

const targetDir = path.join(android, 'app', 'src', 'main', 'java', 'com', 'argentas', 'app');
fs.mkdirSync(targetDir, { recursive: true });
fs.copyFileSync(native, path.join(targetDir, 'ArgentasSyncPlugin.java'));

// Nearby Connections 19.5.0 requires Android API 24+ at runtime/build time.
const variablesGradle = path.join(android, 'variables.gradle');
if (fs.existsSync(variablesGradle)) {
  let v = fs.readFileSync(variablesGradle, 'utf8');
  v = v.replace(/minSdkVersion\s*=\s*23/g, 'minSdkVersion = 24');
  fs.writeFileSync(variablesGradle, v);
}

const resDir = path.join(android, 'app', 'src', 'main', 'res');
if (fs.existsSync(iconSource)) {
  for (const file of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
    const p = path.join(resDir, 'mipmap-anydpi-v26', file);
    if (fs.existsSync(p)) fs.unlinkSync(p);
  }

  // Crop the outer 10% on each side so the Argentas artwork fills the launcher icon.
  const croppedIcon = path.join(root, '.argentas_launcher_icon.png');
  const magick = fs.existsSync('/usr/bin/magick') ? '/usr/bin/magick' : 'convert';
  execFileSync(magick, [
    iconSource, '-gravity', 'center', '-crop', '80%x80%+0+0', '+repage',
    '-resize', '1024x1024!', croppedIcon
  ], { stdio: 'inherit' });

  for (const density of ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi']) {
    const iconDir = path.join(resDir, 'mipmap-' + density);
    fs.mkdirSync(iconDir, { recursive: true });
    for (const file of ['ic_launcher.png', 'ic_launcher.jpg', 'ic_launcher_round.png', 'ic_launcher_round.jpg']) {
      const p = path.join(iconDir, file);
      if (fs.existsSync(p)) fs.unlinkSync(p);
    }
    fs.copyFileSync(croppedIcon, path.join(iconDir, 'ic_launcher.png'));
    fs.copyFileSync(croppedIcon, path.join(iconDir, 'ic_launcher_round.png'));
  }
  fs.unlinkSync(croppedIcon);
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

const appGradleForSdk = path.join(android, 'app', 'build.gradle');
if (fs.existsSync(appGradleForSdk)) {
  let gSdk = fs.readFileSync(appGradleForSdk, 'utf8');
  const minSdkLine = /minSdkVersion[^\n]*/g;
  if (minSdkLine.test(gSdk)) {
    gSdk = gSdk.replace(minSdkLine, 'minSdkVersion 24');
  } else if (gSdk.includes('defaultConfig {')) {
    gSdk = gSdk.replace('defaultConfig {', 'defaultConfig {\n        minSdkVersion 24');
  } else {
    throw new Error('No se encontró defaultConfig en app/build.gradle');
  }
  fs.writeFileSync(appGradleForSdk, gSdk);
}

const appGradle = path.join(android, 'app', 'build.gradle');
if (fs.existsSync(appGradle)) {
  let g = fs.readFileSync(appGradle, 'utf8');
  if (!g.includes('play-services-nearby')) {
    const deps = /dependencies\s*\{/;
    if (!deps.test(g)) throw new Error('No se encontró dependencies en app/build.gradle');
    g = g.replace(deps, 'dependencies {\n    implementation \'com.google.android.gms:play-services-nearby:19.5.0\'');
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
