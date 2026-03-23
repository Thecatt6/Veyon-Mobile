import React, { useEffect, useState, useCallback } from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import {
  Text, useTheme, Appbar, List, Switch, Divider,
  SegmentedButtons, TextInput, Button, Snackbar,
} from 'react-native-paper';
import { useFocusEffect } from '@react-navigation/native';
import { getSettings, saveSettings } from '../controllers/StorageController';

const SettingsScreen = ({ navigation }) => {
  const theme = useTheme();
  const [settings, setSettings] = useState(null);
  const [saved, setSaved] = useState(false);

  useFocusEffect(useCallback(() => {
    getSettings().then(setSettings);
  }, []));

  const update = async (key, value) => {
    const next = { ...settings, [key]: value };
    setSettings(next);
    await saveSettings({ [key]: value });
    setSaved(true);
  };

  if (!settings) return null;

  const s = styles(theme);

  return (
    <View style={s.container}>
      <Appbar.Header style={s.appbar}>
        <Appbar.BackAction onPress={() => navigation.goBack()} iconColor={theme.colors.onSurface} />
        <Appbar.Content title="Settings" titleStyle={s.appbarTitle} />
      </Appbar.Header>

      <ScrollView contentContainerStyle={s.scroll}>

        {/* ── Metodo di connessione ───────────────────────────────────────── */}
        <Text style={s.sectionLabel}>CONNECTION METHOD</Text>
        <View style={s.card}>
          <Text style={s.settingTitle}>Protocol</Text>
          <Text style={s.settingDesc}>
            WebAPI uses HTTP (requires Veyon WebAPI plugin).{'\n'}
            VNC uses the native Veyon protocol on port 11100 (always available).
          </Text>
          <SegmentedButtons
            value={settings.connectionMethod}
            onValueChange={(v) => update('connectionMethod', v)}
            density="small"
            style={{ marginTop: 12 }}
            buttons={[
              { value: 'webapi', label: '🌐 WebAPI (HTTP)', icon: 'web' },
              { value: 'vnc',    label: '🖥️ VNC (native)',   icon: 'monitor' },
            ]}
          />
          {settings.connectionMethod === 'webapi' && (
            <TextInput
              mode="outlined"
              label="WebAPI Port"
              value={String(settings.webApiPort)}
              onChangeText={(v) => update('webApiPort', parseInt(v) || 11080)}
              keyboardType="number-pad"
              dense
              style={[s.input, { marginTop: 12 }]}
            />
          )}
          {settings.connectionMethod === 'vnc' && (
            <TextInput
              mode="outlined"
              label="VNC Port"
              value={String(settings.vncPort)}
              onChangeText={(v) => update('vncPort', parseInt(v) || 11100)}
              keyboardType="number-pad"
              dense
              style={[s.input, { marginTop: 12 }]}
            />
          )}
        </View>

        <Divider style={s.divider} />

        {/* ── Renderer ────────────────────────────────────────────────────── */}
        <Text style={s.sectionLabel}>FRAMEBUFFER RENDERER</Text>
        <View style={s.card}>
          <Text style={s.settingTitle}>Rendering engine</Text>
          <Text style={s.settingDesc}>
            Image: compatibile, bassa CPU.{'\n'}
            Skia: GPU accelerato, migliori FPS.{'\n'}
            Native SurfaceView: massima velocità, solo VNC.
          </Text>
          <SegmentedButtons
            value={settings.renderer}
            onValueChange={(v) => update('renderer', v)}
            density="small"
            style={{ marginTop: 12 }}
            buttons={[
              { value: 'image',  label: 'Image' },
              { value: 'skia',   label: 'Skia' },
              { value: 'native', label: 'Native' },
            ]}
          />
        </View>

        <Divider style={s.divider} />

        {/* ── Qualità ─────────────────────────────────────────────────────── */}
        <Text style={s.sectionLabel}>FRAME QUALITY</Text>
        <View style={s.card}>
          <View style={s.row}>
            <View style={s.rowLabel}>
              <Text style={s.settingTitle}>JPEG Quality</Text>
              <Text style={s.settingDesc}>1-100. Più alto = migliore qualità, più banda.</Text>
            </View>
            <TextInput
              mode="outlined"
              value={String(settings.frameQuality)}
              onChangeText={(v) => {
                const n = Math.max(1, Math.min(100, parseInt(v) || 50));
                update('frameQuality', n);
              }}
              keyboardType="number-pad"
              dense
              style={s.numInput}
            />
          </View>

          <Divider style={{ marginVertical: 12, backgroundColor: theme.colors.outline }} />

          <View style={s.row}>
            <View style={s.rowLabel}>
              <Text style={s.settingTitle}>Compression</Text>
              <Text style={s.settingDesc}>1-9. Più alto = più compresso, più CPU.</Text>
            </View>
            <TextInput
              mode="outlined"
              value={String(settings.frameCompression)}
              onChangeText={(v) => {
                const n = Math.max(1, Math.min(9, parseInt(v) || 8));
                update('frameCompression', n);
              }}
              keyboardType="number-pad"
              dense
              style={s.numInput}
            />
          </View>

          <Divider style={{ marginVertical: 12, backgroundColor: theme.colors.outline }} />

          <View style={s.row}>
            <View style={s.rowLabel}>
              <Text style={s.settingTitle}>Frame throttle (ms)</Text>
              <Text style={s.settingDesc}>0 = massima velocità. 100 = ~10 FPS max.</Text>
            </View>
            <TextInput
              mode="outlined"
              value={String(settings.frameThrottleMs)}
              onChangeText={(v) => {
                const n = Math.max(0, Math.min(1000, parseInt(v) || 0));
                update('frameThrottleMs', n);
              }}
              keyboardType="number-pad"
              dense
              style={s.numInput}
            />
          </View>
        </View>

        <Divider style={s.divider} />

        {/* ── Info ────────────────────────────────────────────────────────── */}
        <Text style={s.sectionLabel}>INFO</Text>
        <View style={s.card}>
          <View style={s.row}>
            <Text style={[s.settingTitle, { flex: 1 }]}>Connection method</Text>
            <Text style={[s.settingDesc, { color: theme.colors.primary }]}>
              {settings.connectionMethod === 'webapi' ? `WebAPI :${settings.webApiPort}` : `VNC :${settings.vncPort}`}
            </Text>
          </View>
          <View style={[s.row, { marginTop: 8 }]}>
            <Text style={[s.settingTitle, { flex: 1 }]}>Renderer</Text>
            <Text style={[s.settingDesc, { color: theme.colors.primary }]}>
              {settings.renderer}
            </Text>
          </View>
          <View style={[s.row, { marginTop: 8 }]}>
            <Text style={[s.settingTitle, { flex: 1 }]}>Quality</Text>
            <Text style={[s.settingDesc, { color: theme.colors.primary }]}>
              Q{settings.frameQuality} C{settings.frameCompression} T{settings.frameThrottleMs}ms
            </Text>
          </View>
        </View>

      </ScrollView>

      <Snackbar
        visible={saved}
        onDismiss={() => setSaved(false)}
        duration={1500}
        style={{ backgroundColor: theme.colors.surfaceVariant }}
      >
        <Text style={{ color: theme.colors.onSurface }}>✓ Saved</Text>
      </Snackbar>
    </View>
  );
};

const styles = (theme) => StyleSheet.create({
  container: { flex: 1, backgroundColor: theme.colors.background },
  appbar: { backgroundColor: theme.colors.surface, elevation: 0, borderBottomWidth: 1, borderBottomColor: theme.colors.outline },
  appbarTitle: { fontSize: 18, fontWeight: '700', color: theme.colors.onSurface },
  scroll: { padding: 16, paddingBottom: 40 },
  sectionLabel: {
    fontSize: 11, fontWeight: '700', color: theme.colors.onSurfaceVariant,
    letterSpacing: 1.2, marginBottom: 8, marginTop: 4,
  },
  card: {
    backgroundColor: theme.colors.surface,
    borderRadius: 10,
    padding: 16,
    borderWidth: 1,
    borderColor: theme.colors.outline,
    marginBottom: 4,
  },
  settingTitle: { fontSize: 14, fontWeight: '600', color: theme.colors.onSurface },
  settingDesc: { fontSize: 12, color: theme.colors.onSurfaceVariant, marginTop: 2, lineHeight: 18 },
  divider: { backgroundColor: theme.colors.outline, marginVertical: 12 },
  input: { backgroundColor: theme.colors.surfaceVariant },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  rowLabel: { flex: 1, marginRight: 12 },
  numInput: { width: 80, backgroundColor: theme.colors.surfaceVariant },
});

export default SettingsScreen;