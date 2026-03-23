import React, { useCallback, useState } from 'react';
import { View, FlatList, StyleSheet, Alert, ScrollView } from 'react-native';
import { Text, useTheme, Appbar, Button, Divider, List, IconButton, TextInput, Dialog, Portal } from 'react-native-paper';
import { useFocusEffect } from '@react-navigation/native';
import { pick, isCancel } from '@react-native-documents/picker';
import 'react-native-get-random-values';
import { v4 as uuidv4 } from 'uuid';
import { getAuthKeys, saveAuthKey, deleteAuthKey } from '../controllers/StorageController';

const KeyManagerScreen = ({ navigation }) => {
  const theme = useTheme();
  const [keys, setKeys] = useState([]);
  const [keyNameInput, setKeyNameInput] = useState('');
  const [pasteDialogVisible, setPasteDialogVisible] = useState(false);
  const [pasteKeyName, setPasteKeyName] = useState('');
  const [pasteKeyContent, setPasteKeyContent] = useState('');

  const loadKeys = useCallback(async () => {
    const data = await getAuthKeys();
    setKeys(Object.values(data));
  }, []);

  useFocusEffect(useCallback(() => { loadKeys(); }, [loadKeys]));

  // ── Importa da file usando @react-native-documents/picker con read() ──────
  const handleImportFile = async () => {
    try {
      const [result] = await pick({ allowMultiSelection: false });
      if (!result) return;

      console.log('Picker result:', JSON.stringify(result));

      let content;
      // Prova read() di @react-native-documents/picker se disponibile
      if (typeof read === 'function') {
        try {
          const { text } = await read(result);
          content = text;
          console.log('Read via picker read():', content?.length);
        } catch (readErr) {
          console.warn('picker read() failed:', readErr?.message);
        }
      }

      if (!content) {
        // Fallback: chiedi all'utente di incollare manualmente
        Alert.alert(
          'File reading not supported',
          'Could not read the file automatically. Please use "Paste Key" to paste the key content manually.',
        );
        return;
      }

      const fileName = keyNameInput.trim() || result.name?.replace(/\.[^/.]+$/, '') || 'imported-key';
      await saveAuthKey({ id: uuidv4(), keyName: fileName, privateKey: content.trim() });
      setKeyNameInput('');
      loadKeys();
      Alert.alert('Success', `Key "${fileName}" imported.`);
    } catch (err) {
      if (!isCancel(err)) {
        console.error('Import error:', err);
        Alert.alert('Import Failed', err?.message || 'Unknown error');
      }
    }
  };

  // ── Importa incollando manualmente il contenuto ────────────────────────────
  const handlePasteConfirm = async () => {
    if (!pasteKeyName.trim() || !pasteKeyContent.trim()) return;
    if (!pasteKeyContent.includes('-----BEGIN')) {
      Alert.alert('Invalid Key', 'The content does not look like a PEM private key.');
      return;
    }
    await saveAuthKey({
      id: uuidv4(),
      keyName: pasteKeyName.trim(),
      privateKey: pasteKeyContent.trim(),
    });
    setPasteDialogVisible(false);
    setPasteKeyName('');
    setPasteKeyContent('');
    loadKeys();
    Alert.alert('Success', `Key "${pasteKeyName.trim()}" saved.`);
  };

  const handleDelete = (key) => {
    Alert.alert('Delete Key', `Remove "${key.keyName}"?`, [
      { text: 'Delete', style: 'destructive', onPress: async () => { await deleteAuthKey(key.id); loadKeys(); } },
      { text: 'Cancel', style: 'cancel' },
    ]);
  };

  const s = styles(theme);

  return (
    <View style={s.container}>
      <Appbar.Header style={s.appbar}>
        <Appbar.BackAction onPress={() => navigation.goBack()} iconColor={theme.colors.onSurface} />
        <Appbar.Content title="Auth Keys" titleStyle={s.appbarTitle} />
      </Appbar.Header>

      {keys.length === 0 ? (
        <View style={s.empty}>
          <Text style={s.emptyIcon}>🔑</Text>
          <Text style={s.emptyTitle}>No keys imported</Text>
          <Text style={s.emptySubtitle}>
            Import your Veyon private key{'\n'}to authenticate with servers
          </Text>
        </View>
      ) : (
        <FlatList
          data={keys}
          keyExtractor={(item) => item.id}
          ItemSeparatorComponent={() => <Divider style={{ backgroundColor: theme.colors.outline }} />}
          renderItem={({ item }) => (
            <List.Item
              title={item.keyName}
              titleStyle={{ color: theme.colors.onSurface, fontWeight: '600' }}
              description={`Private Key · ${item.privateKey?.length || 0} chars`}
              descriptionStyle={{ color: theme.colors.onSurfaceVariant }}
              left={() => <List.Icon icon="key-variant" color={theme.colors.primary} />}
              right={() => (
                <IconButton icon="delete-outline" iconColor={theme.colors.error} onPress={() => handleDelete(item)} />
              )}
              style={{ backgroundColor: theme.colors.surface }}
            />
          )}
        />
      )}

      {/* Footer */}
      <View style={s.footer}>
        <TextInput
          label="Key Name (must match Veyon key name)"
          value={keyNameInput}
          onChangeText={setKeyNameInput}
          mode="outlined"
          placeholder="e.g. Test"
          dense
          style={{ marginBottom: 10, backgroundColor: theme.colors.surfaceVariant }}
        />
        <View style={{ flexDirection: 'row', gap: 8 }}>
          <Button
            mode="outlined"
            icon="file-import"
            onPress={handleImportFile}
            style={{ flex: 1 }}
          >
            Import File
          </Button>
          <Button
            mode="contained"
            icon="clipboard-text"
            onPress={() => {
              setPasteKeyName(keyNameInput.trim());
              setPasteKeyContent('');
              setPasteDialogVisible(true);
            }}
            style={{ flex: 1 }}
          >
            Paste Key
          </Button>
        </View>
      </View>

      {/* Dialog incolla chiave */}
      <Portal>
        <Dialog
          visible={pasteDialogVisible}
          onDismiss={() => setPasteDialogVisible(false)}
          style={{ backgroundColor: theme.colors.surface, maxHeight: '85%' }}
        >
          <Dialog.Title style={{ color: theme.colors.onSurface }}>Paste Private Key</Dialog.Title>
          <Dialog.ScrollArea style={{ maxHeight: 400 }}>
            <ScrollView>
              <View style={{ padding: 4 }}>
                <TextInput
                  mode="outlined"
                  label="Key Name"
                  value={pasteKeyName}
                  onChangeText={setPasteKeyName}
                  placeholder="e.g. Test"
                  dense
                  style={{ marginBottom: 12, backgroundColor: theme.colors.surfaceVariant }}
                />
                <TextInput
                  mode="outlined"
                  label="Key Content (PEM)"
                  value={pasteKeyContent}
                  onChangeText={setPasteKeyContent}
                  placeholder="-----BEGIN PRIVATE KEY-----&#10;...&#10;-----END PRIVATE KEY-----"
                  multiline
                  numberOfLines={10}
                  style={{ backgroundColor: theme.colors.surfaceVariant, fontFamily: 'monospace' }}
                  autoCapitalize="none"
                  autoCorrect={false}
                />
                <Text style={{ fontSize: 11, color: theme.colors.onSurfaceVariant, marginTop: 8 }}>
                  Open the key file on PC with Notepad, select all (Ctrl+A), copy (Ctrl+C), then paste here.
                </Text>
              </View>
            </ScrollView>
          </Dialog.ScrollArea>
          <Dialog.Actions>
            <Button onPress={() => setPasteDialogVisible(false)}>Cancel</Button>
            <Button
              mode="contained"
              onPress={handlePasteConfirm}
              disabled={!pasteKeyName.trim() || !pasteKeyContent.trim()}
            >
              Save
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
  appbarTitle: { fontSize: 18, fontWeight: '700', color: theme.colors.onSurface },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 40 },
  emptyIcon: { fontSize: 48, marginBottom: 16 },
  emptyTitle: { fontSize: 18, fontWeight: '700', color: theme.colors.onSurface, marginBottom: 8 },
  emptySubtitle: { fontSize: 14, color: theme.colors.onSurfaceVariant, textAlign: 'center', lineHeight: 22 },
  footer: { padding: 16, backgroundColor: theme.colors.surface, borderTopWidth: 1, borderTopColor: theme.colors.outline },
});

export default KeyManagerScreen;