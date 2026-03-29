import React, { useEffect, useRef, useState, useCallback } from 'react';
import { View, StyleSheet, Alert, Dimensions, ScrollView, Image, NativeModules } from 'react-native';
import { Text, useTheme, Appbar, Button, ActivityIndicator, Chip, Dialog, Portal, TextInput } from 'react-native-paper';
import { Canvas, Image as SkiaImage } from '@shopify/react-native-skia';
import { authenticate, getSessionUser, getFeatures, setFeature } from '../controllers/VeyonAPI';
import { getAuthKeys, getSettings } from '../controllers/StorageController';
import { startFramePoller } from '../controllers/MJPEGStream';
import NativeSurfaceView from '../components/NativeSurfaceView';
import VeyonVncView from '../components/VeyonVncView';

const { VeyonVncModule } = NativeModules;

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

// Feature UUID map per VNC (name → uid)
const FEATURE_UIDS = {
  'ScreenLock':                 'ccb535a2-1d24-4cc1-a709-8b47d2b2ac79',
  'InputDevicesLock':           'e4a77879-e544-4fec-bc18-e534f33b934c',
  'RemoteView':                 'a18e545b-1321-4d4e-ac34-adc421c6e9c8',
  'RemoteControl':              'ca00ad68-1709-4abe-85e2-48dff6ccf8a2',
  'FullScreenDemo':             '7b6231bd-eb89-45d3-af32-f70663b2f878',
  'WindowDemo':                 'ae45c3db-dc2e-4204-ae8b-374cdab8c62c',
  'ShareOwnScreenFullScreen':   '07b375e1-8ab6-4b48-bcb7-75fb3d56035b',
  'ShareOwnScreenWindow':       '68c55fb9-127e-4c9f-9c90-28b998bf1a47',
  'ShareUserScreenFullScreen':  'b4e542e2-1deb-48ac-910a-bbf8ac9a0bde',
  'ShareUserScreenWindow':      'ebfc5ec4-f725-4bfc-a93a-c6d4864c6806',
  'MonitoringMode':             'edad8259-b4ef-4ca5-90e6-f238d0fda694',
  'ClipboardExchange':          '8fa73e19-3d66-4d59-9783-c2a1bb07e20e',
  'UserLogin':                  '7310707d-3918-460d-a949-65bd152cb958',
  'UserLogoff':                 '7311d43d-ab53-439e-a03a-8cb25f7ed526',
  'Reboot':                     '4f7d98f0-395a-4fff-b968-e49b8d0f748c',
  'PowerDown':                  '6f5a27a0-0e2f-496e-afcc-7aae62eede10',
  'PowerDownNow':               'a88039f2-6716-40d8-b4e1-9f5cd48e91ed',
  'InstallUpdatesAndPowerDown': '09bcb3a1-fc11-4d03-8cf1-efd26be8655b',
  'PowerDownConfirmed':         'ea2406be-d5c7-42b8-9f04-53469d3cc34c',
  'PowerDownDelayed':           '352de795-7fc4-4850-bc57-525bcb7033f5',
  'TextMessage':                'e75ae9c8-ac17-4d00-8f0d-019348346208',
  'StartApp':                   'da9ca56a-b2ad-4fff-8f8a-929b2927b442',
  'OpenWebsite':                '8a11a75d-b3db-48b6-b9cb-f8422ddd5b0c',
  'Screenshot':                 'd5ee3aac-2a87-4d05-b827-0c20344490bd',
};

// Features predefinite per VNC (quando WebAPI non è disponibile)
const VNC_FEATURES = Object.keys(FEATURE_UIDS).map(name => ({
  name,
  uid: FEATURE_UIDS[name],
  active: false,
}));

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

  const [renderer, setRenderer] = useState('image');
  const [connectionMethod, setConnectionMethod] = useState('webapi');
  const [featureMethod, setFeatureMethod] = useState('webapi');
  const [frameQuality, setFrameQuality] = useState(50);
  const [frameCompression, setFrameCompression] = useState(8);
  const [vncPort, setVncPort] = useState(11100);

  const [imageFrame, setImageFrame] = useState(null);
  const [skiaFrame, setSkiaFrame] = useState(null);
  const [nativeFrame, setNativeFrame] = useState(null);
  const [vncAuthKey, setVncAuthKey] = useState(null); // { keyName, privateKey }

  const sessionRef = useRef(null);
  const mountedRef = useRef(true);
  const cancelStreamRef = useRef(null);
  const fpsCountRef = useRef(0);
  const fpsTimerRef = useRef(null);
  const lastFrameTimeRef = useRef(0);
  const FRAME_INTERVAL = 80;

  const baseURL = `http://${computer.ip}:${computer.port}`;
  const { width: W, height: H } = Dimensions.get('window');
  const frameHeight = Math.round(H * 0.6);

  useEffect(() => {
    getSettings().then((s) => {
      setRenderer(s.renderer || 'image');
      setConnectionMethod(s.connectionMethod || 'webapi');
      setFeatureMethod(s.featureMethod || 'webapi');
      setFrameQuality(s.frameQuality || 50);
      setFrameCompression(s.frameCompression || 8);
      setVncPort(s.vncPort || 11100);
    });
  }, []);

  const startFPSCounter = useCallback(() => {
    fpsTimerRef.current = setInterval(() => {
      if (mountedRef.current) { setFps(fpsCountRef.current); fpsCountRef.current = 0; }
    }, 1000);
  }, []);

  const reAuth = useCallback(async () => {
    const keys = await getAuthKeys();
    for (const key of Object.values(keys)) {
      try {
        const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
        if (!mountedRef.current) return null;
        sessionRef.current = session;
        return session.connectionUid;
      } catch { }
    }
    return null;
  }, [computer]);

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

  const startStream = useCallback((connectionUid, rendererMode, quality, compression) => {
    cancelStreamRef.current?.();
    cancelStreamRef.current = startFramePoller(
      baseURL, connectionUid,
      (frame) => {
        if (!mountedRef.current) return;
        const now = Date.now();
        if (now - lastFrameTimeRef.current < FRAME_INTERVAL) return;
        lastFrameTimeRef.current = now;
        fpsCountRef.current += 1;
        if (rendererMode === 'skia' && typeof frame === 'object') setSkiaFrame(frame);
        else if (rendererMode === 'native') setNativeFrame(frame);
        else setImageFrame({ uri: frame });
      },
      (err) => console.warn('Frame error:', err),
      reAuth,
      { quality, compression, rendererMode },
    );
  }, [baseURL, reAuth]);

  const initialize = useCallback(async () => {
    const [keys, s] = await Promise.all([getAuthKeys(), getSettings()]);
    const keyList = Object.values(keys);
    const r = s.renderer || 'image';
    const q = s.frameQuality || 50;
    const c = s.frameCompression || 8;
    const vp = s.vncPort || 11100;
    const method = s.connectionMethod || 'webapi';
    const fm = s.featureMethod || 'webapi';

    setRenderer(r);
    setFrameQuality(q);
    setFrameCompression(c);
    setVncPort(vp);
    setConnectionMethod(method);
    setFeatureMethod(fm);

    if (keyList.length === 0) { setStatus('no-auth'); return; }

    // VNC mode
    if (method === 'vnc') {
      const k = keyList[0];
      setVncAuthKey({ keyName: k.keyName, privateKey: k.privateKey });

      // Carica features: prima prova WebAPI, poi fallback a lista statica VNC
      let featuresLoaded = false;
      for (const key of keyList) {
        try {
          const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
          if (!mountedRef.current) return;
          sessionRef.current = session;
          await loadFeatures(session.connectionUid);
          featuresLoaded = true;
          break;
        } catch (e) {
          console.log('WebAPI auth (optional for features):', e?.message);
        }
      }
      // Se WebAPI non disponibile, usa lista statica per VNC features
      if (!featuresLoaded && mountedRef.current) {
        setFeatures(VNC_FEATURES);
      }
      if (mountedRef.current) setStatus('live');
      return;
    }

    // WebAPI mode
    for (const key of keyList) {
      try {
        const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
        if (!mountedRef.current) return;
        sessionRef.current = session;
        try {
          const user = await getSessionUser(baseURL, session.connectionUid);
          if (mountedRef.current) setUserLabel(user || '');
        } catch { }
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

  // ── Esegui feature ───────────────────────────────────────────────────────────
  // Converte args con chiavi JS (text, url, executable...) in chiavi numeriche Veyon ("0", "1"...)
  // Veyon usa QVariantMap con chiavi stringa degli indici: {"0": valore, "1": valore2}
  const toVncArgs = (args) => {
    const map = {
      text:       { '0': args.text },
      url:        { '0': args.url },
      executable: { '0': args.executable },
      username:   { '0': args.username, '1': args.password || '' },
    };
    // Trova la prima chiave riconosciuta
    for (const [key, mapped] of Object.entries(map)) {
      if (args[key] !== undefined) return mapped;
    }
    // Fallback: converti le chiavi in indici numerici
    return Object.fromEntries(Object.values(args).map((v, i) => [String(i), String(v)]));
  };

  const executeFeature = useCallback(async (feature, args = {}) => {
    const meta = getMeta(feature);
    const newActive = meta.toggle ? !feature.active : true;

    if (meta.toggle) {
      setFeatures(prev => prev.map(f => f.uid === feature.uid ? { ...f, active: newActive } : f));
    }
    setLoadingFeature(feature.uid);

    try {
      const method = featureMethod || connectionMethod;

      if (method === 'vnc') {
        const uid = feature.uid || FEATURE_UIDS[feature.name];
        if (!uid) throw new Error(`Unknown feature UID for: ${feature.name}`);
        // Converti args in formato Veyon (chiavi numeriche)
        const vncArgs = Object.keys(args).length > 0 ? toVncArgs(args) : {};
        await VeyonVncModule.sendFeature(uid, newActive, vncArgs);

      } else {
        if (!sessionRef.current) throw new Error('No WebAPI session');
        await setFeature(baseURL, sessionRef.current.connectionUid, feature.uid, newActive, args);
        await loadFeatures(sessionRef.current.connectionUid, 1);
      }
    } catch (err) {
      if (meta.toggle) {
        setFeatures(prev => prev.map(f => f.uid === feature.uid ? { ...f, active: feature.active } : f));
      }
      Alert.alert('Error', err?.response?.data?.error?.message || err?.message || 'Unknown error');
    } finally {
      setLoadingFeature(null);
    }
  }, [baseURL, loadFeatures, connectionMethod, featureMethod]);

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
      args = { url };
    } else if (inputKey === 'app') {
      if (!inputValue.trim()) return;
      args = { executable: inputValue.trim() };
    } else if (inputKey === 'login') {
      if (!inputValue.trim()) return;
      args = { username: inputValue.trim(), password: inputPassword };
    }
    setInputDialog(null);
    setInputValue('');
    setInputPassword('');
    executeFeature(feature, args);
  }, [inputDialog, inputValue, inputPassword, executeFeature]);

  const s = styles(theme);
  const hasFrame = imageFrame || skiaFrame || nativeFrame || connectionMethod === 'vnc';

  const renderFrame = () => {
    if (connectionMethod === 'vnc') {
      if (!vncAuthKey) {
        return (
          <View style={[s.framePlaceholder, { width: W, height: frameHeight, backgroundColor: '#000' }]}>
            <ActivityIndicator color={theme.colors.primary} size="large" />
            <Text style={s.placeholderText}>Loading key…</Text>
          </View>
        );
      }
      return (
        <VeyonVncView
          host={computer.ip}
          port={vncPort}
          keyName={vncAuthKey.keyName}
          privateKey={vncAuthKey.privateKey}
          style={{ width: W, height: frameHeight }}
          onConnected={() => { if (mountedRef.current) setStatus('live'); }}
          onFpsUpdate={(f) => { if (mountedRef.current) setFps(f); }}
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
      return <NativeSurfaceView frameBase64={nativeFrame} style={{ width: W, height: frameHeight }} />;
    }
    if (imageFrame) {
      return <Image source={imageFrame} style={{ width: W, height: frameHeight }} resizeMode="contain" fadeDuration={0} />;
    }
    return null;
  };

  const canShowActions = features.length > 0;

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
        <Appbar.Action
          icon="lightning-bolt"
          iconColor={canShowActions ? theme.colors.primary : theme.colors.onSurfaceVariant}
          onPress={() => setActionsVisible(true)}
          disabled={!canShowActions}
        />
      </Appbar.Header>

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
        <Dialog visible={actionsVisible} onDismiss={() => setActionsVisible(false)} style={{ backgroundColor: theme.colors.surface }}>
          <Dialog.Title style={{ color: theme.colors.onSurface }}>
            Actions — {computer.label || computer.ip}
            {featureMethod === 'vnc' ? ' (VNC)' : ' (WebAPI)'}
          </Dialog.Title>
          <Dialog.ScrollArea style={{ maxHeight: 440 }}>
            <ScrollView contentContainerStyle={{ padding: 8 }}>
              <View style={s.actionsGrid}>
                {features.map((feature) => {
                  const meta = getMeta(feature);
                  const isActive = meta.toggle && feature.active;
                  return (
                    <Button
                      key={feature.uid || feature.name}
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

        <Dialog visible={inputDialog !== null} onDismiss={() => { setInputDialog(null); setInputValue(''); setInputPassword(''); }} style={{ backgroundColor: theme.colors.surface }}>
          <Dialog.Title style={{ color: theme.colors.onSurface }}>
            {inputDialog ? INPUT_CONFIG[inputDialog.inputKey]?.title : ''}
          </Dialog.Title>
          <Dialog.Content>
            {inputDialog?.inputKey === 'login' ? (
              <>
                <TextInput mode="outlined" label="Username" value={inputValue} onChangeText={setInputValue}
                  autoFocus autoCapitalize="none" style={{ backgroundColor: theme.colors.surfaceVariant, marginBottom: 12 }} />
                <TextInput mode="outlined" label="Password" value={inputPassword} onChangeText={setInputPassword}
                  secureTextEntry style={{ backgroundColor: theme.colors.surfaceVariant }} />
              </>
            ) : (
              <TextInput
                mode="outlined" value={inputValue} onChangeText={setInputValue} autoFocus autoCapitalize="none"
                placeholder={inputDialog ? INPUT_CONFIG[inputDialog.inputKey]?.placeholder : ''}
                multiline={inputDialog?.inputKey === 'text'} numberOfLines={inputDialog?.inputKey === 'text' ? 4 : 1}
                style={{ backgroundColor: theme.colors.surfaceVariant }}
              />
            )}
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => { setInputDialog(null); setInputValue(''); setInputPassword(''); }}>Cancel</Button>
            <Button mode="contained" onPress={handleInputConfirm} disabled={!inputValue.trim()}>Send</Button>
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