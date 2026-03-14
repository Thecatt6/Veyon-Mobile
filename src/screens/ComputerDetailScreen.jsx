import React, { useEffect, useRef, useState, useCallback } from 'react';
import { View, StyleSheet, Alert, Dimensions, ScrollView, Image } from 'react-native';
import { Text, useTheme, Appbar, Button, ActivityIndicator, Divider, Chip, Dialog, Portal, TextInput } from 'react-native-paper';
import { authenticate, getSessionUser, getFeatures, setFeature } from '../controllers/VeyonAPI';
import { getAuthKeys } from '../controllers/StorageController';
import { startFramePoller } from '../controllers/MJPEGStream';

// ─── Feature metadata ──────────────────────────────────────────────────────────
// toggle: true  → ha stato on/off, il pulsante è filled quando attivo
// inputKey: richiede un dialogo di input prima di eseguire
// confirm: richiede conferma per azioni distruttive
// notSupported: funzione non supportabile via WebAPI (mostra avviso)
const FEATURE_META = {
  'ScreenLock':                 { label: '🔒 Lock Screen',           toggle: true },
  'InputDevicesLock':           { label: '⌨️ Lock Input',             toggle: true },
  'RemoteView':                 { label: '👁️ Remote View',            toggle: true },
  'RemoteControl':              { label: '🖱️ Remote Control',         toggle: true },
  'FullScreenDemo':             { label: '📺 Demo (Fullscreen)',       toggle: true },
  'WindowDemo':                 { label: '🪟 Demo (Window)',           toggle: true },
  'ShareOwnScreenFullScreen':   { label: '🖥️ Share My Screen (Full)', toggle: true },
  'ShareOwnScreenWindow':       { label: '🖥️ Share My Screen (Win)',  toggle: true },
  'ShareUserScreenFullScreen':  { label: '👁️ User Screen (Full)',     toggle: true },
  'ShareUserScreenWindow':      { label: '👁️ User Screen (Win)',      toggle: true },
  'MonitoringMode':             { label: '📡 Monitoring Mode',        toggle: true },
  'ClipboardExchange':          { label: '📋 Clipboard Sync',         toggle: true },
  'Screenshot':                 { label: '📸 Screenshot' },
  'UserLogin':                  { label: '🔑 Login' },
  'UserLogoff':                 { label: '🚪 Logoff',                 confirm: true },
  'PowerOn':                    { label: '⚡ Power On' },
  'Reboot':                     { label: '🔄 Reboot',                 confirm: true },
  'RebootNow':                  { label: '🔄 Reboot Now',             confirm: true },
  'PowerDown':                  { label: '⏻ Shutdown',                confirm: true },
  'PowerDownNow':               { label: '⏻ Shutdown Now',            confirm: true },
  'InstallUpdatesAndPowerDown': { label: '🔄 Update & Shutdown',      confirm: true },
  'PowerDownConfirmed':         { label: '⏻ Shutdown (Confirmed)',    confirm: true },
  'PowerDownDelayed':           { label: '⏻ Shutdown (Delayed)',      confirm: true },
  'TextMessage':                { label: '💬 Send Message',           inputKey: 'text' },
  'OpenWebsite':                { label: '🌐 Open Website',           inputKey: 'url' },
  'StartApp':                   { label: '▶️ Start App',              inputKey: 'app' },
  'DistributeFiles':            { label: '📤 Distribute Files',       notSupported: 'File distribution requires Veyon Master.' },
  'FileCollect':                { label: '📥 Collect Files',          notSupported: 'File collection requires Veyon Master.' },
  'DemoServer':                 { label: '📺 Demo Server',            toggle: true },
  'Demo':                       { label: '📺 Demo' },
  'SystemTrayIcon':             { label: '🔔 Tray Icon',              toggle: true },
  'QueryApplicationVersion':    { label: 'ℹ️ App Version' },
  'QueryActiveFeatures':        { label: '🔍 Active Features' },
  'UserInfo':                   { label: '👤 User Info' },
  'SessionInfo':                { label: '🖥️ Session Info' },
  'QueryScreens':               { label: '🖥️ Query Screens' },
  'IdentifyUser':               { label: '🪪 Identify User' },
  'DesktopAccessDialog':        { label: '🔐 Desktop Access Dialog' },
  'AccessControlProvider':      { label: '🔐 Access Control' },
};

function getMeta(feature) {
  return FEATURE_META[feature.name] || { label: feature.name || feature.uid.slice(0, 8) };
}

// ─── Input dialog config ──────────────────────────────────────────────────────
const INPUT_CONFIG = {
  text: { title: '💬 Message to send',   placeholder: 'Type your message…',  multiline: true },
  url:  { title: '🌐 Website URL',        placeholder: 'https://example.com', multiline: false },
  app:  { title: '▶️ App to start',       placeholder: 'notepad.exe',         multiline: false },
};

// ─── Component ────────────────────────────────────────────────────────────────
const ComputerDetailScreen = ({ route, navigation }) => {
  const theme = useTheme();
  const { computer } = route.params;

  const [status, setStatus] = useState('connecting');
  const [userLabel, setUserLabel] = useState('');
  const [frameSource, setFrameSource] = useState(null);
  const [fps, setFps] = useState(0);
  const [features, setFeatures] = useState([]);
  const [loadingFeature, setLoadingFeature] = useState(null);
  const [actionsVisible, setActionsVisible] = useState(false);
  const [inputDialog, setInputDialog] = useState(null); // { feature, inputKey }
  const [inputValue, setInputValue] = useState('');

  const lastFrameTimeRef = useRef(0);
  const FRAME_INTERVAL = 100; // ms → max 10 FPS on UI thread
  const mountedRef = useRef(true);
  const cancelStreamRef = useRef(null);
  const fpsCountRef = useRef(0);
  const fpsTimerRef = useRef(null);

  const baseURL = `http://${computer.ip}:${computer.port}`;
  const { width: screenWidth, height: screenHeight } = Dimensions.get('window');
  const frameHeight = Math.round(screenHeight * 0.6);

  // ── FPS ──────────────────────────────────────────────────────────────────
  const startFPSCounter = useCallback(() => {
    fpsTimerRef.current = setInterval(() => {
      if (mountedRef.current) { setFps(fpsCountRef.current); fpsCountRef.current = 0; }
    }, 1000);
  }, []);

  // ── Re-auth ───────────────────────────────────────────────────────────────
  const reAuth = useCallback(async () => {
    const keys = await getAuthKeys();
    for (const key of Object.values(keys)) {
      try {
        const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
        if (!mountedRef.current) return null;
        sessionRef.current = session;
        return session.connectionUid;
      } catch { /* try next */ }
    }
    return null;
  }, [computer]);

  // ── Load features ─────────────────────────────────────────────────────────
  const loadFeatures = useCallback(async (uid, retries = 3) => {
    const targetUid = uid || sessionRef.current?.connectionUid;
    if (!targetUid) return;
    for (let attempt = 1; attempt <= retries; attempt++) {
      try {
        const data = await getFeatures(baseURL, targetUid);
        const list = Array.isArray(data) ? data
          : Array.isArray(data?.features) ? data.features
          : Array.isArray(data?.data) ? data.data : [];
        if (list.length > 0) {
          if (mountedRef.current) setFeatures(list); // show ALL features
          return;
        }
      } catch (err) {
        console.log(`Features error (attempt ${attempt}):`, err?.message);
      }
      if (attempt < retries) await new Promise(r => setTimeout(r, 1000));
    }
  }, [baseURL]);

  // ── Stream ────────────────────────────────────────────────────────────────
  const startStream = useCallback((connectionUid) => {
    cancelStreamRef.current?.();
    cancelStreamRef.current = startFramePoller(
      baseURL, connectionUid,
      (dataURI) => {
        if (!mountedRef.current) return;
        const now = Date.now();
        if (now - lastFrameTimeRef.current < FRAME_INTERVAL) return;
        lastFrameTimeRef.current = now;
        setFrameSource({ uri: dataURI });
        fpsCountRef.current += 1;
      },
      (err) => console.warn('Frame error:', err),
      reAuth,
      { quality: 50, compression: 8 },
    );
  }, [baseURL, reAuth]);

  // ── Init ──────────────────────────────────────────────────────────────────
  const initialize = useCallback(async () => {
    const keys = await getAuthKeys();
    const keyList = Object.values(keys);
    if (keyList.length === 0) { setStatus('no-auth'); return; }
    for (const key of keyList) {
      try {
        const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
        if (!mountedRef.current) return;
        sessionRef.current = session;
        try {
          const user = await getSessionUser(baseURL, session.connectionUid);
          if (mountedRef.current) setUserLabel(user || '');
        } catch { /* non-blocking */ }
        startStream(session.connectionUid);
        if (mountedRef.current) setStatus('live');
        setTimeout(() => loadFeatures(session.connectionUid), 800);
        return;
      } catch (err) { console.log('Auth error:', err?.message); }
    }
    if (mountedRef.current) setStatus('error');
  }, [computer, baseURL, loadFeatures, startStream]);

  useEffect(() => {
    mountedRef.current = true;
    startFPSCounter();
    initialize();
    return () => {
      mountedRef.current = false;
      cancelStreamRef.current?.();
      clearInterval(fpsTimerRef.current);
    };
  }, []);

  // ── Execute feature ────────────────────────────────────────────────────────
  const executeFeature = useCallback(async (feature, args = {}) => {
    if (!sessionRef.current) return;
    const meta = getMeta(feature);
    const newActive = meta.toggle ? !feature.active : true;

    // Optimistic UI update for toggles — don't wait for reload
    if (meta.toggle) {
      setFeatures(prev => prev.map(f =>
        f.uid === feature.uid ? { ...f, active: newActive } : f
      ));
    }

    setLoadingFeature(feature.uid);
    try {
      await setFeature(baseURL, sessionRef.current.connectionUid, feature.uid, newActive, args);
      // Reload to get actual state from server
      await loadFeatures(sessionRef.current.connectionUid, 1);
    } catch (err) {
      // Revert optimistic update on error
      if (meta.toggle) {
        setFeatures(prev => prev.map(f =>
          f.uid === feature.uid ? { ...f, active: feature.active } : f
        ));
      }
      Alert.alert('Error', err?.response?.data?.error?.message || err?.message || 'Unknown error');
    } finally {
      setLoadingFeature(null);
    }
  }, [baseURL, loadFeatures]);

  // ── Handle feature press ───────────────────────────────────────────────────
  const handleFeaturePress = useCallback((feature) => {
    const meta = getMeta(feature);

    if (meta.notSupported) {
      Alert.alert('Not supported via API', meta.notSupported);
      return;
    }

    if (meta.inputKey) {
      setInputValue('');
      setInputDialog({ feature, inputKey: meta.inputKey });
      return;
    }

    if (meta.confirm) {
      Alert.alert(
        meta.label,
        `Send "${meta.label}" to ${computer.label || computer.ip}?`,
        [
          { text: 'Cancel', style: 'cancel' },
          { text: 'Confirm', style: 'destructive', onPress: () => executeFeature(feature) },
        ]
      );
      return;
    }

    executeFeature(feature);
  }, [computer, executeFeature]);

  // ── Confirm input dialog ───────────────────────────────────────────────────
  const handleInputConfirm = useCallback(() => {
    if (!inputDialog || !inputValue.trim()) return;
    const { feature, inputKey } = inputDialog;

    // Build arguments according to Veyon WebAPI spec
    let args = {};
    if (inputKey === 'text') {
      args = { text: inputValue.trim() };
    } else if (inputKey === 'url') {
      let url = inputValue.trim();
      if (!url.startsWith('http://') && !url.startsWith('https://')) url = 'https://' + url;
      args = { url };
    } else if (inputKey === 'app') {
      args = { executable: inputValue.trim() };
    }

    setInputDialog(null);
    executeFeature(feature, args);
  }, [inputDialog, inputValue, executeFeature]);

  const s = styles(theme);

  return (
    <View style={s.container}>
      <Appbar.Header style={s.appbar}>
        <Appbar.BackAction onPress={() => navigation.goBack()} iconColor={theme.colors.onSurface} />
        <Appbar.Content
          title={computer.label || computer.ip}
          subtitle={userLabel || undefined}
          titleStyle={s.appbarTitle}
          subtitleStyle={s.appbarSubtitle}
        />
        {status === 'live' && (
          <Chip compact style={s.fpsBadge} textStyle={s.fpsBadgeText}>{fps} FPS</Chip>
        )}
        {features.length > 0 && (
          <Appbar.Action
            icon="lightning-bolt"
            iconColor={theme.colors.primary}
            onPress={() => setActionsVisible(true)}
          />
        )}
        {status === 'live' && features.length === 0 && (
          <Appbar.Action
            icon="loading"
            iconColor={theme.colors.onSurfaceVariant}
            onPress={() => loadFeatures()}
          />
        )}
      </Appbar.Header>

      {/* Framebuffer */}
      <View style={[s.frameContainer, { height: frameHeight }]}>
        {frameSource ? (
          <Image source={frameSource} style={s.frame} resizeMode="cover" fadeDuration={0} />
        ) : (
          <View style={s.framePlaceholder}>
            {status === 'connecting' || status === 'live' ? (
              <>
                <ActivityIndicator color={theme.colors.primary} size="large" />
                <Text style={s.placeholderText}>
                  {status === 'connecting' ? 'Authenticating…' : 'Waiting for frame…'}
                </Text>
              </>
            ) : (
              <Text style={[s.placeholderText, { color: theme.colors.error }]}>
                {status === 'no-auth' ? '🔑 No auth key configured' : '✕ Connection failed'}
              </Text>
            )}
          </View>
        )}
      </View>

      <Divider style={{ backgroundColor: theme.colors.outline }} />

      <Portal>
        {/* Actions dialog */}
        <Dialog
          visible={actionsVisible}
          onDismiss={() => setActionsVisible(false)}
          style={{ backgroundColor: theme.colors.surface }}
        >
          <Dialog.Title style={{ color: theme.colors.onSurface }}>
            Actions — {computer.label || computer.ip}
          </Dialog.Title>
          <Dialog.ScrollArea style={{ maxHeight: 440 }}>
            <ScrollView contentContainerStyle={{ padding: 8 }}>
              <View style={s.actionsGrid}>
                {features.map((feature) => {
                  const meta = getMeta(feature);
                  const isActive = meta.toggle && feature.active;
                  return (
                    <Button
                      key={feature.uid}
                      mode={isActive ? 'contained' : 'outlined'}
                      style={[s.actionButton, meta.notSupported && s.actionDisabled]}
                      labelStyle={s.actionLabel}
                      loading={loadingFeature === feature.uid}
                      disabled={loadingFeature !== null}
                      onPress={() => {
                        setActionsVisible(false);
                        setTimeout(() => handleFeaturePress(feature), 150);
                      }}
                      compact
                    >
                      {meta.label}
                    </Button>
                  );
                })}
              </View>
            </ScrollView>
          </Dialog.ScrollArea>
          <Dialog.Actions>
            <Button onPress={() => setActionsVisible(false)}>Close</Button>
          </Dialog.Actions>
        </Dialog>

        {/* Input dialog */}
        <Dialog
          visible={inputDialog !== null}
          onDismiss={() => setInputDialog(null)}
          style={{ backgroundColor: theme.colors.surface }}
        >
          <Dialog.Title style={{ color: theme.colors.onSurface }}>
            {inputDialog ? INPUT_CONFIG[inputDialog.inputKey].title : ''}
          </Dialog.Title>
          <Dialog.Content>
            <TextInput
              mode="outlined"
              value={inputValue}
              onChangeText={setInputValue}
              placeholder={inputDialog ? INPUT_CONFIG[inputDialog.inputKey].placeholder : ''}
              autoFocus
              multiline={inputDialog ? INPUT_CONFIG[inputDialog.inputKey].multiline : false}
              numberOfLines={inputDialog?.inputKey === 'text' ? 4 : 1}
              style={{ backgroundColor: theme.colors.surfaceVariant }}
            />
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setInputDialog(null)}>Cancel</Button>
            <Button
              mode="contained"
              onPress={handleInputConfirm}
              disabled={!inputValue.trim()}
            >
              Send
            </Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </View>
  );
};

const styles = (theme) => StyleSheet.create({
  container: { flex: 1, backgroundColor: theme.colors.background },
  appbar: { backgroundColor: theme.colors.surface, elevation: 0, borderBottomWidth: 1, borderBottomColor: theme.colors.outline },
  appbarTitle: { fontSize: 16, fontWeight: '700', color: theme.colors.onSurface },
  appbarSubtitle: { fontSize: 12, color: theme.colors.onSurfaceVariant },
  fpsBadge: { backgroundColor: theme.colors.surfaceVariant, marginRight: 4 },
  fpsBadgeText: { fontSize: 11, color: theme.colors.secondary, fontWeight: '700' },
  frameContainer: { width: '100%', backgroundColor: '#000' },
  frame: { width: '100%', height: '100%' },
  framePlaceholder: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },
  placeholderText: { color: theme.colors.onSurfaceVariant, fontSize: 13, textAlign: 'center' },
  actionsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  actionButton: { marginBottom: 4 },
  actionDisabled: { opacity: 0.5 },
  actionLabel: { fontSize: 12 },
});

export default ComputerDetailScreen;