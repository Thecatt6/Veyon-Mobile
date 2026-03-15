import { requireNativeComponent } from 'react-native';

/**
 * Singleton — registra VeyonSurfaceView una sola volta.
 * Importato sia da NativeSurfaceView che da VeyonVncView.
 */
const VeyonSurfaceViewNative = requireNativeComponent('VeyonSurfaceView');

export default VeyonSurfaceViewNative;