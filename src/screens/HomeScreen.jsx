import React, { useCallback, useState, useRef } from 'react';
import { View, FlatList, StyleSheet, TouchableOpacity } from 'react-native';
import { FAB, Portal, useTheme, Text, Appbar, Dialog, Button, Checkbox, ActivityIndicator } from 'react-native-paper';
import { useFocusEffect } from '@react-navigation/native';
import ComputerCard from '../components/ComputerCard';
import { getComputers, deleteComputer } from '../controllers/StorageController';
import { getAuthKeys } from '../controllers/StorageController';
import { authenticate, getFeatures, setFeature } from '../controllers/VeyonAPI';

// Feature labels (stesso mapping di ComputerDetailScreen)
const FEATURE_LABELS = {
  'ScreenLock':                 '🔒 Lock Screen',
  'InputDevicesLock':           '⌨️ Lock Input',
  'Screenshot':                 '📸 Screenshot',
  'UserLogin':                  '🔑 Login',
  'UserLogoff':                 '🚪 Logoff',
  'TextMessage':                '💬 Message',
  'FullScreenDemo':             '📺 Demo (Fullscreen)',
  'WindowDemo':                 '🪟 Demo (Window)',
  'ShareOwnScreenFullScreen':   '🖥️ Share My Screen (Full)',
  'ShareOwnScreenWindow':       '🖥️ Share My Screen (Window)',
  'ShareUserScreenFullScreen':  '👁️ Share User Screen (Full)',
  'ShareUserScreenWindow':      '👁️ Share User Screen (Window)',
  'StartApp':                   '▶️ Start App',
  'OpenWebsite':                '🌐 Open Website',
  'DistributeFiles':            '📤 Distribute Files',
  'FileCollect':                '📥 Collect Files',
  'PowerOn':                    '⚡ Power On',
  'Reboot':                     '🔄 Reboot',
  'PowerDown':                  '⏻ Shutdown',
  'PowerDownNow':               '⏻ Shutdown Now',
  'InstallUpdatesAndPowerDown': '🔄 Update & Shutdown',
  'PowerDownConfirmed':         '⏻ Shutdown (Confirmed)',
  'PowerDownDelayed':           '⏻ Shutdown (Delayed)',
  'RemoteView':                 '👁️ Remote View',
  'RemoteControl':              '🖱️ Remote Control',
  'ClipboardExchange':          '📋 Clipboard',
};

const HIDDEN_FEATURES = new Set([
  'Demo', 'DemoServer', 'SystemTrayIcon', 'MonitoringMode',
  'QueryApplicationVersion', 'QueryActiveFeatures', 'UserInfo',
  'SessionInfo', 'QueryScreens', 'IdentifyUser',
  'DesktopAccessDialog', 'AccessControlProvider',
]);

function getFeatureLabel(f) {
  return FEATURE_LABELS[f.name] || f.name || f.uid.slice(0, 8);
}

const HomeScreen = ({ navigation }) => {
  const theme = useTheme();
  const [computers, setComputers] = useState([]);
  const [fabOpen, setFabOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState(null);

  // ── Multi-select state ──────────────────────────────────────────────────
  const [selectMode, setSelectMode] = useState(false);
  const [selected, setSelected] = useState({}); // { [computer.id]: true }

  // ── Broadcast action state ──────────────────────────────────────────────
  const [broadcastFeatures, setBroadcastFeatures] = useState([]);
  const [broadcastLoading, setBroadcastLoading] = useState(false);
  const [broadcastDialogVisible, setBroadcastDialogVisible] = useState(false);

  const loadComputers = useCallback(async () => {
    const data = await getComputers();
    setComputers(Object.values(data));
  }, []);

  useFocusEffect(useCallback(() => {
    loadComputers();
  }, [loadComputers]));

  const handleLongPress = (computer) => {
    if (!selectMode) {
      setSelectMode(true);
      setSelected({ [computer.id]: true });
    }
  };

  const handlePress = ({ computer }) => {
    if (selectMode) {
      setSelected((prev) => {
        const next = { ...prev, [computer.id]: !prev[computer.id] };
        // Exit select mode if nothing selected
        if (!Object.values(next).some(Boolean)) {
          setSelectMode(false);
          return {};
        }
        return next;
      });
    } else {
      navigation.navigate('ComputerDetail', { computer });
    }
  };

  const handleConfirmDelete = async () => {
    if (!deleteTarget) return;
    await deleteComputer(deleteTarget.id);
    setDeleteTarget(null);
    loadComputers();
  };

  const exitSelectMode = () => {
    setSelectMode(false);
    setSelected({});
  };

  const selectedComputers = computers.filter((c) => selected[c.id]);
  const selectedCount = selectedComputers.length;

  // ── Fetch common features for selected computers ────────────────────────
  const openBroadcastDialog = async () => {
    if (selectedCount === 0) return;
    setBroadcastLoading(true);
    setBroadcastDialogVisible(true);

    try {
      const keys = await getAuthKeys();
      const keyList = Object.values(keys);
      if (keyList.length === 0) { setBroadcastLoading(false); return; }

      // Fetch features from all selected computers
      const allFeatureSets = await Promise.all(
        selectedComputers.map(async (computer) => {
          for (const key of keyList) {
            try {
              const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
              const data = await getFeatures(`http://${computer.ip}:${computer.port}`, session.connectionUid);
              const list = Array.isArray(data) ? data : (data?.features || data?.data || []);
              return list;
            } catch { /* try next key */ }
          }
          return [];
        })
      );

      // Find features common to all selected computers (by name)
      const nameSets = allFeatureSets.map((fs) => new Set(fs.map((f) => f.name)));
      const commonNames = nameSets.reduce((acc, s) => new Set([...acc].filter((n) => s.has(n))));
      const commonFeatures = allFeatureSets[0]?.filter((f) => commonNames.has(f.name)) || [];

      setBroadcastFeatures(commonFeatures.filter((f) => !HIDDEN_FEATURES.has(f.name)));
    } catch (err) {
      console.warn('Broadcast feature fetch error:', err?.message);
    } finally {
      setBroadcastLoading(false);
    }
  };

  // ── Send action to all selected computers ──────────────────────────────
  const broadcastAction = async (featureName) => {
    setBroadcastDialogVisible(false);
    setBroadcastLoading(true);

    const keys = await getAuthKeys();
    const keyList = Object.values(keys);

    const results = await Promise.allSettled(
      selectedComputers.map(async (computer) => {
        for (const key of keyList) {
          try {
            const session = await authenticate(computer.authURL, key.keyName, key.privateKey);
            const baseURL = `http://${computer.ip}:${computer.port}`;
            const data = await getFeatures(baseURL, session.connectionUid);
            const list = Array.isArray(data) ? data : (data?.features || data?.data || []);
            const feature = list.find((f) => f.name === featureName);
            if (feature) {
              await setFeature(baseURL, session.connectionUid, feature.uid, true);
            }
            return computer.ip;
          } catch { /* try next key */ }
        }
        throw new Error(`Failed: ${computer.ip}`);
      })
    );

    setBroadcastLoading(false);
    exitSelectMode();

    const failed = results.filter((r) => r.status === 'rejected').length;
    if (failed > 0) {
      // Simple feedback — could use a snackbar
      console.warn(`Broadcast: ${failed} failed out of ${selectedCount}`);
    }
  };

  const s = styles(theme);

  return (
    <View style={s.container}>
      {/* AppBar — changes in select mode */}
      {selectMode ? (
        <Appbar.Header style={s.appbar}>
          <Appbar.Action icon="close" onPress={exitSelectMode} iconColor={theme.colors.onSurface} />
          <Appbar.Content
            title={`${selectedCount} selected`}
            titleStyle={s.appbarTitle}
          />
          {selectedCount === 1 && (
            <Appbar.Action
              icon="delete-outline"
              iconColor={theme.colors.error}
              onPress={() => {
                setDeleteTarget(selectedComputers[0]);
                exitSelectMode();
              }}
            />
          )}
          <Appbar.Action
            icon="lightning-bolt"
            onPress={openBroadcastDialog}
            iconColor={theme.colors.primary}
          />
        </Appbar.Header>
      ) : (
        <Appbar.Header style={s.appbar}>
          <Appbar.Content title="Monitoring Center" titleStyle={s.appbarTitle} />
          <Appbar.Action
            icon="key"
            onPress={() => navigation.navigate('KeyManager')}
            iconColor={theme.colors.onSurfaceVariant}
          />
        </Appbar.Header>
      )}

      {computers.length === 0 ? (
        <View style={s.empty}>
          <Text style={s.emptyIcon}>🖥️</Text>
          <Text style={s.emptyTitle}>No computers yet</Text>
          <Text style={s.emptySubtitle}>
            Tap + to add a computer manually{'\n'}or discover Veyon servers on your network
          </Text>
        </View>
      ) : (
        <FlatList
          data={computers}
          keyExtractor={(item) => item.id}
          numColumns={4}
          contentContainerStyle={s.grid}
          renderItem={({ item }) => (
            <View style={[s.cardWrapper, selectMode && selected[item.id] && s.cardSelected]}>
              {selectMode && (
                <View style={s.checkboxOverlay}>
                  <Checkbox
                    status={selected[item.id] ? 'checked' : 'unchecked'}
                    color={theme.colors.primary}
                    onPress={() => handlePress({ computer: item })}
                  />
                </View>
              )}
              <ComputerCard
                computer={item}
                onPress={handlePress}
                onLongPress={handleLongPress}
              />
            </View>
          )}
        />
      )}

      {/* Select mode bottom bar */}
      {selectMode && selectedCount > 0 && (
        <View style={s.selectBar}>
          <Button
            mode="contained"
            icon="lightning-bolt"
            onPress={openBroadcastDialog}
            loading={broadcastLoading}
            disabled={broadcastLoading}
            style={s.broadcastButton}
          >
            Send Action to {selectedCount} PC{selectedCount > 1 ? 's' : ''}
          </Button>
        </View>
      )}

      <Portal>
        {/* FAB — hidden in select mode */}
        {!selectMode && (
          <FAB.Group
            open={fabOpen}
            visible
            icon={fabOpen ? 'close' : 'plus'}
            color={theme.colors.onPrimary || '#fff'}
            fabStyle={{ backgroundColor: theme.colors.primary }}
            actions={[
              {
                icon: 'magnify',
                label: 'Discover',
                onPress: () => navigation.navigate('Discovery'),
                style: { backgroundColor: theme.colors.surface },
              },
              {
                icon: 'plus',
                label: 'Add Manually',
                onPress: () => navigation.navigate('AddComputer'),
                style: { backgroundColor: theme.colors.surface },
              },
            ]}
            onStateChange={({ open }) => setFabOpen(open)}
          />
        )}

        {/* Delete dialog */}
        <Dialog
          visible={deleteTarget !== null}
          onDismiss={() => setDeleteTarget(null)}
          style={{ backgroundColor: theme.colors.surface }}
        >
          <Dialog.Title style={{ color: theme.colors.onSurface }}>Remove computer?</Dialog.Title>
          <Dialog.Content>
            <Text style={{ color: theme.colors.onSurfaceVariant }}>
              {deleteTarget?.label || deleteTarget?.ip} will be removed from the list.
            </Text>
          </Dialog.Content>
          <Dialog.Actions>
            <Button onPress={() => setDeleteTarget(null)}>Cancel</Button>
            <Button textColor={theme.colors.error} onPress={handleConfirmDelete}>Remove</Button>
          </Dialog.Actions>
        </Dialog>

        {/* Broadcast action dialog */}
        <Dialog
          visible={broadcastDialogVisible}
          onDismiss={() => setBroadcastDialogVisible(false)}
          style={{ backgroundColor: theme.colors.surface }}
        >
          <Dialog.Title style={{ color: theme.colors.onSurface }}>
            Send Action to {selectedCount} PC{selectedCount > 1 ? 's' : ''}
          </Dialog.Title>
          <Dialog.ScrollArea style={{ maxHeight: 360 }}>
            {broadcastLoading ? (
              <View style={{ padding: 32, alignItems: 'center' }}>
                <ActivityIndicator color={theme.colors.primary} />
                <Text style={{ color: theme.colors.onSurfaceVariant, marginTop: 12 }}>
                  Connecting to computers…
                </Text>
              </View>
            ) : broadcastFeatures.length === 0 ? (
              <View style={{ padding: 24 }}>
                <Text style={{ color: theme.colors.onSurfaceVariant, textAlign: 'center' }}>
                  No common actions found.{'\n'}Make sure all selected computers are reachable.
                </Text>
              </View>
            ) : (
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 8, padding: 8 }}>
                {broadcastFeatures.map((feature) => (
                  <Button
                    key={feature.uid}
                    mode="outlined"
                    compact
                    onPress={() => broadcastAction(feature.name)}
                    style={{ marginBottom: 4 }}
                  >
                    {getFeatureLabel(feature)}
                  </Button>
                ))}
              </View>
            )}
          </Dialog.ScrollArea>
          <Dialog.Actions>
            <Button onPress={() => setBroadcastDialogVisible(false)}>Cancel</Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </View>
  );
};

const styles = (theme) => StyleSheet.create({
  container: { flex: 1, backgroundColor: theme.colors.background },
  appbar: { backgroundColor: theme.colors.surface, elevation: 0, borderBottomWidth: 1, borderBottomColor: theme.colors.outline },
  appbarTitle: { fontSize: 18, fontWeight: '700', color: theme.colors.onSurface },
  grid: { padding: 6, paddingBottom: 100 },
  cardWrapper: { flex: 1, position: 'relative' },
  cardSelected: { opacity: 0.85 },
  checkboxOverlay: {
    position: 'absolute',
    top: 6,
    left: 6,
    zIndex: 10,
    backgroundColor: 'rgba(13,17,23,0.7)',
    borderRadius: 4,
  },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 40 },
  emptyIcon: { fontSize: 48, marginBottom: 16 },
  emptyTitle: { fontSize: 20, fontWeight: '700', color: theme.colors.onSurface, marginBottom: 8 },
  emptySubtitle: { fontSize: 14, color: theme.colors.onSurfaceVariant, textAlign: 'center', lineHeight: 22 },
  selectBar: {
    position: 'absolute',
    bottom: 0, left: 0, right: 0,
    padding: 16,
    backgroundColor: theme.colors.surface,
    borderTopWidth: 1,
    borderTopColor: theme.colors.outline,
  },
  broadcastButton: { borderRadius: 8 },
});

export default HomeScreen;