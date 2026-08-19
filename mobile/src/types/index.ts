export type SystemStage =
  | 'DISCONNECTED'
  | 'MACRO_TRACKING'
  | 'TRANSITIONING_TO_P2P'
  | 'MICRO_P2P';

export interface LocationData {
  latitude: number;
  longitude: number;
  accuracy: number;
  heading?: number;
  speed?: number;
  timestamp: number;
}

export interface PeerLocationEvent {
  latitude: number;
  longitude: number;
  accuracy: number;
  timestamp: number;
}

export interface MicroNavigationData {
  distance: number;             // Distance in meters (Wi-Fi RTT filtered)
  bearingDeg: number;           // Absolute bearing angle [0, 360)
  relativeBearingDeg: number;   // Bearing relative to user facing direction [-180, 180]
  confidence: number;           // Observability / Trilateration confidence [0.0, 1.0]
  isConverged: boolean;         // True if 180-deg ambiguity is resolved
  relativeSpeed: number;        // Relative velocity in m/s
}

export interface OrientationData {
  azimuth: number;  // 0-360 deg
  pitch: number;
  roll: number;
}
