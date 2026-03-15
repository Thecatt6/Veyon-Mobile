import React from 'react';
import { StatusBar } from 'react-native';
import { PaperProvider } from 'react-native-paper';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import theme from './src/theme';
import HomeScreen from './src/screens/HomeScreen';
import AddComputerScreen from './src/screens/AddComputerScreen';
import DiscoveryScreen from './src/screens/DiscoveryScreen';
import KeyManagerScreen from './src/screens/KeyManagerScreen';
import ComputerDetailScreen from './src/screens/ComputerDetailScreen';
import SettingsScreen from './src/screens/SettingsScreen';

const Stack = createNativeStackNavigator();

export default function App() {
  return (
    <PaperProvider theme={theme}>
      <StatusBar barStyle="light-content" backgroundColor={theme.colors.surface} />
      <NavigationContainer>
        <Stack.Navigator screenOptions={{ headerShown: false }}>
          <Stack.Screen name="Home" component={HomeScreen} />
          <Stack.Screen name="AddComputer" component={AddComputerScreen} />
          <Stack.Screen name="Discovery" component={DiscoveryScreen} />
          <Stack.Screen name="KeyManager" component={KeyManagerScreen} />
          <Stack.Screen name="ComputerDetail" component={ComputerDetailScreen} />
          <Stack.Screen name="Settings" component={SettingsScreen} />
        </Stack.Navigator>
      </NavigationContainer>
    </PaperProvider>
  );
}