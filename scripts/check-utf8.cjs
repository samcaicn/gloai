const fs = require('fs');
const path = require('path');
const os = require('os');

const remoteFile = path.join(os.tmpdir(), 'remote_build_check.yml');
const buf = fs.readFileSync(remoteFile);

// Check for invalid UTF-8 sequences
let invalidCount = 0;
let invalidPositions = [];
for (let i = 0; i < buf.length; i++) {
  const byte = buf[i];
  // Check for invalid UTF-8 continuation bytes
  if (byte >= 0x80) {
    // Multi-byte sequence
    let expectedLen = 0;
    if ((byte & 0xE0) === 0xC0) expectedLen = 2;
    else if ((byte & 0xF0) === 0xE0) expectedLen = 3;
    else if ((byte & 0xF8) === 0xF0) expectedLen = 4;
    else {
      // Invalid leading byte
      invalidCount++;
      if (invalidPositions.length < 10) {
        invalidPositions.push({ offset: i, byte: byte.toString(16), context: buf.slice(Math.max(0,i-10), i+10).toString('hex') });
      }
      continue;
    }
    // Check continuation bytes
    for (let j = 1; j < expectedLen; j++) {
      if (i + j >= buf.length || (buf[i+j] & 0xC0) !== 0x80) {
        invalidCount++;
        if (invalidPositions.length < 10) {
          invalidPositions.push({ offset: i, byte: byte.toString(16), expectedLen, context: buf.slice(Math.max(0,i-10), Math.min(buf.length, i+10)).toString('hex') });
        }
        break;
      }
    }
    i += expectedLen - 1;
  }
}

console.log(`File size: ${buf.length} bytes`);
console.log(`Invalid UTF-8 sequences: ${invalidCount}`);
if (invalidPositions.length > 0) {
  console.log('First invalid positions:');
  invalidPositions.forEach(p => console.log(`  offset ${p.offset}: byte 0x${p.byte}, context: ${p.context}`));
}

// Also check for the garbled emoji characters
const content = buf.toString('utf8');
const lines = content.split('\n');
let garbledLines = 0;
for (let i = 0; i < lines.length; i++) {
  // Check for common garbled patterns
  if (/[\u4e00-\u9fff]{2,}/.test(lines[i]) && /echo/.test(lines[i])) {
    // Chinese characters in echo statements might be garbled emojis
    garbledLines++;
    if (garbledLines <= 5) {
      console.log(`  Line ${i+1} (possible garbled): ${lines[i].substring(0, 100)}`);
    }
  }
}
if (garbledLines > 0) {
  console.log(`Lines with possible garbled characters: ${garbledLines}`);
}
