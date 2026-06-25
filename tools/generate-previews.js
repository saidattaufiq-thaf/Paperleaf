const fs = require('fs');
const path = require('path');
const PNG = require('pngjs').PNG;

const THEMES_DIR = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'themes');
const W = 200;
const H = 120;

function hexToRgba(hex) {
  const c = hex.replace('#', '');
  return {
    r: parseInt(c.substring(0, 2), 16),
    g: parseInt(c.substring(2, 4), 16),
    b: parseInt(c.substring(4, 6), 16),
    a: c.length === 8 ? parseInt(c.substring(6, 8), 16) : 255
  };
}

function setPixel(png, x, y, r, g, b, a) {
  const idx = (y * png.width + x) * 4;
  png.data[idx] = r;
  png.data[idx + 1] = g;
  png.data[idx + 2] = b;
  png.data[idx + 3] = a;
}

function fillRect(png, x1, y1, w, h, r, g, b, a) {
  for (let y = y1; y < y1 + h && y < H; y++) {
    for (let x = x1; x < x1 + w && x < W; x++) {
      setPixel(png, x, y, r, g, b, a);
    }
  }
}

function roundRect(png, x1, y1, w, h, rad, r, g, b, a) {
  for (let y = y1; y < y1 + h && y < H; y++) {
    for (let x = x1; x < x1 + w && x < W; x++) {
      // Check if inside rounded corners
      let inside = true;
      if (x < x1 + rad && y < y1 + rad) {
        const dx = x - (x1 + rad);
        const dy = y - (y1 + rad);
        inside = (dx * dx + dy * dy <= rad * rad);
      } else if (x > x1 + w - rad - 1 && y < y1 + rad) {
        const dx = x - (x1 + w - rad - 1);
        const dy = y - (y1 + rad);
        inside = (dx * dx + dy * dy <= rad * rad);
      } else if (x < x1 + rad && y > y1 + h - rad - 1) {
        const dx = x - (x1 + rad);
        const dy = y - (y1 + h - rad - 1);
        inside = (dx * dx + dy * dy <= rad * rad);
      } else if (x > x1 + w - rad - 1 && y > y1 + h - rad - 1) {
        const dx = x - (x1 + w - rad - 1);
        const dy = y - (y1 + h - rad - 1);
        inside = (dx * dx + dy * dy <= rad * rad);
      }
      if (inside) setPixel(png, x, y, r, g, b, a);
    }
  }
}

function drawCircle(png, cx, cy, radius, r, g, b, a) {
  for (let y = cy - radius; y <= cy + radius && y < H; y++) {
    for (let x = cx - radius; x <= cx + radius && x < W; x++) {
      const dx = x - cx, dy = y - cy;
      if (dx * dx + dy * dy <= radius * radius) {
        setPixel(png, x, y, r, g, b, a);
      }
    }
  }
}

function lighten(r, g, b, factor) {
  return {
    r: Math.min(255, Math.round(r + (255 - r) * factor)),
    g: Math.min(255, Math.round(g + (255 - g) * factor)),
    b: Math.min(255, Math.round(b + (255 - b) * factor))
  };
}

function generatePreview(themeDir, colors) {
  const png = new PNG({ width: W, height: H });
  const bg = hexToRgba(colors.background);
  const surface = hexToRgba(colors.surface);
  const surfaceVariant = hexToRgba(colors.surfaceVariant);
  const primary = hexToRgba(colors.primary);
  const accent = hexToRgba(colors.accent);
  const textPrimary = hexToRgba(colors.textPrimary);
  const textSecondary = hexToRgba(colors.textSecondary);
  const cardBg = colors.cardBackground ? hexToRgba(colors.cardBackground) : surface;
  const navIcon = colors.navigationIcon ? hexToRgba(colors.navigationIcon) : textPrimary;
  const btnText = colors.buttonPrimaryText ? hexToRgba(colors.buttonPrimaryText) : { r: 255, g: 255, b: 255, a: 255 };

  // Fill background
  fillRect(png, 0, 0, W, H, bg.r, bg.g, bg.b, bg.a);

  // Toolbar bar
  fillRect(png, 0, 0, W, 18, surfaceVariant.r, surfaceVariant.g, surfaceVariant.b, surfaceVariant.a);

  // Toolbar dots
  const tl = lighten(textPrimary.r, textPrimary.g, textPrimary.b, 0.3);
  for (let i = 0; i < 3; i++) {
    drawCircle(png, 12 + i * 14, 9, 2.5, tl.r, tl.g, tl.b, 220);
  }

  // Sidebar card
  roundRect(png, 4, 22, 40, 94, 4, surface.r, surface.g, surface.b, surface.a);

  // Sidebar items
  for (let i = 0; i < 3; i++) {
    roundRect(png, 8, 28 + i * 24, 32, 16, 3, surfaceVariant.r, surfaceVariant.g, surfaceVariant.b, surfaceVariant.a);
  }

  // Main card area
  roundRect(png, 48, 22, 148, 60, 6, cardBg.r, cardBg.g, cardBg.b, cardBg.a);

  // Card accent line
  roundRect(png, 50, 24, 4, 56, 2, accent.r, accent.g, accent.b, accent.a);

  // Primary button
  roundRect(png, 60, 90, 60, 18, 4, primary.r, primary.g, primary.b, primary.a);
  // Button text
  const btnTextColor = btnText;
  const btnTextX = 60 + Math.floor(60 / 2) - Math.floor(5 * 4 / 2); // approx center for "APPLY"
  fillRect(png, btnTextX, 96, 20, 6, btnTextColor.r, btnTextColor.g, btnTextColor.b, btnTextColor.a);

  // Secondary button
  roundRect(png, 128, 90, 52, 18, 4, surface.r, surface.g, surface.b, surface.a);
  // Border around secondary button
  for (let y = 90; y < 108 && y < H; y++) {
    const borderY = (y === 90 || y === 107);
    for (let x = 128; x < 180 && x < W; x++) {
      const borderX = (x === 128 || x === 179);
      if (borderX || borderY) {
        if (y >= 90 && y < 108 && x >= 128 && x < 180) {
          // Check if in rounded corner area - skip for simplicity
          setPixel(png, x, y, textSecondary.r, textSecondary.g, textSecondary.b, 80);
        }
      }
    }
  }

  // Bottom navigation
  fillRect(png, 0, 114, W, 6, surfaceVariant.r, surfaceVariant.g, surfaceVariant.b, surfaceVariant.a);
  for (let i = 0; i < 4; i++) {
    drawCircle(png, 30 + i * 48, 117, 1.5, navIcon.r, navIcon.g, navIcon.b, navIcon.a);
  }

  const buffer = PNG.sync.write(png);
  const outPath = path.join(themeDir, 'preview.webp');
  fs.writeFileSync(outPath, buffer);
  console.log(`  ✓ ${path.basename(themeDir)}`);
}

const dirs = fs.readdirSync(THEMES_DIR).filter(d => {
  const themeJson = path.join(THEMES_DIR, d, 'theme.json');
  return fs.existsSync(themeJson);
});

console.log(`Generating previews for ${dirs.length} themes...\n`);

for (const dir of dirs) {
  const themeJson = path.join(THEMES_DIR, dir, 'theme.json');
  const data = JSON.parse(fs.readFileSync(themeJson, 'utf-8'));
  generatePreview(themeJson.replace('/theme.json', ''), data.colors);
}

console.log('\n✅ Done!');
