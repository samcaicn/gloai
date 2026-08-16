import fs from 'fs';
const d = JSON.parse(fs.readFileSync('c:\\code\\safeopcAPP\\src-tauri\\gen\\schemas\\acl-manifests.json', 'utf-8'));
const cw = d['core:window'] || {};
const result = [];
result.push('DEFAULT: ' + JSON.stringify((cw.default_permission || {}).permissions || []));
result.push('AVAILABLE: ' + JSON.stringify(Object.keys(cw.permissions || {})));
result.push('PERMISSION_SETS: ' + JSON.stringify(Object.keys(cw.permission_sets || {})));
result.push('---');
result.push('Default permission sub-permissions:');
for (const p of ((cw.default_permission || {}).permissions || [])) {
  const permInfo = (cw.permissions || {})[p] || (cw.permission_sets || {})[p];
  if (permInfo) {
    if (permInfo.permissions) {
      result.push(`  ${p} -> set with: ${JSON.stringify(permInfo.permissions)}`);
    }
    if (permInfo.commands) {
      result.push(`  ${p} -> commands: ${JSON.stringify(permInfo.commands)}`);
    }
  }
}
fs.writeFileSync('c:\\code\\safeopcAPP\\perms_output.txt', result.join('\n'));
