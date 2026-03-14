import { MD3DarkTheme } from 'react-native-paper';

const theme = {
  ...MD3DarkTheme,
  colors: {
    ...MD3DarkTheme.colors,
    // Brand
    primary: '#58A6FF',
    onPrimary: '#003166',
    primaryContainer: '#00469E',
    onPrimaryContainer: '#D6E3FF',
    secondary: '#3FB950',
    onSecondary: '#003910',
    secondaryContainer: '#005319',
    onSecondaryContainer: '#78FF88',
    tertiary: '#C792EA',
    onTertiary: '#380060',
    tertiaryContainer: '#520082',
    onTertiaryContainer: '#F0DBFF',
    // Backgrounds & surfaces
    background: '#0D1117',
    onBackground: '#E6EDF3',
    surface: '#161B22',
    onSurface: '#E6EDF3',
    surfaceVariant: '#21262D',
    onSurfaceVariant: '#8B949E',
    surfaceDisabled: 'rgba(230, 237, 243, 0.12)',
    onSurfaceDisabled: 'rgba(230, 237, 243, 0.38)',
    // Outline
    outline: '#30363D',
    outlineVariant: '#21262D',
    // Error
    error: '#F85149',
    onError: '#690005',
    errorContainer: '#93000A',
    onErrorContainer: '#FFDAD6',
    // Others
    shadow: '#000000',
    scrim: '#000000',
    inverseSurface: '#E6EDF3',
    inverseOnSurface: '#0D1117',
    inversePrimary: '#0060CC',
    elevation: {
      level0: 'transparent',
      level1: '#1C2128',
      level2: '#1C2128',
      level3: '#21262D',
      level4: '#21262D',
      level5: '#262C36',
    },
  },
};

export const extraColors = {
  level0: '#0D1117',
  level1: '#161B22',
  level2: '#1C2128',
  level3: '#21262D',
};

export default theme;