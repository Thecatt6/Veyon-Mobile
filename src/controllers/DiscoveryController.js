import axios from 'axios';
import { NetworkInfo } from 'react-native-network-info';
import { VEYON_PORT, VEYON_COMPUTER_PORT, buildAuthURL } from './VeyonAPI';

const PROBE_TIMEOUT = 700;
const BATCH_SIZE = 30;

function ipToInt(ip) {
  return ip.split('.').reduce((acc, oct) => (acc << 8) + parseInt(oct, 10), 0) >>> 0;
}

function intToIp(int) {
  return [
    (int >>> 24) & 255,
    (int >>> 16) & 255,
    (int >>> 8) & 255,
    int & 255,
  ].join('.');
}

async function probeHost(ip) {
  try {
    const url = buildAuthURL(ip);
    const res = await axios.get(url, { timeout: PROBE_TIMEOUT, crossDomain: true });
    if (res.data?.methods?.length > 0) {
      return { ip, port: VEYON_PORT, authURL: url, methods: res.data.methods };
    }
  } catch { /* unreachable */ }
  return null;
}

/**
 * Scans an IP range for Veyon servers.
 * @param {string} startIP  - e.g. "192.168.1.1"
 * @param {string} endIP    - e.g. "192.168.1.254"
 * @param {function} onFound
 * @param {function} onProgress  - (0-100)
 * @param {function} onComplete
 * @returns {function} cancel
 */
function startDiscovery(startIP, endIP, onFound, onProgress, onComplete) {
  let cancelled = false;

  (async () => {
    const startInt = ipToInt(startIP);
    const endInt = ipToInt(endIP);
    const total = endInt - startInt + 1;
    const found = [];
    let done = 0;

    for (let base = startInt; base <= endInt && !cancelled; base += BATCH_SIZE) {
      const batchEnd = Math.min(base + BATCH_SIZE - 1, endInt);
      const batch = [];

      for (let i = base; i <= batchEnd; i++) {
        batch.push(
          probeHost(intToIp(i)).then((result) => {
            if (result && !cancelled) {
              found.push(result);
              onFound?.(result);
            }
            done++;
          }),
        );
      }

      await Promise.all(batch);
      onProgress?.(Math.round((done / total) * 100));
    }

    if (!cancelled) onComplete?.(found);
  })();

  return () => { cancelled = true; };
}

/**
 * Detects the local subnet and returns a suggested IP range.
 * e.g. { startIP: "192.168.1.1", endIP: "192.168.1.254" }
 */
async function getDefaultRange() {
  try {
    const ip = await NetworkInfo.getIPV4Address();
    if (!ip) return { startIP: '192.168.1.1', endIP: '192.168.1.254' };
    const parts = ip.split('.');
    return {
      startIP: `${parts[0]}.${parts[1]}.${parts[2]}.1`,
      endIP: `${parts[0]}.${parts[1]}.${parts[2]}.254`,
    };
  } catch {
    return { startIP: '192.168.1.1', endIP: '192.168.1.254' };
  }
}

export { startDiscovery, getDefaultRange };