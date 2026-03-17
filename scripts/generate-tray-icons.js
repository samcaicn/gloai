/**
 * Generate tray icons for Windows, macOS, and Linux from a source PNG.
 * Works on both macOS and Windows.
 */

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const os = require('os');

const SOURCE = path.join(__dirname, '..', 'public', 'logo.png');
const OUTPUT_DIR = path.join(__dirname, '..', 'resources', 'tray');

function isWindows() {
  return os.platform() === 'win32';
}

function resizeImageMac(src, dest, size) {
  execSync(`sips -z ${size} ${size} "${src}" --out "${dest}"`, { stdio: 'inherit' });
}

function resizeImageWindows(src, dest, size) {
  const tmpDir = path.join(__dirname, '..', 'build', 'icons', '_tmp');
  fs.mkdirSync(tmpDir, { recursive: true });

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

function createIco() {
  const tmpDir = path.join(__dirname, '..', 'build', 'icons', '_tmp');
  fs.mkdirSync(tmpDir, { recursive: true });

  const sizes = [16, 32, 48];
  const pngBuffers = [];

  for (const size of sizes) {
    const outPath = path.join(tmpDir, `tray-${size}.png`);
    resizeImage(SOURCE, outPath, size);
    pngBuffers.push({ size, data: fs.readFileSync(outPath) });
  }

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

  const icoPath = path.join(OUTPUT_DIR, 'tray-icon.ico');
  fs.writeFileSync(icoPath, ico);
  console.log(`Generated ${icoPath} — ${ico.length} bytes`);
}

function createTrayIcons() {
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  console.log('Generating tray icons...');

  const linuxPng = path.join(OUTPUT_DIR, 'tray-icon.png');
  resizeImage(SOURCE, linuxPng, 48);
  console.log('Generated tray-icon.png (48x48)');

  createIco();

  const macSizes = [
    { size: 16, out: 'tray-icon-mac.png', template: 'trayIconTemplate.png' },
    { size: 32, out: 'tray-icon-mac@2x.png', template: 'trayIconTemplate@2x.png' }
  ];

  for (const { size, out } of macSizes) {
    const outPath = path.join(OUTPUT_DIR, out);
    resizeImage(SOURCE, outPath, size);
    console.log(`Generated ${out} (${size}x${size})`);
  }

  console.log(`\nTray icons generated successfully in ${OUTPUT_DIR}`);
}

createTrayIcons();
