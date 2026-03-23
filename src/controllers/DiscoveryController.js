import { NativeModules } from 'react-native';
import { NetworkInfo } from 'react-native-network-info';

const { NetworkProbeModule } = NativeModules;
const VEYON_VNC_PORT = 11100;
const VEYON_API_PORT = 11080;
const PROBE_TIMEOUT_MS = 600;
const BATCH_SIZE = 40;

// ─── TCP probe via modulo nativo ──────────────────────────────────────────────

async function tcpProbe(host, port) {
  if (!NetworkProbeModule) {
    console.warn('NetworkProbeModule not available — rebuild required');
    return false;
  }
  try {
    return await NetworkProbeModule.tcpProbe(host, port, PROBE_TIMEOUT_MS);
  } catch {
    return false;
  }
}

// ─── Parser hostname range flessibile ────────────────────────────────────────

/**
 * Espande un pattern hostname in una lista di hostname.
 *
 * Formati supportati:
 *   INFO1-PC[01-23]     → INFO1-PC01 ... INFO1-PC23 (zero-padded)
 *   INFO1-PC[1-23]      → INFO1-PC1 ... INFO1-PC23
 *   INFO[1-4]-PCDOC     → INFO1-PCDOC ... INFO4-PCDOC
 *   INFO[1-4]-PC[01-10] → INFO1-PC01 ... INFO4-PC10 (prodotto cartesiano)
 *   INFO1-PC7           → [INFO1-PC7] (singolo host)
 *   192.168.1.[1-50]    → funziona anche con IP
 */
function expandHostnamePattern(pattern) {
  // Trova tutti i blocchi [N-M] nel pattern
  const bracketRegex = /\[(\d+)-(\d+)\]/g;
  const matches = [...pattern.matchAll(bracketRegex)];

  if (matches.length === 0) {
    // Nessun range — singolo host
    return [pattern];
  }

  // Genera il prodotto cartesiano di tutti i range
  let results = [pattern];
  for (const match of matches) {
    const [fullMatch, fromStr, toStr] = match;
    const from = parseInt(fromStr);
    const to = parseInt(toStr);
    const pad = fromStr.length; // zero-padding basato sulla lunghezza del numero iniziale

    const expanded = [];
    for (const current of results) {
      for (let i = from; i <= to; i++) {
        const num = pad > 1 ? String(i).padStart(pad, '0') : String(i);
        expanded.push(current.replace(fullMatch, num));
      }
    }
    results = expanded;
  }

  return results;
}

/**
 * Genera lista target da opzioni.
 *
 * options:
 *   mode: 'subnet' | 'hostname_range' | 'ip_range'
 *   subnet: '192.168.1'
 *   hostnamePattern: 'INFO1-PC[01-23]' o 'INFO[1-4]-PC[01-10]'
 *   ipFrom: '192.168.1.1', ipTo: '192.168.1.50'
 *   port: 11100
 */
function generateTargets(options = {}) {
  const port = options.port || VEYON_VNC_PORT;
  const targets = [];

  if (options.mode === 'hostname_range') {
    const pattern = options.hostnamePattern || '';
    const hosts = expandHostnamePattern(pattern);
    for (const host of hosts) {
      targets.push({ host, port });
    }

  } else if (options.mode === 'ip_range') {
    const fromParts = options.ipFrom.split('.').map(Number);
    const toParts = options.ipTo.split('.').map(Number);
    const prefix = fromParts.slice(0, 3).join('.');
    for (let i = fromParts[3]; i <= toParts[3]; i++) {
      targets.push({ host: `${prefix}.${i}`, port });
    }

  } else {
    // Subnet /24
    const subnet = options.subnet || '192.168.1';
    for (let i = 1; i <= 254; i++) {
      targets.push({ host: `${subnet}.${i}`, port });
    }
  }

  return targets;
}

// ─── Scanner principale ───────────────────────────────────────────────────────

function startDiscovery(options = {}, onFound, onProgress, onComplete) {
  let cancelled = false;

  (async () => {
    let targets;

    if (!options.mode || options.mode === 'subnet') {
      let subnet = options.subnet;
      if (!subnet) {
        try {
          const ip = await NetworkInfo.getIPV4Address();
          if (ip) {
            const parts = ip.split('.');
            subnet = `${parts[0]}.${parts[1]}.${parts[2]}`;
          }
        } catch { /* ignora */ }
        subnet = subnet || '192.168.1';
      }
      targets = generateTargets({ ...options, mode: 'subnet', subnet });
    } else {
      targets = generateTargets(options);
    }

    const total = targets.length;
    const found = [];
    let scanned = 0;

    for (let i = 0; i < total && !cancelled; i += BATCH_SIZE) {
      const batch = targets.slice(i, Math.min(i + BATCH_SIZE, total));

      await Promise.all(batch.map(async ({ host, port }) => {
        if (cancelled) return;
        const isOpen = await tcpProbe(host, port);
        scanned++;
        if (isOpen && !cancelled) {
          const result = { host, port, ip: host };
          found.push(result);
          onFound?.(result);
        }
        onProgress?.(Math.round((scanned / total) * 100));
      }));
    }

    if (!cancelled) onComplete?.(found);
  })();

  return () => { cancelled = true; };
}

export { startDiscovery, generateTargets, expandHostnamePattern, VEYON_VNC_PORT, VEYON_API_PORT };