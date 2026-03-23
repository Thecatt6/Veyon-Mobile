import React, { useEffect, useRef, useState, useCallback } from 'react';
import { View, FlatList, StyleSheet, TouchableOpacity } from 'react-native';
import { Text, useTheme, Appbar, Button, Checkbox, ProgressBar, Divider, TextInput, SegmentedButtons } from 'react-native-paper';
import 'react-native-get-random-values';
import { v4 as uuidv4 } from 'uuid';
import { startDiscovery, expandHostnamePattern, VEYON_VNC_PORT } from '../controllers/DiscoveryController';
import { saveComputer, getSettings } from '../controllers/StorageController';

const DiscoveryScreen = ({ navigation }) => {
  const theme = useTheme();

  // ── Modalità scansione ────────────────────────────────────────────────────
  const [mode, setMode] = useState('subnet'); // 'subnet' | 'hostname_range' | 'ip_range'
  const [connectionMethod, setConnectionMethod] = useState('vnc');
  const [subnetInput, setSubnetInput] = useState('');       // es. 192.168.1
  const [hostnameInput, setHostnameInput] = useState('');   // es. INFO1-PC[01-23]
  const [ipFrom, setIpFrom] = useState('');
  const [ipTo, setIpTo] = useState('');
  const [portInput, setPortInput] = useState(String(VEYON_VNC_PORT));

  // ── Stato scansione ───────────────────────────────────────────────────────
  const [scanning, setScanning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [found, setFound] = useState([]);
  const [selected, setSelected] = useState({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const cancelRef = useRef(null);

  useEffect(() => {
    getSettings().then(s => setConnectionMethod(s.connectionMethod || 'vnc'));
  }, []);

  const toggleSelect = (host) => {
    setSelected(prev => ({ ...prev, [host]: !prev[host] }));
  };

  // ── Avvia scansione ───────────────────────────────────────────────────────
  const handleScan = useCallback(() => {
    setError('');
    setFound([]);
    setSelected({});
    setProgress(0);
    setScanning(true);

    const port = parseInt(portInput) || VEYON_VNC_PORT;
    let options = { port };

    if (mode === 'hostname_range') {
      const pattern = hostnameInput.trim();
      if (!pattern) {
        setError('Inserisci un pattern hostname. Es: INFO1-PC[01-23]');
        setScanning(false);
        return;
      }
      options = { ...options, mode: 'hostname_range', hostnamePattern: pattern };
    } else if (mode === 'ip_range') {
      if (!ipFrom.trim() || !ipTo.trim()) {
        setError('Inserisci IP iniziale e finale');
        setScanning(false);
        return;
      }
      options = { ...options, mode: 'ip_range', ipFrom: ipFrom.trim(), ipTo: ipTo.trim() };
    } else {
      options = { ...options, mode: 'subnet', subnet: subnetInput.trim() || undefined };
    }

    cancelRef.current = startDiscovery(
      { ...options, mode: options.mode || 'subnet', connectionMethod },
      (server) => {
        setFound(prev => [...prev, server]);
        setSelected(prev => ({ ...prev, [server.host]: true }));
      },
      (pct) => setProgress(pct / 100),
      () => setScanning(false),
    );
  }, [mode, subnetInput, hostnameInput, ipFrom, ipTo, portInput]);

  const handleStop = () => {
    cancelRef.current?.();
    setScanning(false);
  };

  // ── Aggiungi selezionati ──────────────────────────────────────────────────
  const handleAddSelected = async () => {
    const toAdd = found.filter(s => selected[s.host]);
    if (toAdd.length === 0) return;
    setSaving(true);
    for (const server of toAdd) {
      await saveComputer({
        id: uuidv4(),
        ip: server.host,
        port: server.port,
        authURL: `http://${server.host}:11080/api/v1/authentication/${server.host}:${server.port}`,
        label: server.host,
        useVnc: true, // flag per usare VNC invece di WebAPI
      });
    }
    setSaving(false);
    navigation.goBack();
  };

  const selectedCount = Object.values(selected).filter(Boolean).length;
  const s = styles(theme);

  return (
    <View style={s.container}>
      <Appbar.Header style={s.appbar}>
        <Appbar.BackAction
          onPress={() => { cancelRef.current?.(); navigation.goBack(); }}
          iconColor={theme.colors.onSurface}
        />
        <Appbar.Content title="Discover Computers" titleStyle={s.appbarTitle} />
      </Appbar.Header>

      {/* Modalità */}
      <View style={s.configSection}>
        <SegmentedButtons
          value={mode}
          onValueChange={setMode}
          density="small"
          buttons={[
            { value: 'subnet', label: 'Subnet' },
            { value: 'hostname_range', label: 'Hostname' },
            { value: 'ip_range', label: 'IP Range' },
          ]}
          style={{ marginBottom: 12 }}
        />

        {mode === 'subnet' && (
          <TextInput
            mode="outlined"
            label="Subnet (vuoto = auto)"
            value={subnetInput}
            onChangeText={setSubnetInput}
            placeholder="192.168.1"
            autoCapitalize="none"
            keyboardType="default"
            dense
            style={s.input}
          />
        )}

        {mode === 'hostname_range' && (
          <TextInput
            mode="outlined"
            label="Range hostname"
            value={hostnameInput}
            onChangeText={setHostnameInput}
            placeholder="INFO1-PC[01-23] o INFO[1-4]-PCDOC"
            autoCapitalize="characters"
            dense
            style={s.input}
          />
        )}

        {mode === 'ip_range' && (
          <View style={{ flexDirection: 'row', gap: 8 }}>
            <TextInput
              mode="outlined"
              label="Da IP"
              value={ipFrom}
              onChangeText={setIpFrom}
              placeholder="192.168.1.100"
              autoCapitalize="none"
              keyboardType="default"
              dense
              style={[s.input, { flex: 1 }]}
            />
            <TextInput
              mode="outlined"
              label="A IP"
              value={ipTo}
              onChangeText={setIpTo}
              placeholder="192.168.1.150"
              autoCapitalize="none"
              keyboardType="default"
              dense
              style={[s.input, { flex: 1 }]}
            />
          </View>
        )}

        <View style={{ flexDirection: 'row', gap: 8, alignItems: 'center', marginTop: 8 }}>
          <TextInput
            mode="outlined"
            label="Porta"
            value={portInput}
            onChangeText={setPortInput}
            keyboardType="number-pad"
            dense
            style={[s.input, { width: 100 }]}
          />
          <Button
            mode="contained"
            onPress={scanning ? handleStop : handleScan}
            style={{ flex: 1 }}
            icon={scanning ? 'stop' : 'magnify'}
          >
            {scanning ? 'Stop' : 'Scan'}
          </Button>
        </View>

        {error ? <Text style={s.errorText}>{error}</Text> : null}
      </View>

      {/* Progress */}
      {scanning && (
        <View style={s.progressContainer}>
          <ProgressBar
            progress={progress}
            color={theme.colors.primary}
            style={s.progressBar}
          />
          <Text style={s.progressText}>
            Scansione in corso… {Math.round(progress * 100)}% — {found.length} trovati
          </Text>
        </View>
      )}

      <Divider style={{ backgroundColor: theme.colors.outline }} />

      {/* Risultati */}
      {found.length === 0 && !scanning ? (
        <View style={s.empty}>
          <Text style={s.emptyIcon}>🔍</Text>
          <Text style={s.emptyTitle}>Nessun server trovato</Text>
          <Text style={s.emptySubtitle}>
            Avvia una scansione per trovare{'\n'}i server Veyon sulla rete
          </Text>
        </View>
      ) : (
        <FlatList
          data={found}
          keyExtractor={(item) => item.host}
          contentContainerStyle={s.list}
          renderItem={({ item }) => (
            <TouchableOpacity style={s.row} onPress={() => toggleSelect(item.host)}>
              <Checkbox
                status={selected[item.host] ? 'checked' : 'unchecked'}
                color={theme.colors.primary}
                onPress={() => toggleSelect(item.host)}
              />
              <View style={s.rowInfo}>
                <Text style={s.rowHost}>{item.host}</Text>
                <Text style={s.rowMeta}>Porta {item.port} · Veyon VNC</Text>
              </View>
              <View style={[s.badge, { backgroundColor: theme.colors.surfaceVariant }]}>
                <Text style={[s.badgeText, { color: theme.colors.secondary }]}>VEYON</Text>
              </View>
            </TouchableOpacity>
          )}
          ItemSeparatorComponent={() => <Divider style={{ backgroundColor: theme.colors.outline }} />}
        />
      )}

      {/* Aggiungi button */}
      {found.length > 0 && (
        <View style={s.footer}>
          <Button
            mode="contained"
            onPress={handleAddSelected}
            disabled={selectedCount === 0 || saving}
            loading={saving}
            style={s.addButton}
            contentStyle={s.addButtonContent}
          >
            Aggiungi {selectedCount > 0 ? `${selectedCount} computer` : ''}
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
  configSection: { padding: 16, backgroundColor: theme.colors.surface, borderBottomWidth: 1, borderBottomColor: theme.colors.outline },
  input: { backgroundColor: theme.colors.surfaceVariant, marginBottom: 4 },
  errorText: { color: theme.colors.error, fontSize: 12, marginTop: 4 },
  progressContainer: { padding: 16, backgroundColor: theme.colors.surface },
  progressBar: { height: 4, borderRadius: 2, backgroundColor: theme.colors.outline, marginBottom: 8 },
  progressText: { fontSize: 13, color: theme.colors.onSurfaceVariant },
  list: { paddingBottom: 100 },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 12, paddingHorizontal: 16, backgroundColor: theme.colors.surface },
  rowInfo: { flex: 1, marginLeft: 8 },
  rowHost: { fontSize: 15, fontWeight: '600', color: theme.colors.onSurface },
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