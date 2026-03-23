import { NativeModules } from 'react-native';

const { FileReaderModule } = NativeModules;

/**
 * Legge un file da un URI content:// o file:// su Android.
 * Usa il modulo nativo FileReaderModule che usa ContentResolver.
 */
export async function readFileFromUri(uri) {
  if (!FileReaderModule) {
    throw new Error('FileReaderModule native module not found');
  }
  return await FileReaderModule.readContentUri(uri);
}