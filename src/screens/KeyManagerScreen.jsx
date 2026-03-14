import React, { useCallback, useState } from 'react';
import { View, FlatList, StyleSheet, Alert } from 'react-native';
import { Text, useTheme, Appbar, Button, Divider, List, IconButton, TextInput } from 'react-native-paper';
import { useFocusEffect } from '@react-navigation/native';
import { pick, isCancel } from '@react-native-documents/picker';
import { readFile, copyFile, unlink, TemporaryDirectoryPath } from '@dr.pogodin/react-native-fs';
import 'react-native-get-random-values';
import { v4 as uuidv4 } from 'uuid';
import { getAuthKeys, saveAuthKey, deleteAuthKey } from '../controllers/StorageController';

const KeyManagerScreen = ({ navigation }) => {
  const theme = useTheme();
  const [keys, setKeys] = useState([]);
  const [keyNameInput, setKeyNameInput] = useState('');

  const loadKeys = useCallback(async () => {
    const data = await getAuthKeys();
    setKeys(Object.values(data));
  }, []);

  useFocusEffect(useCallback(() => { loadKeys(); }, [loadKeys]));

  const handleImport = async () => {
    try {
      const results = await pick({ allowMultiSelection: false });
      const result = results[0];
      if (!result) return;

      let content;
      try {
        content = await readFile(result.uri, 'utf8');
      } catch {
        const tmpPath = `${TemporaryDirectoryPath}/tmp_key_${Date.now()}.pem`;
        await copyFile(result.uri, tmpPath);
        content = await readFile(tmpPath, 'utf8');
        await unlink(tmpPath);
      }

      const fileName = keyNameInput.trim() || result.name?.replace(/\.[^/.]+$/, '') || 'imported-key';

      await saveAuthKey({
        id: uuidv4(),
        keyName: fileName,
        privateKey: content.trim(),
      });

      loadKeys();
    } catch (err) {
      if (!isCancel(err)) {
        console.error('Import error:', err);
        Alert.alert('Import Failed', 'Could not read the key file.');
      }
    }
  };

  const handleDelete = (key) => {
    Alert.alert(
      'Delete Key',
      `Remove "${key.keyName}"?`,
      [
        {
          text: 'Delete',
          style: 'destructive',
          onPress: async () => { await deleteAuthKey(key.id); loadKeys(); },
        },
        { text: 'Cancel', style: 'cancel' },
      ],
    );
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
            Import your Veyon private key file{'\n'}to authenticate with servers
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
              description="Private Key"
              descriptionStyle={{ color: theme.colors.onSurfaceVariant }}
              left={() => (
                <List.Icon icon="key-variant" color={theme.colors.primary} style={{ margin: 0, padding: 0 }} />
              )}
              right={() => (
                <IconButton
                  icon="delete-outline"
                  iconColor={theme.colors.error}
                  onPress={() => handleDelete(item)}
                />
              )}
              style={{ backgroundColor: theme.colors.surface }}
            />
          )}
        />
      )}

      <View style={s.footer}>
        <TextInput
          label="Key Name (must match Veyon key folder)"
          value={keyNameInput}
          onChangeText={setKeyNameInput}
          mode="outlined"
          placeholder="e.g. Test"
          style={{ marginBottom: 10, backgroundColor: theme.colors.surfaceVariant }}
        />
        <Button
          mode="contained"
          icon="file-import"
          onPress={handleImport}
          style={s.importButton}
          contentStyle={s.importButtonContent}
        >
          Import Key File
        </Button>
      </View>
    </View>
  );
};

const styles = (theme) => StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: theme.colors.background,
  },
  appbar: {
    backgroundColor: theme.colors.surface,
    elevation: 0,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.outline,
  },
  appbarTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: theme.colors.onSurface,
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 40,
  },
  emptyIcon: {
    fontSize: 48,
    marginBottom: 16,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: theme.colors.onSurface,
    marginBottom: 8,
  },
  emptySubtitle: {
    fontSize: 14,
    color: theme.colors.onSurfaceVariant,
    textAlign: 'center',
    lineHeight: 22,
  },
  footer: {
    padding: 16,
    backgroundColor: theme.colors.surface,
    borderTopWidth: 1,
    borderTopColor: theme.colors.outline,
  },
  importButton: {
    borderRadius: 8,
  },
  importButtonContent: {
    paddingVertical: 6,
  },
});

export default KeyManagerScreen;