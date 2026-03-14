import React, { useEffect, useRef, useState } from 'react';
import { View, FlatList, StyleSheet, TouchableOpacity } from 'react-native';
import { Text, useTheme, Appbar, Button, Checkbox, ProgressBar, Divider, TextInput } from 'react-native-paper';
import 'react-native-get-random-values';
import { v4 as uuidv4 } from 'uuid';
import { startDiscovery, getDefaultRange } from '../controllers/DiscoveryController';
import { saveComputer } from '../controllers/StorageController';
import { extraColors } from '../theme';

const DiscoveryScreen = ({ navigation }) => {
  const theme = useTheme();
  const [startIP, setStartIP] = useState('');
  const [endIP, setEndIP] = useState('');
  const [scanning, setScanning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [found, setFound] = useState([]);
  const [selected, setSelected] = useState({});
  const [saving, setSaving] = useState(false);
  const [hasScanned, setHasScanned] = useState(false);
  const cancelRef = useRef(null);

  useEffect(() => {
    getDefaultRange().then(({ startIP: s, endIP: e }) => {
      setStartIP(s);
      setEndIP(e);
    });
  }, []);

  const handleScan = () => {
    if (!startIP || !endIP) return;
    cancelRef.current?.();
    setFound([]);
    setSelected({});
    setProgress(0);
    setScanning(true);
    setHasScanned(true);

    cancelRef.current = startDiscovery(
      startIP, endIP,
      (server) => {
        setFound((prev) => [...prev, server]);
        setSelected((prev) => ({ ...prev, [server.ip]: true }));
      },
      (pct) => setProgress(pct / 100),
      () => setScanning(false),
    );
  };

  const toggleSelect = (ip) => setSelected((prev) => ({ ...prev, [ip]: !prev[ip] }));

  const handleAddSelected = async () => {
    const toAdd = found.filter((s) => selected[s.ip]);
    if (toAdd.length === 0) return;
    setSaving(true);
    for (const server of toAdd) {
      await saveComputer({ id: uuidv4(), ip: server.ip, port: server.port, authURL: server.authURL, label: server.ip });
    }
    setSaving(false);
    navigation.goBack();
  };

  const selectedCount = Object.values(selected).filter(Boolean).length;
  const s = styles(theme);

  return (
    <View style={s.container}>
      <Appbar.Header style={s.appbar}>
        <Appbar.BackAction onPress={() => { cancelRef.current?.(); navigation.goBack(); }} iconColor={theme.colors.onSurface} />
        <Appbar.Content title="Discover Computers" titleStyle={s.appbarTitle} />
      </Appbar.Header>

      <View style={s.rangeContainer}>
        <Text style={s.sectionLabel}>IP RANGE</Text>
        <View style={s.rangeRow}>
          <TextInput label="Start IP" value={startIP} onChangeText={setStartIP} mode="outlined" style={s.rangeInput} keyboardType="numeric" autoCapitalize="none" disabled={scanning} />
          <Text style={s.rangeSeparator}>→</Text>
          <TextInput label="End IP" value={endIP} onChangeText={setEndIP} mode="outlined" style={s.rangeInput} keyboardType="numeric" autoCapitalize="none" disabled={scanning} />
        </View>
        <Button mode="contained" onPress={handleScan} disabled={scanning || !startIP || !endIP} loading={scanning} style={s.scanButton} icon="magnify">
          {scanning ? 'Scanning…' : 'Start Scan'}
        </Button>
      </View>

      {(scanning || hasScanned) && (
        <>
          <View style={s.progressContainer}>
            <ProgressBar progress={scanning ? progress : 1} color={scanning ? theme.colors.primary : theme.colors.secondary} style={s.progressBar} />
            <Text style={s.progressText}>{scanning ? `Scanning… ${Math.round(progress * 100)}%` : `Done — ${found.length} server${found.length !== 1 ? 's' : ''} found`}</Text>
          </View>
          <Divider style={{ backgroundColor: theme.colors.outline }} />
        </>
      )}

      {hasScanned && found.length === 0 && !scanning ? (
        <View style={s.empty}>
          <Text style={s.emptyIcon}>🔍</Text>
          <Text style={s.emptyTitle}>No servers found</Text>
          <Text style={s.emptySubtitle}>Make sure Veyon is running{'\n'}and the IP range is correct</Text>
        </View>
      ) : (
        <FlatList
          data={found}
          keyExtractor={(item) => item.ip}
          contentContainerStyle={s.list}
          renderItem={({ item }) => (
            <TouchableOpacity style={s.row} onPress={() => toggleSelect(item.ip)}>
              <Checkbox status={selected[item.ip] ? 'checked' : 'unchecked'} color={theme.colors.primary} onPress={() => toggleSelect(item.ip)} />
              <View style={s.rowInfo}>
                <Text style={s.rowIP}>{item.ip}</Text>
                <Text style={s.rowMeta}>Port {item.port}</Text>
              </View>
              <View style={[s.badge, { backgroundColor: extraColors.level3 }]}>
                <Text style={[s.badgeText, { color: theme.colors.secondary }]}>VEYON</Text>
              </View>
            </TouchableOpacity>
          )}
          ItemSeparatorComponent={() => <Divider style={{ backgroundColor: theme.colors.outline }} />}
        />
      )}

      {found.length > 0 && (
        <View style={s.footer}>
          <Button mode="contained" onPress={handleAddSelected} disabled={selectedCount === 0 || scanning || saving} loading={saving} style={s.addButton} contentStyle={s.addButtonContent}>
            Add {selectedCount > 0 ? `${selectedCount} Computer${selectedCount > 1 ? 's' : ''}` : ''}
          </Button>
        </View>
      )}
    </View>
  );
};

const styles = (theme) => StyleSheet.create({
  container: { flex: 1, backgroundColor: theme.colors.background },
  appbar: { backgroundColor: theme.colors.surface, elevation: 0, borderBottomWidth: 1, borderBottomColor: theme.colors.outline },
  appbarTitle: { fontSize: 18, fontWeight: '700', color: theme.colors.onSurface },
  rangeContainer: { padding: 16, backgroundColor: theme.colors.surface, borderBottomWidth: 1, borderBottomColor: theme.colors.outline },
  sectionLabel: { fontSize: 11, fontWeight: '700', color: theme.colors.onSurfaceVariant, letterSpacing: 1.2, marginBottom: 10 },
  rangeRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 12 },
  rangeInput: { flex: 1, backgroundColor: theme.colors.surfaceVariant },
  rangeSeparator: { color: theme.colors.onSurfaceVariant, marginHorizontal: 8, fontSize: 18 },
  scanButton: { borderRadius: 8 },
  progressContainer: { padding: 16, backgroundColor: theme.colors.surface },
  progressBar: { height: 4, borderRadius: 2, backgroundColor: theme.colors.outline, marginBottom: 8 },
  progressText: { fontSize: 13, color: theme.colors.onSurfaceVariant },
  list: { paddingBottom: 100 },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 12, paddingHorizontal: 16, backgroundColor: theme.colors.surface },
  rowInfo: { flex: 1, marginLeft: 8 },
  rowIP: { fontSize: 15, fontWeight: '600', color: theme.colors.onSurface },
  rowMeta: { fontSize: 12, color: theme.colors.onSurfaceVariant, marginTop: 2 },
  badge: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 4 },
  badgeText: { fontSize: 10, fontWeight: '700', letterSpacing: 0.8 },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 40 },
  emptyIcon: { fontSize: 48, marginBottom: 16 },
  emptyTitle: { fontSize: 18, fontWeight: '700', color: theme.colors.onSurface, marginBottom: 8 },
  emptySubtitle: { fontSize: 14, color: theme.colors.onSurfaceVariant, textAlign: 'center', lineHeight: 22 },
  footer: { position: 'absolute', bottom: 0, left: 0, right: 0, padding: 16, backgroundColor: theme.colors.surface, borderTopWidth: 1, borderTopColor: theme.colors.outline },
  addButton: { borderRadius: 8 },
  addButtonContent: { paddingVertical: 6 },
});

export default DiscoveryScreen;