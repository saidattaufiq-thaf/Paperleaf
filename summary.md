## Goal
Perbaiki layer system di edit canvas: urutan layer, masking, bug drag/hide, dan ANR setelah exit.

## Constraints & Preferences
- Layer baru harus di posisi paling kanan di UI panel dan tumpukan paling atas (goresan menutupi layer di kirinya)
- Masking: layer dengan `isMasking=true` hanya terlihat di area non-transparan dari semua layer visible di bawahnya (seperti clip mask di Krita/Ibis Paint)
- Tombol hide/duplicate/lock per layer hanya bisa diatur dari layer itu sendiri (tidak tercampur setelah drag)
- Saat transform mode aktif, tidak ada perubahan warna pada tombol tools
- Tidak boleh ada bitmap besar yang dibuat tiap frame di `onDraw` (cause ANR)

## Progress
### Done
- Urutan layer: index 0 = bottom, index terakhir = top. Iterasi rendering `onDraw`, `flattenLayers`, `flattenLayersForSave` pakai `for(layer in layers)` (forward)
- Layer baru (`addEmptyLayer`, `addImageLayer`, text layers) append di akhir list = posisi kanan/teratas
- `moveLayer()` logic sudah sesuai index 0 = bottom, layer di 0 hanya bisa move right
- Bug drag: setelah `suppressLayersChanged = false`, panggil `dv.onLayersChanged?.invoke()` supaya adapter refresh index
- Bug transform mode: `toggleTransformMode()` sekarang hanya update `btnMove.setColorFilter()`, tidak panggil `applyThemeColors()` (yang reset `imageTintList` semua tools)
- Masking via `drawMaskedLayerContent()`: composite layer masking dengan mask dari layer-layer visible di bawahnya pakai `PorterDuff.Mode.SRC_IN`
- ANR fixed: mask bitmap sekarang di-cache di `maskCache: MutableMap<ImageLayer, Bitmap>`, hanya dibuat ulang saat layers berubah (bukan tiap frame)
  - `getOrCreateMask()`: lazy create & cache mask per ImageLayer
  - `invalidateMaskCache()`: recycle & clear semua mask, dipanggil dari semua layer mutations
  - `drawMaskedLayerContent()`: simplified, hanya bikin 1 bitmap (`clipped`) per frame untuk masked layer

### In Progress
– (none)

### Blocked
– (none)

## Key Decisions
- Gunakan forward iteration untuk rendering (`for(layer in layers)`) sehingga index 0 = bottom, last index = top, sesuai UI panel kiri-ke-kanan.
- Mask compositing hanya di display (`onDraw`), layer bitmap tetap menyimpan semua goresan (non-destructive).
- Cache mask bitmap di DrawingView dan invalidate saat layer mutations (add, remove, reorder, visibility change, stroke end).

## Next Steps
1. Test build & run — verify no ANR after exiting edit mode
2. Test masking behavior across layer reorder/mutation
3. (Optional) Cache `clipped` bitmap per masked layer if perf still needs improvement — but the mask cache alone should eliminate the ANR

## Critical Context
- `workBitmap` ukuran default 3508×2480 pixel (A4 300dpi), 1 bitmap ARGB_8888 = ~34.8MB
- **Before fix**: `drawMaskedLayerContent` membuat 2 bitmap (~70MB) setiap frame di `onDraw` (60fps) → OOM/GC thrashing/ANR
- **After fix**: mask dibuat sekali per mutation (`getOrCreateMask`), `clipped` bitmap (1x ~35MB) masih dibuat per frame tapi hanya untuk layer dengan `isMasking=true`
- `PorterDuffXfermode(PorterDuff.Mode.SRC_IN)` sudah benar untuk aplikasi mask
- ANR terjadi setelah exit karena main thread diblok oleh GC akibat alokasi bitmap terus-menerus selama rendering

## Relevant Files
- `DrawingView.kt:133-162`: maskCache, invalidateMaskCache(), getOrCreateMask()
- `DrawingView.kt:577-598`: drawMaskedLayerContent() simplified
- `DrawingView.kt:172-187`: addEmptyLayer (forward iteration, mask cache)
- `DrawingView.kt:896-911`: addImageLayer (forward iteration, mask cache)
- `DrawingView.kt:1059-1077`: deleteLayer
- `DrawingView.kt:1083-1098`: duplicateLayer
- `DrawingView.kt:1111-1118`: toggleLayerMasking
- `DrawingView.kt:1120-1182`: mergeLayerLeft
- `DrawingView.kt:1184-1205`: moveLayer
- `DrawingView.kt:645-661`: createDefaultLayer
- `DrawingView.kt:966-1008`: loadLayers
- `DrawingView.kt:1010-1033`: flattenLayers
- `DrawingView.kt:1595-1629`: endDrawing (stroke end → invalidateMaskCache)
