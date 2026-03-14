import React, { useState } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import { TextInput, Button, useTheme, Text, Appbar, HelperText } from 'react-native-paper';
import 'react-native-get-random-values';
import { v4 as uuidv4 } from 'uuid';
import { getAuthMethods, buildAuthURL, buildBaseURL, VEYON_PORT } from '../controllers/VeyonAPI';
import { saveComputer } from '../controllers/StorageController';

const AddComputerScreen = ({ navigation }) => {
  const theme = useTheme();
  const [ip, setIp] = useState('');
  const [port, setPort] = useState(String(VEYON_PORT));
  const [label, setLabel] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleAdd = async () => {
    if (!ip.trim()) { setError('Enter an IP address or hostname'); return; }
    setError('');
    setLoading(true);

    try {
      const portNum = parseInt(port, 10) || VEYON_PORT;
      const data = await getAuthMethods(ip.trim(), portNum);

      if (!data.methods?.includes('0c69b301-81b4-42d6-8fae-128cdd113314')) {
        Alert.alert(
          'Key Auth Unavailable',
          'This server does not support key file authentication.',
        );
        setLoading(false);
        return;
      }

      const computer = {
        id: uuidv4(),
        ip: ip.trim(),
        port: portNum,
        authURL: buildAuthURL(ip.trim(), portNum),
        label: label.trim() || ip.trim(),
      };

      await saveComputer(computer);
      navigation.goBack();
    } catch {
      setError('Could not reach the Veyon server. Check the IP and port.');
    } finally {
      setLoading(false);
    }
  };

  const s = styles(theme);

  return (
    <View style={s.container}>
      <Appbar.Header style={s.appbar}>
        <Appbar.BackAction onPress={() => navigation.goBack()} iconColor={theme.colors.onSurface} />
        <Appbar.Content title="Add Computer" titleStyle={s.appbarTitle} />
      </Appbar.Header>

      <ScrollView contentContainerStyle={s.form}>
        <Text style={s.sectionLabel}>SERVER</Text>

        <TextInput
          label="IP Address or Hostname"
          value={ip}
          onChangeText={setIp}
          mode="outlined"
          style={s.input}
          autoCapitalize="none"
          keyboardType="default"
          placeholder="192.168.1.100"
        />

        <TextInput
          label="Port"
          value={port}
          onChangeText={setPort}
          mode="outlined"
          style={s.input}
          keyboardType="number-pad"
          placeholder="11080"
        />

        <TextInput
          label="Label (optional)"
          value={label}
          onChangeText={setLabel}
          mode="outlined"
          style={s.input}
          placeholder="e.g. Classroom PC 01"
        />

        {error ? <HelperText type="error" visible>{error}</HelperText> : null}

        <Button
          mode="contained"
          onPress={handleAdd}
          loading={loading}
          disabled={loading}
          style={s.button}
          contentStyle={s.buttonContent}
        >
          Connect & Add
        </Button>
      </ScrollView>
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
  form: {
    padding: 20,
    paddingTop: 24,
  },
  sectionLabel: {
    fontSize: 11,
    fontWeight: '700',
    color: theme.colors.onSurfaceVariant,
    letterSpacing: 1.2,
    marginBottom: 12,
  },
  input: {
    marginBottom: 14,
    backgroundColor: theme.colors.surfaceVariant,
  },
  button: {
    marginTop: 8,
    borderRadius: 8,
  },
  buttonContent: {
    paddingVertical: 6,
  },
});

export default AddComputerScreen;
