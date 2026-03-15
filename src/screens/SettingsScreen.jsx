import React, { useState, useCallback } from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import { Text, useTheme, Appbar, List, Divider, RadioButton, Chip, TextInput } from 'react-native-paper';
import { useFocusEffect } from '@react-navigation/native';
import { getSettings, saveSettings } from '../controllers/StorageController';

const RENDERERS = [
  {
    value: 'image',
    label: '🖼️ React Native Image',
    description: 'Base64 → React state. Compatibile con tutto, ma lento (5-10 FPS).',
    badge: 'Stabile',
    badgeColor: '#3FB950',
  },
  {
    value: 'skia',
    label: '⚡ Skia GPU',
    description: 'Bytes diretti → GPU. Più veloce, usa hardware grafico (20-40 FPS).',
    badge: 'Consigliato',
    badgeColor: '#58A6FF',
  },
  {
    value: 'native',
    label: '🚀 Native SurfaceView',
    description: 'BitmapFactory Android nativo, fuori dal thread JS (30-60 FPS).',
    badge: 'Più veloce',
    badgeColor: '#C792EA',
  },
  {
    value: 'vnc',
    label: '🔗 VNC Nativo (Veyon)',
    description: 'Connessione TCP diretta alla porta VNC di Veyon (porta 11100). Solo aree cambiate, 30-60 FPS stabili. Richiede rebuild.',
    badge: 'Sperimentale',
    badgeColor: '#FF9800',
  },
];

const QUALITY_OPTIONS = [
  { value: 30, label: 'Bassa (più veloce)' },
  { value: 50, label: 'Media (consigliata)' },
  { value: 70, label: 'Alta (più lenta)' },
  { value: 90, label: 'Massima' },
];

const COMPRESSION_OPTIONS = [
  { value: 9, label: 'Alta compressione (più veloce)' },
  { value: 6, label: 'Media (bilanciata)' },
  { value: 3, label: 'Bassa (più qualità)' },
];

const SettingsScreen = ({ navigation }) => {
  const theme = useTheme();
  const [settings, setSettings] = useState(null);

  useFocusEffect(useCallback(() => {
    getSettings().then(setSettings);
  }, []));

  const updateSetting = async (key, value) => {
    const updated = { ...settings, [key]: value };
    setSettings(updated);
    await saveSettings({ [key]: value });
  };

  const s = styles(theme);

  if (!settings) return null;

  return (
    <View style={s.container}>
      <Appbar.Header style={s.appbar}>
        <Appbar.BackAction onPress={() => navigation.goBack()} iconColor={theme.colors.onSurface} />
        <Appbar.Content title="Settings" titleStyle={s.appbarTitle} />
      </Appbar.Header>

      <ScrollView contentContainerStyle={s.content}>

        {/* ── Renderer ── */}
        <Text style={s.sectionLabel}>RENDERER FRAMEBUFFER</Text>
        <View style={s.card}>
          {RENDERERS.map((r, i) => (
            <React.Fragment key={r.value}>
              <View style={s.rendererRow}>
                <RadioButton
                  value={r.value}
                  status={settings.renderer === r.value ? 'checked' : 'unchecked'}
                  color={theme.colors.primary}
                  onPress={() => updateSetting('renderer', r.value)}
                />
                <View style={s.rendererInfo}>
                  <View style={s.rendererHeader}>
                    <Text style={s.rendererLabel}>{r.label}</Text>
                    <Chip
                      compact
                      style={[s.badge, { backgroundColor: r.badgeColor + '22' }]}
                      textStyle={[s.badgeText, { color: r.badgeColor }]}
                    >
                      {r.badge}
                    </Chip>
                  </View>
                  <Text style={s.rendererDesc}>{r.description}</Text>
                </View>
              </View>
              {i < RENDERERS.length - 1 && <Divider style={{ backgroundColor: theme.colors.outline }} />}
            </React.Fragment>
          ))}
        </View>

        {/* ── Qualità frame ── */}
        <Text style={[s.sectionLabel, { marginTop: 24 }]}>QUALITÀ FRAME (JPEG)</Text>
        <View style={s.card}>
          {QUALITY_OPTIONS.map((q, i) => (
            <React.Fragment key={q.value}>
              <View style={s.optionRow}>
                <RadioButton
                  value={String(q.value)}
                  status={settings.frameQuality === q.value ? 'checked' : 'unchecked'}
                  color={theme.colors.primary}
                  onPress={() => updateSetting('frameQuality', q.value)}
                />
                <Text style={s.optionLabel}>{q.label}</Text>
              </View>
              {i < QUALITY_OPTIONS.length - 1 && <Divider style={{ backgroundColor: theme.colors.outline }} />}
            </React.Fragment>
          ))}
        </View>

        {/* ── Compressione ── */}
        <Text style={[s.sectionLabel, { marginTop: 24 }]}>COMPRESSIONE</Text>
        <View style={s.card}>
          {COMPRESSION_OPTIONS.map((c, i) => (
            <React.Fragment key={c.value}>
              <View style={s.optionRow}>
                <RadioButton
                  value={String(c.value)}
                  status={settings.frameCompression === c.value ? 'checked' : 'unchecked'}
                  color={theme.colors.primary}
                  onPress={() => updateSetting('frameCompression', c.value)}
                />
                <Text style={s.optionLabel}>{c.label}</Text>
              </View>
              {i < COMPRESSION_OPTIONS.length - 1 && <Divider style={{ backgroundColor: theme.colors.outline }} />}
            </React.Fragment>
          ))}
        </View>

        <Text style={s.hint}>
          Le impostazioni vengono applicate alla prossima connessione.{'\n'}
          NativeSurfaceView e VNC richiedono un rebuild dopo la prima installazione.
        </Text>

        {/* ── VNC Port ── */}
        {settings.renderer === 'vnc' && (
          <>
            <Text style={[s.sectionLabel, { marginTop: 24 }]}>PORTA VNC</Text>
            <View style={s.card}>
              <View style={{ padding: 12 }}>
                <TextInput
                  mode="outlined"
                  label="Porta VNC di Veyon"
                  value={String(settings.vncPort || 11100)}
                  onChangeText={(v) => {
                    const n = parseInt(v);
                    if (!isNaN(n)) updateSetting('vncPort', n);
                  }}
                  keyboardType="number-pad"
                  style={{ backgroundColor: theme.colors.surfaceVariant }}
                />
                <Text style={[s.hint, { marginTop: 8, textAlign: 'left' }]}>
                  Default: 11100 — è la porta VNC interna di Veyon Service.
                </Text>
              </View>
            </View>
          </>
        )}
      </ScrollView>
    </View>
  );
};

const styles = (theme) => StyleSheet.create({
  container: { flex: 1, backgroundColor: theme.colors.background },
  appbar: { backgroundColor: theme.colors.surface, elevation: 0, borderBottomWidth: 1, borderBottomColor: theme.colors.outline },
  appbarTitle: { fontSize: 18, fontWeight: '700', color: theme.colors.onSurface },
  content: { padding: 20, paddingBottom: 40 },
  sectionLabel: {
    fontSize: 11, fontWeight: '700', color: theme.colors.onSurfaceVariant,
    letterSpacing: 1.2, marginBottom: 10,
  },
  card: {
    backgroundColor: theme.colors.surface,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: theme.colors.outline,
    overflow: 'hidden',
  },
  rendererRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    padding: 12,
  },
  rendererInfo: { flex: 1, marginLeft: 4 },
  rendererHeader: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 4 },
  rendererLabel: { fontSize: 14, fontWeight: '600', color: theme.colors.onSurface, flex: 1 },
  rendererDesc: { fontSize: 12, color: theme.colors.onSurfaceVariant, lineHeight: 18 },
  badge: { borderRadius: 4 },
  badgeText: { fontSize: 10, fontWeight: '700' },
  optionRow: { flexDirection: 'row', alignItems: 'center', padding: 12 },
  optionLabel: { fontSize: 14, color: theme.colors.onSurface, marginLeft: 4 },
  hint: {
    marginTop: 20,
    fontSize: 12,
    color: theme.colors.onSurfaceVariant,
    textAlign: 'center',
    lineHeight: 20,
  },
});

export default SettingsScreen;