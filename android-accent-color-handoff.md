# Coda accent-color extraction: Android handoff

Status: production behavior on macOS as of 2026-07-23  
Algorithm version: 1  
macOS source of truth: `Sources/CodaPlaybackSpike/ArtworkTreatment.swift`,
`AlbumArtworkLoader.extractAccent(from:)`

## Goal

Port Coda macOS's artwork-derived highlight color to Android without redesigning or
"improving" it. The current behavior has been visually validated across the complete
2,095-album Navidrome library and should be preserved.

The algorithm is Coda-specific. AppKit currently supplies only image decoding,
32×32 resizing, RGB pixel access, and RGB-to-HSV conversion.

## Important non-goals

- Do not add a perceptual-luminance floor.
- Do not boost dark blue or red results.
- Do not change hue-bucket scoring.
- Do not use Android Palette as a replacement.
- Do not extract embedded track artwork. Coda uses the Navidrome album cover.
- Do not require byte-identical results after independently decoding and resizing a
  JPEG/WebP on macOS and Android. Small platform differences are expected.

The 2026-07-23 library audit found one unusually dark result, Ecliptica. It was
intentionally accepted rather than changing the tone of the selected color.

## Exact algorithm

### 1. Normalize the input image

Render the complete album cover into a 32×32, 8-bit-per-channel sRGB/RGBA bitmap.

The cover is normally square. Coda macOS fills the complete 32×32 destination. It
does not crop a center region or apply artwork-aware framing.

### 2. Create 18 hue buckets

Each bucket stores:

- `score`
- weighted red sum
- weighted green sum
- weighted blue sum
- accumulated weight

All values begin at zero.

### 3. Inspect all 1,024 pixels

Convert each 8-bit RGB pixel to normalized RGB components in `[0, 1]`.

Calculate HSV-style value and saturation:

```text
maximum    = max(red, green, blue)
minimum    = min(red, green, blue)
value      = maximum
saturation = maximum == 0 ? 0 : (maximum - minimum) / maximum
```

Discard the pixel unless all three strict conditions are true:

```text
value > 0.12
value < 0.94
saturation > 0.16
```

The strict greater-than/less-than comparisons are intentional.

Calculate hue in `[0, 1)` and assign the pixel to:

```text
bucket = min(Int(hue * 18), 17)
```

Calculate its score:

```text
middleBrightness = 1 - abs(value - 0.58)
weight = saturation² * (0.45 + middleBrightness)
```

Accumulate:

```text
bucket.score  += weight
bucket.red    += red   * weight
bucket.green  += green * weight
bucket.blue   += blue  * weight
bucket.weight += weight
```

### 4. Select and average the winning bucket

Choose the bucket with the largest `score`.

If no accepted pixel exists, return the monochrome fallback:

```text
red   = 0.56
green = 0.58
blue  = 0.60
hex   = #8F9499
```

Otherwise calculate its weighted RGB average:

```text
red   = bucket.red   / bucket.weight
green = bucket.green / bucket.weight
blue  = bucket.blue  / bucket.weight
```

### 5. Apply the existing HSV-value floor

This is an RGB-channel lift, not a perceptual-brightness correction:

```text
lift = max(0, 0.50 - max(red, green, blue))

red   = min(red   + lift, 1)
green = min(green + lift, 1)
blue  = min(blue  + lift, 1)
```

Return that color without further adjustment.

### Load/decode fallback

When artwork is missing or cannot be decoded, the UI-level generic fallback is:

```text
red   = 0.20
green = 0.72
blue  = 0.76
hex   = #33B8C2
```

This is distinct from the neutral fallback for a successfully decoded monochrome
cover.

## Kotlin reference implementation

This version explicitly renders into an sRGB bitmap on Android 8/API 26 or newer.
If Coda Android supports an older API, retain the same extraction core and add a
compatible sRGB rendering path.

```kotlin
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CodaAccentColor(
    val red: Double,
    val green: Double,
    val blue: Double,
)

object CodaAccentExtractor {
    const val ALGORITHM_VERSION = 1

    private const val SAMPLE_SIZE = 32
    private const val BUCKET_COUNT = 18
    private const val MIN_VALUE = 0.12
    private const val MAX_VALUE = 0.94
    private const val MIN_SATURATION = 0.16
    private const val PREFERRED_VALUE = 0.58
    private const val WEIGHT_BASE = 0.45
    private const val OUTPUT_VALUE_FLOOR = 0.50

    val GENERIC_FALLBACK =
        CodaAccentColor(red = 0.20, green = 0.72, blue = 0.76)

    val MONOCHROME_FALLBACK =
        CodaAccentColor(red = 0.56, green = 0.58, blue = 0.60)

    private data class Bucket(
        var score: Double = 0.0,
        var red: Double = 0.0,
        var green: Double = 0.0,
        var blue: Double = 0.0,
        var weight: Double = 0.0,
    )

    fun extractOrFallback(source: Bitmap?): CodaAccentColor {
        if (source == null) return GENERIC_FALLBACK
        return runCatching {
            val sample = renderToSrgb32(source)
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            sample.getPixels(
                pixels,
                0,
                SAMPLE_SIZE,
                0,
                0,
                SAMPLE_SIZE,
                SAMPLE_SIZE,
            )
            sample.recycle()
            extractFromArgbPixels(pixels)
        }.getOrDefault(GENERIC_FALLBACK)
    }

    /**
     * Keep this function independent of Bitmap decoding so it can be tested with
     * exact, synthetic 32×32 pixel buffers.
     */
    internal fun extractFromArgbPixels(pixels: IntArray): CodaAccentColor {
        require(pixels.size == SAMPLE_SIZE * SAMPLE_SIZE)

        val buckets = Array(BUCKET_COUNT) { Bucket() }
        val hsv = FloatArray(3)

        for (pixel in pixels) {
            val red8 = Color.red(pixel)
            val green8 = Color.green(pixel)
            val blue8 = Color.blue(pixel)

            val red = red8 / 255.0
            val green = green8 / 255.0
            val blue = blue8 / 255.0

            val maximum = max(red, max(green, blue))
            val minimum = min(red, min(green, blue))
            val value = maximum
            val saturation =
                if (maximum == 0.0) 0.0 else (maximum - minimum) / maximum

            if (
                value <= MIN_VALUE ||
                value >= MAX_VALUE ||
                saturation <= MIN_SATURATION
            ) {
                continue
            }

            Color.RGBToHSV(red8, green8, blue8, hsv)
            val hue = hsv[0].toDouble() / 360.0
            val bucketIndex =
                min((hue * BUCKET_COUNT).toInt(), BUCKET_COUNT - 1)

            val middleBrightness = 1.0 - abs(value - PREFERRED_VALUE)
            val weight =
                saturation * saturation * (WEIGHT_BASE + middleBrightness)

            buckets[bucketIndex].apply {
                score += weight
                this.red += red * weight
                this.green += green * weight
                this.blue += blue * weight
                this.weight += weight
            }
        }

        val winner = buckets.maxByOrNull { it.score }
        if (winner == null || winner.weight <= 0.0) {
            return MONOCHROME_FALLBACK
        }

        val red = winner.red / winner.weight
        val green = winner.green / winner.weight
        val blue = winner.blue / winner.weight
        val lift = max(0.0, OUTPUT_VALUE_FLOOR - max(red, max(green, blue)))

        return CodaAccentColor(
            red = min(red + lift, 1.0),
            green = min(green + lift, 1.0),
            blue = min(blue + lift, 1.0),
        )
    }

    private fun renderToSrgb32(source: Bitmap): Bitmap {
        val target = Bitmap.createBitmap(
            SAMPLE_SIZE,
            SAMPLE_SIZE,
            Bitmap.Config.ARGB_8888,
            true,
            ColorSpace.get(ColorSpace.Named.SRGB),
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        Canvas(target).drawBitmap(
            source,
            null,
            Rect(0, 0, SAMPLE_SIZE, SAMPLE_SIZE),
            paint,
        )
        return target
    }
}
```

## Verification strategy

Verification must distinguish scoring differences from decoder/resizer differences.

### Layer 1: exact extraction-core tests

Call `extractFromArgbPixels()` with programmatically generated arrays containing
exactly 1,024 pixels.

Required tests:

1. **All black**
   - Every pixel is rejected by the value threshold.
   - Expected result: monochrome fallback `#8F9499`.

2. **All neutral gray**
   - Every pixel is rejected by the saturation threshold.
   - Expected result: monochrome fallback `#8F9499`.

3. **Solid red at RGB `#800000`**
   - Pixel is accepted.
   - Expected result: `#800000`, allowing only floating-point rounding error.

4. **Solid blue at RGB `#000080`**
   - Pixel is accepted.
   - Expected result: `#000080`.

5. **Values bracketing the lower threshold**
   - With a saturated color, `30 / 255 = 0.1176` must be rejected.
   - `31 / 255 = 0.1216` must be accepted.

6. **Values bracketing the upper threshold**
   - With a saturated color, `239 / 255 = 0.9373` must be accepted.
   - `240 / 255 = 0.9412` must be rejected.

7. **Values bracketing the saturation threshold**
   - At maximum component 128, a channel range of 20 produces saturation
     `20 / 128 = 0.15625` and must be rejected.
   - A channel range of 21 produces saturation `21 / 128 = 0.16406` and must
     be accepted.

8. **Two competing hue buckets**
   - Construct known pixel counts/saturations.
   - Verify that the bucket with the greater accumulated score wins.

9. **Output lift**
   - Use an accepted dark saturated color whose maximum component is below `0.50`.
   - Verify that the same lift is added to all three components until the maximum
     component is exactly `0.50`.

The extraction-core tests should match the Swift implementation to within `1e-6`
per normalized RGB component. Inputs are already identical 8-bit pixels, so platform
image decoding is not involved.

### Layer 2: real-cover pipeline tests

Use the same encoded JPEG/WebP album-cover files on both platforms:

1. Decode the image.
2. Render to 32×32 sRGB.
3. Run the extraction core.
4. Compare the final colors.

Recommended acceptance:

- Ideal: each 8-bit RGB component differs by no more than 2.
- Acceptable decoder/resizer variance: each component differs by no more than 5.
- Any larger or systematic difference requires inspection of color-space conversion,
  alpha handling, or scaling.

Do not compare screenshots. Display profiles, HDR, window compositing, and the
artwork-derived background make screenshots unsuitable as algorithm fixtures.

Do not commit copyrighted album covers as public test fixtures. Real-cover comparison
can run locally against the user's Navidrome library. Public repository tests should
use generated synthetic fixtures or redistributable artwork.

### Layer 3: complete local-library audit

For a full audit:

1. Page through `getAlbumList2` with:
   - `type=alphabeticalByName`
   - `size=500`
   - increasing `offset`
2. For each album, use its `coverArt` ID, falling back to the album ID.
3. Fetch one `getCoverArt` image at `size=500`.
4. Extract the accent.
5. Record:
   - album and artist
   - RGB/hex output
   - HSV value
   - WCAG relative luminance
   - optional OKLab lightness
6. Sort by relative luminance and inspect the darkest results.

Relative luminance is diagnostic only and is not part of production extraction:

```text
linear(c) =
    c / 12.92                         when c <= 0.04045
    ((c + 0.055) / 1.055) ^ 2.4      otherwise

Y =
    0.2126 * linear(red) +
    0.7152 * linear(green) +
    0.0722 * linear(blue)
```

## macOS reference results

Full-library audit on 2026-07-23:

- Albums analyzed: 2,095
- Minimum relative luminance: `0.0313`
- 1st percentile: `0.0538`
- 5th percentile: `0.0689`
- 10th percentile: `0.0814`
- Median: `0.1566`

Dark-result counts:

| Relative luminance | Albums | Share |
|---|---:|---:|
| `< 0.04` | 2 | 0.1% |
| `< 0.05` | 9 | 0.4% |
| `< 0.06` | 51 | 2.4% |
| `< 0.07` | 116 | 5.5% |
| `< 0.08` | 194 | 9.3% |
| `< 0.10` | 379 | 18.1% |

Named visual references:

| Album | Artist | Accent | Relative luminance | OKLab L |
|---|---|---:|---:|---:|
| Ecliptica | Sonata Arctica | `#132780` | `0.0313` | `0.326` |
| Ride the Lightning | Metallica | `#1B3580` | `0.0437` | `0.359` |
| Space 1992: Rise of the Chaos Wizards | Gloryhammer | `#5C15AB` | `0.0572` | `0.412` |

Ecliptica was the darkest result in the complete library. The result can be difficult
to read on a dim display, but is acceptable on brighter calibrated displays. A
post-extraction luminance floor was tested conceptually and rejected because even a
small neutral RGB lift visibly changes the tone. Preserve the current output.

## Integration notes

- Cache the accent by stable Navidrome album/cover-art identity. Do not recalculate it
  during recomposition.
- Perform decode, resize, and extraction off the UI thread.
- Publish only the final color back to Compose state.
- Keep the algorithm constants in one object and give the behavior an explicit version.
- If either platform changes the algorithm, update both implementations and repeat all
  three verification layers.
- Background generation is separate from accent extraction. Port and test it
  independently rather than mixing its transformations into this function.
