/**
 * Generate app icons for both Windows (.ico) and macOS (.icns) from a source PNG.
 * Works on both macOS and Windows.
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const os = require('os');

const SOURCE = path.join(__dirname, '..', 'public', 'logo.png');
const ICONS_DIR = path.join(__dirname, '..', 'build', 'icons');
const WIN_DIR = path.join(ICONS_DIR, 'win');
const MAC_DIR = path.join(ICONS_DIR, 'mac');
const PNG_DIR = path.join(ICONS_DIR, 'png');
const OUT_ICO = path.join(WIN_DIR, 'icon.ico');
const SIZES = [256, 128, 64, 48, 32, 16];
const MAC_SIZES = [16, 32, 128, 256, 512];

const tmpDir = path.join(ICONS_DIR, '_tmp');
fs.mkdirSync(tmpDir, { recursive: true });

function isWindows() {
  return os.platform() === 'win32';
}

function resizeImageMac(src, dest, size) {
  execSync(`sips -z ${size} ${size} "${src}" --out "${dest}"`, { stdio: 'inherit' });
}

function resizeImageWindows(src, dest, size) {
  const psScript = `
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile("${src.replace(/\\/g, '\\\\')}")
$bmp = New-Object System.Drawing.Bitmap($src)
$bmpNew = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmpNew)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.DrawImage($bmp, 0, 0, $size, $size)
$g.Dispose()
$bmpNew.Save("${dest.replace(/\\/g, '\\\\')}", [System.Drawing.Imaging.ImageFormat]::Png)
$bmpNew.Dispose()
$bmp.Dispose()
`;
  const psFile = path.join(tmpDir, 'resize.ps1');
  fs.writeFileSync(psFile, psScript, 'utf8');
  execSync(`powershell -ExecutionPolicy Bypass -File "${psFile}"`, { stdio: 'inherit' });
}

function resizeImage(src, dest, size) {
  if (isWindows()) {
    resizeImageWindows(src, dest, size);
  } else {
    resizeImageMac(src, dest, size);
  }
}

function generateWindowsIcon() {
  fs.mkdirSync(WIN_DIR, { recursive: true });

  console.log('Generating Windows icons...');
  for (const size of SIZES) {
    const outPath = path.join(tmpDir, `icon_${size}.png`);
    resizeImage(SOURCE, outPath, size);
    console.log(`  Generated ${size}x${size}`);
  }

  const pngBuffers = SIZES.map(s => {
    const p = path.join(tmpDir, `icon_${s}.png`);
    return { size: s, data: fs.readFileSync(p) };
  });

  const count = pngBuffers.length;
  const headerSize = 6;
  const entrySize = 16;
  const dataOffset0 = headerSize + entrySize * count;

  let currentOffset = dataOffset0;
  const entries = pngBuffers.map(({ size, data }) => {
    const entry = {
      width: size >= 256 ? 0 : size,
      height: size >= 256 ? 0 : size,
      dataSize: data.length,
      offset: currentOffset,
      data,
    };
    currentOffset += data.length;
    return entry;
  });

  const totalSize = currentOffset;
  const ico = Buffer.alloc(totalSize);

  ico.writeUInt16LE(0, 0);
  ico.writeUInt16LE(1, 2);
  ico.writeUInt16LE(count, 4);

  entries.forEach((e, i) => {
    const off = headerSize + i * entrySize;
    ico.writeUInt8(e.width, off + 0);
    ico.writeUInt8(e.height, off + 1);
    ico.writeUInt8(0, off + 2);
    ico.writeUInt8(0, off + 3);
    ico.writeUInt16LE(1, off + 4);
    ico.writeUInt16LE(32, off + 6);
    ico.writeUInt32LE(e.dataSize, off + 8);
    ico.writeUInt32LE(e.offset, off + 12);
  });

  entries.forEach(e => {
    e.data.copy(ico, e.offset);
  });

  fs.writeFileSync(OUT_ICO, ico);
  console.log(`Generated ${OUT_ICO} — ${ico.length} bytes`);
}

function generateMacIcon() {
  fs.mkdirSync(MAC_DIR, { recursive: true });
  fs.mkdirSync(PNG_DIR, { recursive: true });

  const iconSetDir = path.join(tmpDir, 'icon.iconset');
  if (fs.existsSync(iconSetDir)) {
    fs.rmSync(iconSetDir, { recursive: true });
  }
  fs.mkdirSync(iconSetDir, { recursive: true });

  console.log('Generating macOS icons...');
  for (const size of MAC_SIZES) {
    const outPath = path.join(tmpDir, `icon_${size}.png`);
    resizeImage(SOURCE, outPath, size);

    const destPng = path.join(iconSetDir, `icon_${size}x${size}.png`);
    fs.copyFileSync(outPath, destPng);
    console.log(`  Added ${size}x${size}`);

    const outPath2x = path.join(tmpDir, `icon_${size}x${size}@2x.png`);
    resizeImage(SOURCE, outPath2x, size * 2);
    const destPng2x = path.join(iconSetDir, `icon_${size}x${size}@2x.png`);
    fs.copyFileSync(outPath2x, destPng2x);
    console.log(`  Added ${size}x${size}@2x (${size * 2}x${size * 2})`);

    const pngOutPath = path.join(PNG_DIR, `${size}x${size}.png`);
    fs.copyFileSync(outPath, pngOutPath);
  }

  const icnsPath = path.join(MAC_DIR, 'icon.icns');
  if (fs.existsSync(icnsPath)) {
    fs.copyFileSync(icnsPath, path.join(MAC_DIR, 'icon.icns.backup'));
    console.log('Backed up old icon to icon.icns.backup');
  }

  try {
    execSync(`iconutil -c icns "${iconSetDir}" -o "${icnsPath}"`, { stdio: 'inherit' });
    console.log(`Generated ${icnsPath}`);
  } catch (e) {
    console.error('Failed to generate .icns file:', e.message);
    if (fs.existsSync(path.join(MAC_DIR, 'icon.icns.backup'))) {
      fs.copyFileSync(path.join(MAC_DIR, 'icon.icns.backup'), icnsPath);
    }
  }

  fs.rmSync(iconSetDir, { recursive: true });
  console.log('Cleaned up temporary iconset directory');
}

console.log(`Platform: ${os.platform()}`);
console.log('\nGenerating Windows icon...');
generateWindowsIcon();

console.log('\nGenerating macOS icon...');
generateMacIcon();

console.log('\nCleaning up temp files...');
fs.rmSync(tmpDir, { recursive: true, force: true });

console.log('\n All icons generated successfully!');
console.log('Now you can rebuild with: npm run dist:mac');
