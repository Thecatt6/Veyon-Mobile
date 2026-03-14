import axios from 'axios';
import { Buffer } from 'buffer';

const VEYON_PORT = 11080;
const VEYON_COMPUTER_PORT = 11100;
const TIMEOUT = 5000;

function buildBaseURL(ip, port = VEYON_PORT) {
  return `http://${ip}:${port}`;
}

function buildAuthURL(ip, port = VEYON_PORT, computerPort = VEYON_COMPUTER_PORT) {
  return `${buildBaseURL(ip, port)}/api/v1/authentication/${ip}:${computerPort}`;
}

/**
 * Fetches available authentication methods for a Veyon server.
 * Returns { methods: [...] } or throws on error.
 */
async function getAuthMethods(ip, port = VEYON_PORT) {
  const url = buildAuthURL(ip, port);
  const res = await axios.get(url, { timeout: TIMEOUT, crossDomain: true });
  return res.data; // { methods: ['uuid1', ...] }
}

/**
 * Authenticates with a Veyon server using a private key.
 * Returns { connectionUid, validUntil } or throws on error.
 */
async function authenticate(authURL, keyName, privateKey) {
  console.log('Authenticating to:', authURL, 'keyName:', keyName, 'keyLen:', privateKey?.length);
  // Normalizza newline — Veyon richiede \n non \r\n
  const normalizedKey = privateKey.replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim();
  console.log('Key first 80 chars:', normalizedKey.substring(0, 80));
  console.log('Key last 40 chars:', normalizedKey.slice(-40));
  const body = {
    method: '0c69b301-81b4-42d6-8fae-128cdd113314', // Key File auth
    credentials: { keyname: keyName, keydata: normalizedKey },
  };
  try {
    const res = await axios.post(authURL, body, { timeout: TIMEOUT, crossDomain: true });
    console.log('Auth OK:', JSON.stringify(res.data));
    return {
      connectionUid: res.data['connection-uid'],
      validUntil: res.data.validUntil,
      authURL,
      authBody: body,
    };
  } catch (err) {
    console.warn('Auth FAILED:', err?.response?.status, JSON.stringify(err?.response?.data));
    throw err;
  }
}

/**
 * Fetches the logged-in username.
 * Tries /api/v1/session (Veyon 4.8+) first, then /api/v1/user (older).
 * Returns a display string or null.
 */
async function getSessionUser(baseURL, connectionUid) {
  const headers = { 'connection-uid': connectionUid };
  const opts = { headers, withCredentials: true, crossDomain: true, timeout: TIMEOUT };

  // Veyon 4.8+ endpoint
  try {
    const res = await axios.get(`${baseURL}/api/v1/session`, opts);
    if (res.data?.login) return res.data.login;
    if (res.data?.sessionClientName) return res.data.sessionClientName;
  } catch { /* fall through */ }

  // Legacy endpoint
  try {
    const res = await axios.get(`${baseURL}/api/v1/user`, opts);
    if (res.data?.session === -1) return null;
    if (res.data?.login) return res.data.login;
  } catch { /* fall through */ }

  return null;
}

/**
 * Fetches a single JPEG framebuffer from a Veyon server.
 * Returns a base64 data URI string or throws.
 */
async function getFrameBuffer(baseURL, connectionUid) {
  const url = `${baseURL}/api/v1/framebuffer`
    + `?t=${Date.now()}&format=jpeg&compression=8&quality=50`;

  const res = await axios.get(url, {
    headers: { 'connection-uid': connectionUid, Accept: 'image/jpeg' },
    responseType: 'arraybuffer',
    withCredentials: true,
    crossDomain: true,
    timeout: TIMEOUT,
  });

  return `data:image/jpeg;base64,${Buffer.from(res.data, 'binary').toString('base64')}`;
}

/**
 * Fetches the list of available features on a Veyon server.
 */
async function getFeatures(baseURL, connectionUid) {
  const res = await axios.get(`${baseURL}/api/v1/feature`, {
    headers: { 'connection-uid': connectionUid },
    withCredentials: true,
    crossDomain: true,
    timeout: TIMEOUT,
  });
  return res.data;
}

/**
 * Activates or deactivates a feature on a Veyon server.
 * Extra params go inside the 'arguments' key as per Veyon WebAPI spec.
 */
async function setFeature(baseURL, connectionUid, featureUid, active, args = {}) {
  const body = { active };
  if (Object.keys(args).length > 0) body.arguments = args;
  await axios.put(`${baseURL}/api/v1/feature/${featureUid}`, body, {
    headers: { 'connection-uid': connectionUid },
    withCredentials: true,
    crossDomain: true,
    timeout: TIMEOUT,
  });
}

export {
  buildBaseURL,
  buildAuthURL,
  getAuthMethods,
  authenticate,
  getSessionUser,
  getFrameBuffer,
  getFeatures,
  setFeature,
  VEYON_PORT,
  VEYON_COMPUTER_PORT,
};