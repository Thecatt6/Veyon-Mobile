import React, { useEffect, useRef, useState, useCallback } from 'react';
import { View, Image, TouchableOpacity, StyleSheet } from 'react-native';
import { Text, useTheme, ActivityIndicator } from 'react-native-paper';
import { authenticate, getSessionUser, buildBaseURL } from '../controllers/VeyonAPI';
import { startFramePoller } from '../controllers/MJPEGStream';
import { extraColors } from '../theme';
import { getAuthKeys } from '../controllers/StorageController';

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
      } catch { /* try next */ }
    }
    return null;
  }, [computer]);

  const tryAuthenticate = useCallback(async () => {
    const keys = await getAuthKeys();
    const keyList = Object.values(keys);
    if (keyList.length === 0) { if (mountedRef.current) setStatus('no-auth'); return false; }

    for (const key of keyList) {
      try {
        const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
        if (!mountedRef.current) return false;
        sessionRef.current = session;
        const user = await getSessionUser(baseURL, session.connectionUid);
        if (mountedRef.current) {
          setUserLabel(user || '');
          setStatus('live');
        }
        return true;
      } catch { /* try next */ }
    }
    if (mountedRef.current) setStatus('error');
    return false;
  }, [computer, baseURL]);

  useEffect(() => {
    mountedRef.current = true;

    tryAuthenticate().then((ok) => {
      if (!ok || !mountedRef.current) return;
      setTimeout(() => {
        if (!mountedRef.current) return;
        cancelStreamRef.current = startFramePoller(
          baseURL,
          sessionRef.current.connectionUid,
          (dataURI) => {
            if (mountedRef.current) setFrameSource({ uri: dataURI });
          },
          () => {},
          reAuth,
          { quality: 40, compression: 9 }, // lower quality for grid thumbnails
        );
      }, 1500);
    });

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

  const statusLabel = {
    connecting: 'Connecting…',
    live: userLabel || computer.label || computer.ip,
    error: 'Unreachable',
    'no-auth': 'No Auth Key',
  }[status];

  return (
    <TouchableOpacity
      style={s.card}
      onPress={() => onPress?.({ computer, session: sessionRef.current, baseURL })}
      onLongPress={() => onLongPress?.(computer)}
      activeOpacity={0.8}
    >
      {/* Framebuffer / placeholder */}
      <View style={s.preview}>
        {frameSource ? (
          <Image source={frameSource} style={s.image} resizeMode="cover" fadeDuration={0} />
        ) : (
          <View style={s.placeholder}>
            {status === 'connecting' ? (
              <ActivityIndicator color={theme.colors.primary} size="small" />
            ) : (
              <Text style={[s.placeholderIcon, { color: statusColor }]}>
                {status === 'error' || status === 'no-auth' ? '✕' : '…'}
              </Text>
            )}
          </View>
        )}
        {status === 'live' && (
          <View style={[s.liveDot, { backgroundColor: theme.colors.secondary }]} />
        )}
      </View>

      {/* Footer */}
      <View style={s.footer}>
        <View style={s.footerText}>
          <Text style={s.ipText} numberOfLines={1}>{computer.ip}</Text>
          <Text style={[s.statusText, { color: statusColor }]} numberOfLines={1}>
            {statusLabel}
          </Text>
        </View>
      </View>
    </TouchableOpacity>
  );
};

const styles = (theme) => StyleSheet.create({
  card: {
    flex: 1,
    margin: 3,
    backgroundColor: theme.colors.surface,
    borderRadius: 6,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: theme.colors.outline,
  },
  preview: {
    width: '100%',
    aspectRatio: 16 / 9,
    backgroundColor: extraColors.level2,
    position: 'relative',
  },
  image: { width: '100%', height: '100%' },
  placeholder: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  placeholderIcon: { fontSize: 14 },
  liveDot: {
    position: 'absolute',
    top: 4,
    right: 4,
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  footer: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  footerText: { flex: 1 },
  ipText: { fontSize: 11, fontWeight: '600', color: '#E6EDF3' },
  statusText: { fontSize: 10, marginTop: 1 },
});

export default ComputerCard;