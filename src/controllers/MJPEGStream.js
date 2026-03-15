import ReactNativeBlobUtil from 'react-native-blob-util';

/**
 * Unified FramePoller per Veyon.
 * Supporta 3 modalità di output:
 *   - 'image':  callback con data URI base64 (per React Native Image)
 *   - 'skia':   callback con SkiaImage (per @shopify/react-native-skia)
 *   - 'native': callback con data URI base64 (per NativeSurfaceView)
 */
export function startFramePoller(
  baseURL,
  initialConnectionUid,
  onFrame,
  onError,
  reAuthFn,
  options = {}
) {
  const {
    quality = 50,
    compression = 6,
    delay503 = 500,
    delayError = 1000,
    rendererMode = 'image', // 'image' | 'skia' | 'native'
  } = options;

  let cancelled = false;
  let currentConnectionUid = initialConnectionUid;
  let frameCount = 0;

  // Import Skia lazily solo se necessario
  let Skia = null;
  if (rendererMode === 'skia') {
    try {
      Skia = require('@shopify/react-native-skia').Skia;
    } catch (e) {
      console.warn('Skia not available, falling back to image mode');
    }
  }

  async function poll() {
    while (!cancelled) {
      try {
        if (!currentConnectionUid) {
          currentConnectionUid = await reAuthFn?.();
          if (!currentConnectionUid) {
            await new Promise(r => setTimeout(r, 2000));
            continue;
          }
        }

        const url = `${baseURL}/api/v1/framebuffer?format=jpeg&compression=${compression}&quality=[${quality}]&_t=${Date.now()}`;

        const response = await ReactNativeBlobUtil.fetch('GET', url, {
          'connection-uid': currentConnectionUid,
          'Accept': 'image/jpeg',
        });

        const status = response.info().status;

        if (status === 200) {
          frameCount++;
          if (frameCount <= 3) console.log(`Frame #${frameCount} [${rendererMode}]`);

          if (rendererMode === 'skia' && Skia) {
            // Skia: bytes diretti → GPU, nessuna base64
            try {
              const data = response.arrayBuffer();
              const bytes = new Uint8Array(data);
              if (bytes[0] === 0xFF && bytes[1] === 0xD8) {
                const skiaData = Skia.Data.fromBytes(bytes);
                const image = Skia.Image.MakeFromEncoded(skiaData);
                if (image && !cancelled) onFrame(image);
              }
            } catch (e) {
              // Fallback a base64 se Skia fallisce
              const b64 = response.base64();
              if (b64 && !cancelled) onFrame(`data:image/jpeg;base64,${b64}`);
            }
          } else {
            // Image / Native: base64
            const b64 = response.base64();
            if (b64 && !cancelled) onFrame(`data:image/jpeg;base64,${b64}`);
          }

        } else if (status === 503) {
          await new Promise(r => setTimeout(r, delay503));

        } else if (status === 401 || status === 403) {
          currentConnectionUid = null;
          await new Promise(r => setTimeout(r, 500));

        } else {
          console.warn(`Framebuffer HTTP ${status}`);
          await new Promise(r => setTimeout(r, delayError));
        }

      } catch (err) {
        if (!cancelled) {
          console.warn('Frame poll error:', err?.message);
          await new Promise(r => setTimeout(r, delayError));
        }
      }
    }
  }

  poll();
  return () => { cancelled = true; };
}