import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const android = path.join(root, 'android');
const native = path.join(root, 'native', 'android', 'cómo', 'argentas', 'app', 'ArgentasSyncPlugin.java');

const targetDir = path.join(android, 'app', 'src', 'main', 'java', 'com', 'argentas', 'app');
fs.mkdirSync(targetDir, { recursive: true });
fs.copyFileSync(native, path.join(targetDir, 'ArgentasSyncPlugin.java'));

const mainActivity = path.join(targetDir, 'MainActivity.java');
if (fs.existsSync(mainActivity)) {
  let s = fs.readFileSync(mainActivity, 'utf8');
  if (!s.includes('ArgentasSyncPlugin')) {
    s = s.replace(/package ([^;]+);/, 'package $1;\n\nimport android.os.Bundle;\nimport com.argentas.app.ArgentasSyncPlugin;');
    s = s.replace(/public class MainActivity extends BridgeActivity \{/, 'public class MainActivity extends BridgeActivity {\n    @Override\n    public void onCreate(Bundle savedInstanceState) {\n        super.onCreate(savedInstanceState);\n        registerPlugin(ArgentasSyncPlugin.class);\n    }');
    fs.writeFileSync(mainActivity, s);
  }
}

const manifest = path.join(android, 'app', 'src', 'main', 'AndroidManifest.xml');
if (fs.existsSync(manifest)) {
  let s = fs.readFileSync(manifest, 'utf8');
  const perms = [
    '<uses-permission android:name="android.permission.INTERNET" />',
    '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
    '<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />'
  ];
  const missing = perms.filter((p) => !s.includes(p));
  if (missing.length) {
    const applicationTag = /\n\s*<application\b/;
    if (!applicationTag.test(s)) throw new Error('No se encontró la etiqueta <application> en AndroidManifest.xml');
    s = s.replace(applicationTag, '\n    ' + missing.join('\n    ') + '\n\n    <application');
  }
  fs.writeFileSync(manifest, s);
}