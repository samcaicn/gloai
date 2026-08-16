const fs = require('fs');
const path = require('path');

// Check both workflow files
const files = [
  '.github/workflows/build.yml',
  '.github/workflows/ci-validate.yml',
];

for (const file of files) {
  const fullPath = path.resolve(file);
  try {
    const content = fs.readFileSync(fullPath, 'utf8');
    // Check for BOM
    const bom = content.charCodeAt(0) === 0xFEFF;
    // Check for null bytes or invalid UTF-8
    const buf = fs.readFileSync(fullPath);
    let hasInvalidBytes = false;
    for (let i = 0; i < buf.length; i++) {
      if (buf[i] === 0) { hasInvalidBytes = true; break; }
    }
    console.log(`${file}: ${bom ? 'BOM ' : 'no-BOM '} ${hasInvalidBytes ? 'HAS-INVALID-BYTES' : 'clean'} (${buf.length} bytes)`);
    
    // Try to find non-ASCII characters that might be garbled
    const lines = content.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      // Check for common garbled patterns (GBK-interpreted-as-UTF8)
      if (/[\uFFFD\uE000-\uF8FF]/.test(line)) {
        console.log(`  Line ${i+1}: REPLACEMENT/PRIVATE CHARS: ${line.substring(0,80)}`);
      }
    }
  } catch (e) {
    console.log(`${file}: ERROR - ${e.message}`);
  }
}
