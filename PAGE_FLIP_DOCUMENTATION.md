# Premium Page Flip Animation - WeTransfer Paper Style

## Overview
Paperleaf sekarang dilengkapi dengan animasi flip halaman premium yang terinspirasi dari WeTransfer Paper. Sistem ini menggunakan OpenGL ES 3.0 dengan fitur-fitur canggih untuk memberikan pengalaman membaca yang realistis dan smooth.

## Fitur Utama

### 🎨 Visual Effects
- **Curl Deformation**: Simulasi lengkungan kertas yang realistis menggunakan mesh deformation
- **Dynamic Shadows**: Bayangan dinamis yang berubah sesuai posisi halaman
- **Lighting & Shading**: Pencahayaan real-time dengan ambient, diffuse, dan specular components
- **Paper Texture**: Support untuk tekstur kertas dengan noise texture untuk efek realistis
- **Translucency Effect**: Efek tembus pandang seperti kertas asli saat dibalik

### ⚡ Performance
- **60/120 FPS Support**: Animasi smooth dengan adaptive frame rate
- **GPU Accelerated**: Semua perhitungan fisika dan rendering dilakukan di GPU
- **Dynamic Quality**: Kualitas otomatis menyesuaikan dengan kemampuan device
- **Efficient Memory Management**: Texture pooling dan cleanup otomatis

### 👆 Gesture Controls
- **Swipe**: Usap kiri/kanan untuk flip halaman dengan velocity-based animation
- **Pan**: Tahan dan tarik untuk melihat detail curl halaman
- **Tap**: Ketuk untuk flip cepat ke halaman berikutnya
- **Spring Physics**: Animasi berbasis fisika dengan spring dynamics

## Arsitektur Sistem

```
PremiumPageFlipView (Main View)
├── MeshGenerator (Curl deformation)
├── PagePhysics (Spring physics simulation)
├── ShadowRenderer (Dynamic shadows)
├── LightingRenderer (PBR lighting)
├── GestureController (Touch input handling)
├── AnimationController (Spring animations)
├── ShaderManager (OpenGL shader management)
└── TextureManager (Texture loading & pooling)
```

## Cara Menggunakan

### 1. Setup di Layout XML

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.paperleaf.sketchbook.pageflip.premium.PremiumPageFlipView
        android:id="@+id/pageFlipView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

### 2. Setup di Activity

```kotlin
class ReaderActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityReaderBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup listener untuk event flip
        setupPageFlipListener()
        
        // Load halaman pertama
        loadInitialPages()
    }
    
    private fun setupPageFlipListener() {
        // Track progress flip (0.0 - 1.0)
        binding.pageFlipView.onFlipProgress = { progress ->
            Log.d("Reader", "Flip progress: $progress")
        }
        
        // Handle saat flip selesai
        binding.pageFlipView.onFlipComplete = { isForward ->
            if (isForward) {
                loadNextPage()
            } else {
                loadPreviousPage()
            }
        }
    }
    
    private fun loadInitialPages() {
        lifecycleScope.launch {
            val frontPage = loadImageFromAssets("pages/page_001.jpg")
            val backPage = loadImageFromAssets("pages/page_002.jpg")
            
            if (frontPage != null && backPage != null) {
                binding.pageFlipView.setPages(frontPage, backPage)
            }
        }
    }
    
    private fun loadNextPage() {
        lifecycleScope.launch {
            val nextPage = loadImageFromAssets("pages/page_003.jpg")
            nextPage?.let {
                binding.pageFlipView.setPageTextures(listOf(it))
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        binding.pageFlipView.onPause()
    }
    
    override fun onResume() {
        super.onResume()
        binding.pageFlipView.onResume()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        binding.pageFlipView.cleanup()
    }
}
```

### 3. Customization

#### Mengatur Ketebalan Halaman
```kotlin
binding.pageFlipView.setPageThickness(0.03f) // Range: 0.005 - 0.05
```

#### Mode Menggambar (Disable Flip)
```kotlin
// Enable/disable drawing mode (flip disabled saat drawing)
binding.pageFlipView.setDrawingMode(true)
```

#### Manual Flip Progress
```kotlin
// Set progress flip secara manual (untuk slider control)
binding.pageFlipView.setFlipProgress(0.5f) // 50% flipped

// Animate flip dengan target dan velocity
binding.pageFlipView.animateFlip(
    target = 1.0f,           // Flip forward complete
    velocity = 0.5f          // Initial velocity
)
```

## Struktur Folder Assets

```
app/src/main/assets/
└── pages/
    ├── page_001.jpg
    ├── page_002.jpg
    ├── page_003.jpg
    └── ...
```

## Best Practices

### 1. Image Optimization
- Gunakan ukuran maksimal 2048x2048 untuk texture
- Format PNG atau JPG dengan kompresi optimal
- Pre-load halaman berikutnya saat user sedang membaca

### 2. Memory Management
```kotlin
override fun onDestroy() {
    super.onDestroy()
    // Cleanup textures dan resources
    binding.pageFlipView.cleanup()
    
    // Recycle bitmaps yang tidak digunakan
    pageImages.forEach { it.recycle() }
    pageImages.clear()
}
```

### 3. Lifecycle Handling
```kotlin
override fun onPause() {
    super.onPause()
    binding.pageFlipView.onPause() // Pause rendering
}

override fun onResume() {
    super.onResume()
    binding.pageFlipView.onResume() // Resume rendering
}
```

## Troubleshooting

### Masalah: Animasi Tidak Smooth
**Solusi**: 
- Kurangi resolusi texture halaman
- Pastikan device support OpenGL ES 3.0
- Check frame profiler untuk bottleneck

### Masalah: Texture Tidak Muncul
**Solusi**:
- Pastikan bitmap loaded sebelum setPages dipanggil
- Check logcat untuk error loading texture
- Verifikasi path file assets benar

### Masalah: Crash saat Flip
**Solusi**:
- Pastikan cleanup dipanggil di onDestroy
- Jangan akses view setelah activity destroyed
- Handle exception saat load bitmap

## API Reference

### PremiumPageFlipView Methods

| Method | Description |
|--------|-------------|
| `setPages(front: Bitmap, back: Bitmap)` | Set halaman depan dan belakang |
| `setPageTextures(textures: List<Bitmap>)` | Update texture halaman |
| `setBackTextures(textures: List<Bitmap>)` | Set texture bagian belakang |
| `setFlipProgress(progress: Float)` | Set progress flip manual (0.0-1.0) |
| `animateFlip(target: Float, velocity: Float)` | Animasi flip dengan spring physics |
| `setPageThickness(thickness: Float)` | Atur ketebalan halaman |
| `setDrawingMode(drawing: Boolean)` | Enable/disable drawing mode |
| `onPause()` | Pause rendering engine |
| `onResume()` | Resume rendering engine |
| `cleanup()` | Cleanup semua resources |

### Callbacks

| Callback | Parameter | Description |
|----------|-----------|-------------|
| `onFlipProgress` | `progress: Float` | Dipanggil saat flip berlangsung (0.0-1.0) |
| `onFlipComplete` | `isForward: Boolean` | Dipanggil saat flip selesai |

## Credits

Dikembangkan oleh Paperleaf Team dengan inspirasi dari WeTransfer Paper app.

## License

Copyright © 2024 Paperleaf. All rights reserved.
