import React, { useEffect, useRef, useState, useCallback } from 'react';
import { View, StyleSheet, Alert, Dimensions, ScrollView, Image } from 'react-native';
import { Text, useTheme, Appbar, Button, ActivityIndicator, Chip, Dialog, Portal, TextInput } from 'react-native-paper';
import { Canvas, Image as SkiaImage } from '@shopify/react-native-skia';
import { authenticate, getSessionUser, getFeatures, setFeature } from '../controllers/VeyonAPI';
import { getAuthKeys, getSettings } from '../controllers/StorageController';
import { startFramePoller } from '../controllers/MJPEGStream';
import NativeSurfaceView from '../components/NativeSurfaceView';
import VeyonVncView from '../components/VeyonVncView';

// ─── Feature metadata ──────────────────────────────────────────────────────────
const FEATURE_META = {
  'ScreenLock':                 { label: '🔒 Lock Screen',            toggle: true },
  'InputDevicesLock':           { label: '⌨️ Lock Input',              toggle: true },
  'RemoteView':                 { label: '👁️ Remote View',             toggle: true },
  'RemoteControl':              { label: '🖱️ Remote Control',          toggle: true },
  'FullScreenDemo':             { label: '📺 Demo (Fullscreen)',        toggle: true },
  'WindowDemo':                 { label: '🪟 Demo (Window)',            toggle: true },
  'ShareOwnScreenFullScreen':   { label: '🖥️ Share My Screen (Full)',  toggle: true },
  'ShareOwnScreenWindow':       { label: '🖥️ Share My Screen (Win)',   toggle: true },
  'ShareUserScreenFullScreen':  { label: '👁️ User Screen (Full)',      toggle: true },
  'ShareUserScreenWindow':      { label: '👁️ User Screen (Win)',       toggle: true },
  'MonitoringMode':             { label: '📡 Monitoring Mode',         toggle: true },
  'ClipboardExchange':          { label: '📋 Clipboard Sync',          toggle: true },
  'DemoServer':                 { label: '📺 Demo Server',             toggle: true },
  'SystemTrayIcon':             { label: '🔔 Tray Icon',               toggle: true },
  'Screenshot':                 { label: '📸 Screenshot' },
  'UserLogin':                  { label: '🔑 Login',                   inputKey: 'login' },
  'UserLogoff':                 { label: '🚪 Logoff',                  confirm: true },
  'PowerOn':                    { label: '⚡ Power On' },
  'Reboot':                     { label: '🔄 Reboot',                  confirm: true },
  'RebootNow':                  { label: '🔄 Reboot Now',              confirm: true },
  'PowerDown':                  { label: '⏻ Shutdown',                 confirm: true },
  'PowerDownNow':               { label: '⏻ Shutdown Now',             confirm: true },
  'InstallUpdatesAndPowerDown': { label: '🔄 Update & Shutdown',       confirm: true },
  'PowerDownConfirmed':         { label: '⏻ Shutdown (Confirmed)',     confirm: true },
  'PowerDownDelayed':           { label: '⏻ Shutdown (Delayed)',       confirm: true },
  'TextMessage':                { label: '💬 Send Message',            inputKey: 'text' },
  'OpenWebsite':                { label: '🌐 Open Website',            inputKey: 'url' },
  'StartApp':                   { label: '▶️ Start App',               inputKey: 'app' },
  'DistributeFiles':            { label: '📤 Distribute Files',        notSupported: 'Requires Veyon Master.' },
  'FileCollect':                { label: '📥 Collect Files',           notSupported: 'Requires Veyon Master.' },
  'Demo':                       { label: '📺 Demo' },
  'QueryApplicationVersion':    { label: 'ℹ️ App Version' },
  'QueryActiveFeatures':        { label: '🔍 Active Features' },
  'UserInfo':                   { label: '👤 User Info' },
  'SessionInfo':                { label: '🖥️ Session Info' },
  'QueryScreens':               { label: '🖥️ Query Screens' },
  'IdentifyUser':               { label: '🪪 Identify User' },
  'DesktopAccessDialog':        { label: '🔐 Desktop Access Dialog' },
  'AccessControlProvider':      { label: '🔐 Access Control' },
};

const INPUT_CONFIG = {
  text:  { title: '💬 Message to send',  placeholder: 'Type your message…', multiline: true },
  url:   { title: '🌐 Website URL',       placeholder: 'https://example.com',multiline: false },
  app:   { title: '▶️ App to start',      placeholder: 'notepad.exe',        multiline: false },
  login: { title: '🔑 Login credentials', placeholder: '',                   multiline: false },
};

function getMeta(feature) {
  return FEATURE_META[feature.name] || { label: feature.name || feature.uid.slice(0, 8) };
}

const RENDERER_LABELS = { image: '🖼️ Image', skia: '⚡ Skia', native: '🚀 Native' };

// ─── Component ────────────────────────────────────────────────────────────────
const ComputerDetailScreen = ({ route, navigation }) => {
  const theme = useTheme();
  const { computer } = route.params;

  const [status, setStatus] = useState('connecting');
  const [userLabel, setUserLabel] = useState('');
  const [fps, setFps] = useState(0);
  const [features, setFeatures] = useState([]);
  const [loadingFeature, setLoadingFeature] = useState(null);
  const [actionsVisible, setActionsVisible] = useState(false);
  const [inputDialog, setInputDialog] = useState(null);
  const [inputValue, setInputValue] = useState('');
  const [inputPassword, setInputPassword] = useState('');

  // ── Renderer state ───────────────────────────────────────────────────────────
  const [renderer, setRenderer] = useState('skia');
  const [frameQuality, setFrameQuality] = useState(50);
  const [frameCompression, setFrameCompression] = useState(6);
  const [vncPort, setVncPort] = useState(11100);

  // Frame state for each renderer
  const [imageFrame, setImageFrame] = useState(null);   // base64 URI
  const [skiaFrame, setSkiaFrame] = useState(null);     // SkiaImage object
  const [nativeFrame, setNativeFrame] = useState(null); // base64 URI for NativeSurfaceView

  const sessionRef = useRef(null);
  const mountedRef = useRef(true);
  const cancelStreamRef = useRef(null);
  const fpsCountRef = useRef(0);
  const fpsTimerRef = useRef(null);
  const lastFrameTimeRef = useRef(0);
  const FRAME_INTERVAL = 80; // ~12 FPS UI throttle

  const baseURL = `http://${computer.ip}:${computer.port}`;
  const { width: W, height: H } = Dimensions.get('window');
  const frameHeight = Math.round(H * 0.6);

  // ── Load settings on mount ────────────────────────────────────────────────
  useEffect(() => {
    getSettings().then((s) => {
      setRenderer(s.renderer || 'skia');
      setFrameQuality(s.frameQuality || 50);
      setFrameCompression(s.frameCompression || 6);
    });
  }, []);

  // ── FPS counter ───────────────────────────────────────────────────────────
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
    for (let i = 1; i <= retries; i++) {
      try {
        const data = await getFeatures(baseURL, targetUid);
        const list = Array.isArray(data) ? data
          : Array.isArray(data?.features) ? data.features
          : Array.isArray(data?.data) ? data.data : [];
        if (list.length > 0 && mountedRef.current) { setFeatures(list); return; }
      } catch (e) { console.log(`Features attempt ${i}:`, e?.message); }
      if (i < retries) await new Promise(r => setTimeout(r, 1000));
    }
  }, [baseURL]);

  // ── Start stream ──────────────────────────────────────────────────────────
  const startStream = useCallback((connectionUid, rendererMode, quality, compression) => {
    cancelStreamRef.current?.();
    cancelStreamRef.current = startFramePoller(
      baseURL, connectionUid,
      (frame) => {
        if (!mountedRef.current) return;
        // Throttle UI updates
        const now = Date.now();
        if (now - lastFrameTimeRef.current < FRAME_INTERVAL) return;
        lastFrameTimeRef.current = now;
        fpsCountRef.current += 1;

        if (rendererMode === 'skia' && typeof frame === 'object') {
          setSkiaFrame(frame);
        } else if (rendererMode === 'native') {
          setNativeFrame(frame);
        } else {
          setImageFrame({ uri: frame });
        }
      },
      (err) => console.warn('Frame error:', err),
      reAuth,
      { quality, compression, rendererMode },
    );
  }, [baseURL, reAuth]);

  // ── Init ──────────────────────────────────────────────────────────────────
  const initialize = useCallback(async () => {
    const [keys, settings] = await Promise.all([getAuthKeys(), getSettings()]);
    const keyList = Object.values(keys);
    const r = settings.renderer || 'skia';
    const q = settings.frameQuality || 50;
    const c = settings.frameCompression || 6;
    const vp = settings.vncPort || 11100;
    setRenderer(r); setFrameQuality(q); setFrameCompression(c); setVncPort(vp);

    if (keyList.length === 0) { setStatus('no-auth'); return; }

    // VNC mode: just authenticate to get session info, skip HTTP stream
    if (r === 'vnc') {
      for (const key of keyList) {
        try {
          const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
          if (!mountedRef.current) return;
          sessionRef.current = session;
          try {
            const user = await getSessionUser(baseURL, session.connectionUid);
            if (mountedRef.current) setUserLabel(user || '');
          } catch { /* non-blocking */ }
          if (mountedRef.current) setStatus('live');
          setTimeout(() => loadFeatures(session.connectionUid), 800);
          return;
        } catch (e) { console.log('Auth error:', e?.message); }
      }
      if (mountedRef.current) setStatus('error');
      return;
    }

    for (const key of keyList) {
      try {
        const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
        if (!mountedRef.current) return;
        sessionRef.current = session;
        try {
          const user = await getSessionUser(baseURL, session.connectionUid);
          if (mountedRef.current) setUserLabel(user || '');
        } catch { /* non-blocking */ }
        startStream(session.connectionUid, r, q, c);
        if (mountedRef.current) setStatus('live');
        setTimeout(() => loadFeatures(session.connectionUid), 800);
        return;
      } catch (e) { console.log('Auth error:', e?.message); }
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

  // ── Feature execution ─────────────────────────────────────────────────────
  const executeFeature = useCallback(async (feature, args = {}) => {
    console.log('Executing:', feature.name, 'args:', JSON.stringify(args));
    if (!sessionRef.current) return;
    const meta = getMeta(feature);
    const newActive = meta.toggle ? !feature.active : true;
    if (meta.toggle) {
      setFeatures(prev => prev.map(f => f.uid === feature.uid ? { ...f, active: newActive } : f));
    }
    setLoadingFeature(feature.uid);
    try {
      await setFeature(baseURL, sessionRef.current.connectionUid, feature.uid, newActive, args);
      await loadFeatures(sessionRef.current.connectionUid, 1);
    } catch (err) {
      if (meta.toggle) {
        setFeatures(prev => prev.map(f => f.uid === feature.uid ? { ...f, active: feature.active } : f));
      }
      Alert.alert('Error', err?.response?.data?.error?.message || err?.message || 'Unknown error');
    } finally { setLoadingFeature(null); }
  }, [baseURL, loadFeatures]);

  const handleFeaturePress = useCallback((feature) => {
    const meta = getMeta(feature);
    if (meta.notSupported) { Alert.alert('Not supported', meta.notSupported); return; }
    if (meta.inputKey) { setInputValue(''); setInputDialog({ feature, inputKey: meta.inputKey }); return; }
    if (meta.confirm) {
      Alert.alert(meta.label, `Send "${meta.label}" to ${computer.label || computer.ip}?`, [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Confirm', style: 'destructive', onPress: () => executeFeature(feature) },
      ]);
      return;
    }
    executeFeature(feature);
  }, [computer, executeFeature]);

  const handleInputConfirm = useCallback(() => {
    if (!inputDialog) return;
    const { feature, inputKey } = inputDialog;
    let args = {};

    if (inputKey === 'text') {
      if (!inputValue.trim()) return;
      args = { text: inputValue.trim() };

    } else if (inputKey === 'url') {
      if (!inputValue.trim()) return;
      let url = inputValue.trim();
      if (!url.startsWith('http://') && !url.startsWith('https://')) url = 'https://' + url;
      // Veyon expects 'url' inside arguments
      args = { url };

    } else if (inputKey === 'app') {
      if (!inputValue.trim()) return;
      // Veyon expects 'executable' inside arguments
      args = { executable: inputValue.trim() };

    } else if (inputKey === 'login') {
      if (!inputValue.trim()) return;
      // Veyon UserLogin expects username and password
      args = { username: inputValue.trim(), password: inputPassword };
    }

    setInputDialog(null);
    setInputValue('');
    setInputPassword('');
    executeFeature(feature, args);
  }, [inputDialog, inputValue, inputPassword, executeFeature]);

  const s = styles(theme);
  const hasFrame = imageFrame || skiaFrame || nativeFrame || renderer === 'vnc';

  // ── Render framebuffer based on renderer setting ────────────────────────────
  const renderFrame = () => {
    if (renderer === 'vnc') {
      const authBody = sessionRef.current?.authBody;
      return (
        <VeyonVncView
          host={computer.ip}
          port={vncPort}
          keyName={authBody?.credentials?.keyname || ''}
          privateKey={authBody?.credentials?.keydata || ''}
          style={{ width: W, height: frameHeight }}
          onConnected={() => setStatus('live')}
          onError={(e) => console.warn('VNC error:', e)}
        />
      );
    }
    if (renderer === 'skia' && skiaFrame) {
      return (
        <Canvas style={{ width: W, height: frameHeight }}>
          <SkiaImage image={skiaFrame} x={0} y={0} width={W} height={frameHeight} fit="contain" />
        </Canvas>
      );
    }
    if (renderer === 'native' && nativeFrame) {
      return (
        <NativeSurfaceView
          frameBase64={nativeFrame}
          style={{ width: W, height: frameHeight }}
        />
      );
    }
    if (imageFrame) {
      return (
        <Image source={imageFrame} style={{ width: W, height: frameHeight }} resizeMode="contain" fadeDuration={0} />
      );
    }
    return null;
  };

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
          <Chip compact style={s.fpsBadge} textStyle={s.fpsBadgeText}>
            {fps} FPS · {RENDERER_LABELS[renderer]}
          </Chip>
        )}
        {features.length > 0 && (
          <Appbar.Action icon="lightning-bolt" iconColor={theme.colors.primary} onPress={() => setActionsVisible(true)} />
        )}
      </Appbar.Header>

      {/* Framebuffer */}
      <View style={[s.frameContainer, { height: frameHeight }]}>
        {hasFrame ? renderFrame() : (
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

      <Portal>
        {/* Actions dialog */}
        <Dialog visible={actionsVisible} onDismiss={() => setActionsVisible(false)} style={{ backgroundColor: theme.colors.surface }}>
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
                      style={[s.actionButton, meta.notSupported && { opacity: 0.5 }]}
                      labelStyle={s.actionLabel}
                      loading={loadingFeature === feature.uid}
                      disabled={loadingFeature !== null}
                      onPress={() => { setActionsVisible(false); setTimeout(() => handleFeaturePress(feature), 150); }}
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
        <Dialog visible={inputDialog !== null} onDismiss={() => { setInputDialog(null); setInputValue(''); setInputPassword(''); }} style={{ backgroundColor: theme.colors.surface }}>
          <Dialog.Title style={{ color: theme.colors.onSurface }}>
            {inputDialog ? INPUT_CONFIG[inputDialog.inputKey]?.title : ''}
          </Dialog.Title>
          <Dialog.Content>
            {inputDialog?.inputKey === 'login' ? (
              <>
                <TextInput
                  mode="outlined"
                  label="Username"
                  value={inputValue}
                  onChangeText={setInputValue}
                  autoFocus
                  autoCapitalize="none"
                  style={{ backgroundColor: theme.colors.surfaceVariant, marginBottom: 12 }}
                />
                <TextInput
                  mode="outlined"
                  label="Password"
                  value={inputPassword}
                  onChangeText={setInputPassword}
                  secureTextEntry
                  style={{ backgroundColor: theme.colors.surfaceVariant }}
                />
              </>
            ) : (
              <TextInput
                mode="outlined"
                value={inputValue}
                onChangeText={setInputValue}
                placeholder={inputDialog ? INPUT_CONFIG[inputDialog.inputKey]?.placeholder : ''}
                autoFocus
                autoCapitalize="none"
                multiline={inputDialog?.inputKey === 'text'}
                numberOfLines={inputDialog?.inputKey === 'text' ? 4 : 1}
                style={{ backgroundColor: theme.colors.surfaceVariant }}
              />
            )}
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => { setInputDialog(null); setInputValue(''); setInputPassword(''); }}>Cancel</Button>
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
  fpsBadgeText: { fontSize: 10, color: theme.colors.secondary, fontWeight: '700' },
  frameContainer: { width: '100%', backgroundColor: '#000' },
  framePlaceholder: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },
  placeholderText: { color: theme.colors.onSurfaceVariant, fontSize: 13, textAlign: 'center' },
  actionsGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  actionButton: { marginBottom: 4 },
  actionLabel: { fontSize: 12 },
});

export default ComputerDetailScreen;