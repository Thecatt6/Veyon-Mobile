import AsyncStorage from '@react-native-async-storage/async-storage';

const COMPUTERS_KEY = '@mc_computers';
const KEYS_KEY = '@mc_authkeys';
const SETTINGS_KEY = '@mc_settings';

// ─── Settings ─────────────────────────────────────────────────────────────────

const DEFAULT_SETTINGS = {
  renderer: 'skia',   // 'image' | 'skia' | 'native' | 'vnc'
  frameQuality: 50,
  frameCompression: 6,
  vncPort: 11100,
};

async function getSettings() {
  try {
    const raw = await AsyncStorage.getItem(SETTINGS_KEY);
    return raw ? { ...DEFAULT_SETTINGS, ...JSON.parse(raw) } : DEFAULT_SETTINGS;
  } catch {
    return DEFAULT_SETTINGS;
  }
}

async function saveSettings(settings) {
  const current = await getSettings();
  await AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify({ ...current, ...settings }));
}

// ─── Computers ────────────────────────────────────────────────────────────────

/**
 * Returns { id: { id, ip, port, authURL, label } }
 */
async function getComputers() {
  try {
    const raw = await AsyncStorage.getItem(COMPUTERS_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

async function saveComputer(computer) {
  // computer: { id, ip, port, authURL, label }
  const computers = await getComputers();
  computers[computer.id] = computer;
  await AsyncStorage.setItem(COMPUTERS_KEY, JSON.stringify(computers));
}

async function deleteComputer(id) {
  const computers = await getComputers();
  delete computers[id];
  await AsyncStorage.setItem(COMPUTERS_KEY, JSON.stringify(computers));
}

// ─── Auth Keys ────────────────────────────────────────────────────────────────

/**
 * Returns { id: { id, keyName, privateKey } }
 */
async function getAuthKeys() {
  try {
    const raw = await AsyncStorage.getItem(KEYS_KEY);
    return raw ? JSON.parse(raw) : {};
  } catch {
    return {};
  }
}

async function saveAuthKey(key) {
  // key: { id, keyName, privateKey }
  const keys = await getAuthKeys();
  keys[key.id] = key;
  await AsyncStorage.setItem(KEYS_KEY, JSON.stringify(keys));
}

async function deleteAuthKey(id) {
  const keys = await getAuthKeys();
  delete keys[id];
  await AsyncStorage.setItem(KEYS_KEY, JSON.stringify(keys));
}

export {
  getComputers,
  saveComputer,
  deleteComputer,
  getAuthKeys,
  saveAuthKey,
  deleteAuthKey,
};

// Re-export settings (already defined above)
export { getSettings, saveSettings };