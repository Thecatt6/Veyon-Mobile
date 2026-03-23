import React, { useEffect, useRef, useState, useCallback } from 'react';
import { View, StyleSheet, NativeModules, NativeEventEmitter } from 'react-native';
import { Text, useTheme, ActivityIndicator, Chip } from 'react-native-paper';
import NativeSurfaceView from './NativeSurfaceView';

const { VeyonVncModule } = NativeModules;
const emitter = VeyonVncModule ? new NativeEventEmitter(VeyonVncModule) : null;

/**
 * VeyonVncView — native VNC viewer for Veyon
 *
 * Props:
 *   host (string)        — IP address
 *   port (number)        — default 11100
 *   keyName (string)     — key name (e.g. "Test")
 *   privateKey (string)  — PEM private key content
 *   style (object)       — container style
 *   onConnected (fn)     — called when connected
 *   onDisconnected (fn)  — called when disconnected
 *   onError (fn)         — called on error
 */
const VeyonVncView = ({
  host, port = 11100, keyName, privateKey,
  style, onConnected, onDisconnected, onError,
}) => {
  const theme = useTheme();
  const [status, setStatus] = useState('connecting');
  const [fps, setFps] = useState(0);
  const [resolution, setResolution] = useState('');
  const surfaceReady = useRef(false);
  const connected = useRef(false);
  const onConnectedRef = useRef(onConnected);
  const onDisconnectedRef = useRef(onDisconnected);
  const onErrorRef = useRef(onError);

  useEffect(() => { onConnectedRef.current = onConnected; }, [onConnected]);
  useEffect(() => { onDisconnectedRef.current = onDisconnected; }, [onDisconnected]);
  useEffect(() => { onErrorRef.current = onError; }, [onError]);

  const doConnect = useCallback(() => {
    if (!surfaceReady.current || !host || !keyName || !privateKey) {
      console.log('VNC doConnect skipped — not ready:', { surfaceReady: surfaceReady.current, host, hasKey: !!keyName, hasPriv: !!privateKey });
      return;
    }
    if (connected.current) return;
    connected.current = true;
    console.log('VNC connecting to', host, ':', port, 'keyName:', keyName);
    setStatus('connecting');
    VeyonVncModule.connect(host, port, keyName, privateKey)
      .then((res) => {
        setResolution(res);
        setStatus('live');
        onConnectedRef.current?.();
      })
      .catch((err) => {
        connected.current = false;
        setStatus('error');
        console.warn('VNC connect error:', err.message);
        onErrorRef.current?.(err.message);
      });
  }, [host, port, keyName, privateKey]);

  // Retry when props change (e.g. when keyName/privateKey arrive after mount)
  useEffect(() => {
    // Reconnect only when connection params change, not on every parent re-render.
    connected.current = false;
    doConnect();
  }, [host, port, keyName, privateKey, doConnect]);

  useEffect(() => {
    if (!emitter) return;
    const subs = [
      emitter.addListener('veyonVncFps', (f) => setFps(parseInt(f))),
      emitter.addListener('veyonVncDisconnected', (reason) => {
        connected.current = false;
        setStatus('error');
        onDisconnectedRef.current?.(reason);
      }),
    ];
    return () => {
      subs.forEach(s => s.remove());
      VeyonVncModule?.disconnect().catch(() => {});
    };
  }, []);

  const handleSurfaceLayout = useCallback(() => {
    if (!surfaceReady.current) {
      surfaceReady.current = true;
      doConnect();
    }
  }, [doConnect]);

  const s = styles(theme);

  return (
    <View style={[s.container, style]}>
      <NativeSurfaceView
        style={StyleSheet.absoluteFill}
        onLayout={handleSurfaceLayout}
      />
      {status === 'connecting' && (
        <View style={s.overlay}>
          <ActivityIndicator color={theme.colors.primary} size="large" />
          <Text style={s.overlayText}>Connecting via VNC…</Text>
        </View>
      )}
      {status === 'error' && (
        <View style={s.overlay}>
          <Text style={[s.overlayText, { color: theme.colors.error }]}>
            ✕ VNC connection failed
          </Text>
        </View>
      )}
      {status === 'live' && (
        <View style={s.badgeRow}>
          <Chip compact style={s.badge} textStyle={s.badgeText}>
            VNC · {fps} FPS
          </Chip>
          {resolution ? (
            <Chip compact style={s.badge} textStyle={s.badgeText}>
              {resolution}
            </Chip>
          ) : null}
        </View>
      )}
    </View>
  );
};

const styles = (theme) => StyleSheet.create({
  container: {
    backgroundColor: '#000',
    overflow: 'hidden',
  },
  overlay: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
  },
  overlayText: {
    color: theme.colors.onSurfaceVariant,
    fontSize: 14,
  },
  badgeRow: {
    position: 'absolute',
    top: 6,
    right: 6,
    flexDirection: 'row',
    gap: 4,
  },
  badge: {
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  badgeText: {
    fontSize: 10,
    color: '#3FB950',
    fontWeight: '700',
  },
});

export default VeyonVncView;