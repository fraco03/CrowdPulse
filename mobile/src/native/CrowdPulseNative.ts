import { NativeModules, NativeEventEmitter, Platform } from 'react-native';
import {
  LocationData,
  MicroNavigationData,
  OrientationData,
  PeerLocationEvent,
  SystemStage,
} from '../types';

const { CrowdPulseModule } = NativeModules;

// Mock emitter if running on web or non-android environments
const eventEmitter = CrowdPulseModule
  ? new NativeEventEmitter(CrowdPulseModule)
  : new NativeEventEmitter();

export const CrowdPulseNative = {
  connectRoom: async (
    serverUrl: string,
    roomCode: string,
    peerId: string
  ): Promise<boolean> => {
    if (!CrowdPulseModule) {
      console.warn('CrowdPulseModule not available, using mock mode.');
      return true;
    }
    return CrowdPulseModule.connectRoom(serverUrl, roomCode, peerId);
  },

  sendLocationUpdate: (location: LocationData) => {
    if (CrowdPulseModule) {
      CrowdPulseModule.sendLocationUpdate(
        location.latitude,
        location.longitude,
        location.accuracy,
        location.heading || 0.0,
        location.speed || 0.0
      );
    }
  },

  startMicroP2P: (role: 'publisher' | 'subscriber', peerId: string) => {
    if (CrowdPulseModule) {
      CrowdPulseModule.startMicroP2P(role, peerId);
    }
  },

  stopAll: () => {
    if (CrowdPulseModule) {
      CrowdPulseModule.stopAll();
    }
  },

  // Event Subscriptions
  onStageChanged: (callback: (stage: SystemStage) => void) => {
    return eventEmitter.addListener('onStageChanged', (event: { stage: SystemStage }) => {
      callback(event.stage);
    });
  },

  onPeerJoined: (callback: (data: { peerId: string; peerCount: number }) => void) => {
    return eventEmitter.addListener('onPeerJoined', callback);
  },

  onPeerLeft: (callback: (data: { peerId: string }) => void) => {
    return eventEmitter.addListener('onPeerLeft', callback);
  },

  onPeerLocation: (callback: (loc: PeerLocationEvent) => void) => {
    return eventEmitter.addListener('onPeerLocation', callback);
  },

  onMicroNavigationUpdate: (callback: (nav: MicroNavigationData) => void) => {
    return eventEmitter.addListener('onMicroNavigationUpdate', callback);
  },

  onOrientationUpdate: (callback: (ori: OrientationData) => void) => {
    return eventEmitter.addListener('onOrientationUpdate', callback);
  },

  onError: (callback: (err: { error: string }) => void) => {
    return eventEmitter.addListener('onError', callback);
  },
};
