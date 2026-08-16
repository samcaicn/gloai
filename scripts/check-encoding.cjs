const fs = require('fs');
const path = require('path');
const os = require('os');

const remoteFile = path.join(os.tmpdir(), 'remote_build_check.yml');
const buf = fs.readFileSync(remoteFile);

// Check line endings
let crlfCount = 0;
let lfCount = 0;
for (let i = 0; i < buf.length; i++) {
  if (buf[i] === 0x0A) {
    if (i > 0 && buf[i-1] === 0x0D) {
      crlfCount++;
    } else {
      lfCount++;
    }
  }
}
console.log(`Line endings: CRLF=${crlfCount}, LF=${lfCount}`);
console.log(`File has mixed line endings: ${crlfCount > 0 && lfCount > 0}`);

// Check for null bytes
let nullCount = 0;
for (let i = 0; i < buf.length; i++) {
  if (buf[i] === 0) nullCount++;
}
console.log(`Null bytes: ${nullCount}`);

// Check for other control characters (except \n, \r, \t)
let controlChars = 0;
for (let i = 0; i < buf.length; i++) {
  if (buf[i] < 0x20 && buf[i] !== 0x0A && buf[i] !== 0x0D && buf[i] !== 0x09) {
    controlChars++;
    if (controlChars <= 5) {
      console.log(`  Control char 0x${buf[i].toString(16)} at offset ${i}`);
    }
  }
}
console.log(`Other control characters: ${controlChars}`);

// Check first few bytes for BOM
console.log(`First 5 bytes: ${Array.from(buf.slice(0, 5)).map(b => '0x' + b.toString(16)).join(' ')}`);

// Check the on: key specifically
const content = buf.toString('utf8');
const lines = content.split(/\r?\n/);
for (let i = 0; i < lines.length; i++) {
  if (lines[i].match(/^on\s*:/)) {
    console.log(`Line ${i+1} (on key): "${lines[i]}" (bytes: ${Buffer.from(lines[i]).toString('hex')})`);
  }
}
