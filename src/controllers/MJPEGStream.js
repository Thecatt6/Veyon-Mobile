import ReactNativeBlobUtil from 'react-native-blob-util';

/**
 * Poller ottimizzato per Veyon (Endpoint: /api/v1/framebuffer)
 */
export function startFramePoller(baseURL, initialConnectionUid, onFrame, onError, reAuthFn, options = {}) {
  const {
    quality = 50,
    compression = 8,
    delay503 = 800,   // ms di attesa su 503 (server occupato)
    delayFrame = 0,   // ms tra un frame e il prossimo (0 = massima velocità)
    delayError = 1000,
  } = options;

  let pollerCancelled = false;
  let currentConnectionUid = initialConnectionUid;
  let frameCount = 0;

  async function poll() {
    while (!pollerCancelled) {
      try {
        // Re-auth se necessario
        if (!currentConnectionUid) {
          console.log('MJPEGStream: re-autenticazione...');
          currentConnectionUid = await reAuthFn?.();
          if (!currentConnectionUid) {
            await new Promise(r => setTimeout(r, 2000));
            continue;
          }
          console.log('MJPEGStream: re-auth OK');
        }

        const url = `${baseURL}/api/v1/framebuffer?format=jpeg&compression=${compression}&quality=[${quality}]&_t=${Date.now()}`;

        const response = await ReactNativeBlobUtil.fetch('GET', url, {
          'connection-uid': currentConnectionUid,  // lowercase — Veyon è case-sensitive
          'Accept': 'image/jpeg',
        });

        const status = response.info().status;

        if (status === 200) {
          const base64Str = response.base64();
          if (!pollerCancelled && base64Str) {
            frameCount++;
            if (frameCount <= 3) console.log(`Frame #${frameCount} ricevuto`);
            onFrame(`data:image/jpeg;base64,${base64Str}`);
          }
          // Piccola pausa per non saturare CPU/rete
          if (delayFrame > 0) await new Promise(r => setTimeout(r, delayFrame));

        } else if (status === 503) {
          // Server occupato — attendi e riprova silenziosamente
          await new Promise(r => setTimeout(r, delay503));

        } else if (status === 401 || status === 403) {
          // Sessione scaduta — forza re-auth al prossimo ciclo
          console.warn('MJPEGStream: sessione scaduta, re-auth al prossimo ciclo');
          currentConnectionUid = null;
          await new Promise(r => setTimeout(r, 500));

        } else {
          // Errore non recuperabile — logga ma continua
          console.warn(`MJPEGStream: HTTP ${status}`);
          await new Promise(r => setTimeout(r, delayError));
        }

      } catch (err) {
        if (!pollerCancelled) {
          console.warn('MJPEGStream fetch error:', err?.message);
          await new Promise(r => setTimeout(r, delayError));
        }
      }
    }
  }

  poll();

  return () => { pollerCancelled = true; };
}