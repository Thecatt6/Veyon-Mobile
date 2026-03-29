import React, { useEffect, useRef, useState, useCallback } from 'react';
import { View, Image, TouchableOpacity, StyleSheet } from 'react-native';
import { Text, useTheme, ActivityIndicator } from 'react-native-paper';
import { authenticate, getSessionUser, buildBaseURL } from '../controllers/VeyonAPI';
import { startFramePoller } from '../controllers/MJPEGStream';
import { extraColors } from '../theme';
import { getAuthKeys, getSettings } from '../controllers/StorageController';
import { NativeModules } from 'react-native';

const { NetworkProbeModule } = NativeModules;

const ComputerCard = ({ computer, onPress, onLongPress }) => {
  const theme = useTheme();
  const [frameSource, setFrameSource] = useState(null);
  const [userLabel, setUserLabel] = useState('');
  const [status, setStatus] = useState('connecting');
  const sessionRef = useRef(null);
  const cancelStreamRef = useRef(null);
  const mountedRef = useRef(true);

  const baseURL = buildBaseURL(computer.ip, computer.port);

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

  useEffect(() => {
    mountedRef.current = true;

    (async () => {
      const [keys, settings] = await Promise.all([getAuthKeys(), getSettings()]);
      const keyList = Object.values(keys);
      const method = settings.connectionMethod || 'webapi';

      if (method === 'vnc') {
        // VNC mode: prova TCP probe per verificare raggiungibilità
        const vncPort = settings.vncPort || 11100;
        let reachable = false;
        try {
          reachable = await NetworkProbeModule.tcpProbe(computer.ip, vncPort, 1500);
        } catch { }

        if (!mountedRef.current) return;

        if (!reachable) {
          setStatus('error');
          return;
        }

        // PC raggiungibile via VNC — prova WebAPI in background per ottenere username
        // Questo è non-bloccante: se fallisce, mostriamo solo il label del computer
        setStatus('live');

        if (keyList.length > 0) {
          for (const key of keyList) {
            try {
              const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
              if (!mountedRef.current) return;
              sessionRef.current = session;
              const user = await getSessionUser(baseURL, session.connectionUid);
              if (mountedRef.current && user) {
                setUserLabel(user);
              }
              // Se WebAPI funziona, avvia anche lo stream thumbnail
              setTimeout(() => {
                if (!mountedRef.current || !sessionRef.current) return;
                cancelStreamRef.current = startFramePoller(
                  baseURL,
                  sessionRef.current.connectionUid,
                  (dataURI) => { if (mountedRef.current) setFrameSource({ uri: dataURI }); },
                  () => {},
                  reAuth,
                  { quality: 40, compression: 9 },
                );
              }, 1500);
              break;
            } catch {
              // WebAPI non disponibile — ok, mostriamo solo il computer label
            }
          }
        }
        return;
      }

      // WebAPI mode
      if (keyList.length === 0) { if (mountedRef.current) setStatus('no-auth'); return; }

      for (const key of keyList) {
        try {
          const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
          if (!mountedRef.current) return;
          sessionRef.current = session;
          try {
            const user = await getSessionUser(baseURL, session.connectionUid);
            if (mountedRef.current) setUserLabel(user || '');
          } catch { }
          if (mountedRef.current) setStatus('live');

          setTimeout(() => {
            if (!mountedRef.current) return;
            cancelStreamRef.current = startFramePoller(
              baseURL,
              session.connectionUid,
              (dataURI) => { if (mountedRef.current) setFrameSource({ uri: dataURI }); },
              () => {},
              reAuth,
              { quality: 40, compression: 9 },
            );
          }, 1500);
          return;
        } catch { }
      }
      if (mountedRef.current) setStatus('error');
    })();

    return () => {
      mountedRef.current = false;
      cancelStreamRef.current?.();
    };
  }, []);

  const s = styles(theme);

  const statusColor = {
    connecting: theme.colors.onSurfaceVariant,
    live: theme.colors.secondary,
    error: theme.colors.error,
    'no-auth': theme.colors.error,
  }[status];

  // Label da mostrare sotto l'IP:
  // - In VNC mode senza WebAPI: mostra il label del computer configurato
  // - In VNC mode con WebAPI: mostra username@computer
  // - In WebAPI mode: mostra username
  const displayLabel = (() => {
    if (status === 'connecting') return 'Connecting…';
    if (status === 'error') return 'Unreachable';
    if (status === 'no-auth') return 'No Auth Key';
    if (userLabel) return userLabel;
    return computer.label || computer.ip;
  })();

  return (
    <TouchableOpacity
      style={s.card}
      onPress={() => onPress?.({ computer, session: sessionRef.current, baseURL })}
      onLongPress={() => onLongPress?.(computer)}
      activeOpacity={0.8}
    >
      <View style={s.preview}>
        {frameSource ? (
          <Image source={frameSource} style={s.image} resizeMode="cover" fadeDuration={0} />
        ) : (
          <View style={s.placeholder}>
            {status === 'connecting' ? (
              <ActivityIndicator color={theme.colors.primary} size="small" />
            ) : (
              <Text style={[s.placeholderIcon, { color: statusColor }]}>
                {status === 'error' || status === 'no-auth' ? '✕' : '🖥️'}
              </Text>
            )}
          </View>
        )}
        {status === 'live' && (
          <View style={[s.liveDot, { backgroundColor: theme.colors.secondary }]} />
        )}
      </View>

      <View style={s.footer}>
        <View style={s.footerText}>
          <Text style={s.ipText} numberOfLines={1}>{computer.ip}</Text>
          <Text style={[s.statusText, { color: statusColor }]} numberOfLines={1}>
            {displayLabel}
          </Text>
        </View>
      </View>
    </TouchableOpacity>
  );
};

const styles = (theme) => StyleSheet.create({
  card: {
    flex: 1, margin: 3,
    backgroundColor: theme.colors.surface,
    borderRadius: 6, overflow: 'hidden',
    borderWidth: 1, borderColor: theme.colors.outline,
  },
  preview: {
    width: '100%', aspectRatio: 16 / 9,
    backgroundColor: extraColors.level2, position: 'relative',
  },
  image: { width: '100%', height: '100%' },
  placeholder: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  placeholderIcon: { fontSize: 14 },
  liveDot: { position: 'absolute', top: 4, right: 4, width: 6, height: 6, borderRadius: 3 },
  footer: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 8, paddingVertical: 6 },
  footerText: { flex: 1 },
  ipText: { fontSize: 11, fontWeight: '600', color: '#E6EDF3' },
  statusText: { fontSize: 10, marginTop: 1 },
});

export default ComputerCard;