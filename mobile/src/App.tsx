import React, { useState, useEffect } from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  View,
  Text,
  TouchableOpacity,
  Alert,
} from 'react-native';
import {
  LocationData,
  MicroNavigationData,
  OrientationData,
  PeerLocationEvent,
  SystemStage,
} from './types';
import { CrowdPulseNative } from './native/CrowdPulseNative';
import { RoomSelector } from './components/RoomSelector';
import { MacroMap } from './components/MacroMap';
import { RadarCompass } from './components/RadarCompass';

export const App: React.FC = () => {
  const [stage, setStage] = useState<SystemStage>('DISCONNECTED');
  const [roomCode, setRoomCode] = useState<string>('');
  const [peerId, setPeerId] = useState<string>('');
  const [otherPeerId, setOtherPeerId] = useState<string | null>(null);

  const [userLocation, setUserLocation] = useState<LocationData | null>(null);
  const [peerLocation, setPeerLocation] = useState<PeerLocationEvent | null>(null);
  const [microNav, setMicroNav] = useState<MicroNavigationData | null>(null);
  const [orientation, setOrientation] = useState<OrientationData>({
    azimuth: 0,
    pitch: 0,
    roll: 0,
  });
  const [isLoading, setIsLoading] = useState<boolean>(false);

  useEffect(() => {
    const subStage = CrowdPulseNative.onStageChanged((newStage) => {
      setStage(newStage);
    });

    const subPeerJoined = CrowdPulseNative.onPeerJoined(({ peerId: joinedId }) => {
      setOtherPeerId(joinedId);
    });

    const subPeerLeft = CrowdPulseNative.onPeerLeft(() => {
      setOtherPeerId(null);
      setPeerLocation(null);
    });

    const subPeerLoc = CrowdPulseNative.onPeerLocation((loc) => {
      setPeerLocation(loc);
    });

    const subMicroNav = CrowdPulseNative.onMicroNavigationUpdate((nav) => {
      setMicroNav(nav);
    });

    const subOri = CrowdPulseNative.onOrientationUpdate((ori) => {
      setOrientation(ori);
    });

    const subErr = CrowdPulseNative.onError(({ error }) => {
      Alert.alert('Connection Notice', error);
      setIsLoading(false);
    });

    return () => {
      subStage.remove();
      subPeerJoined.remove();
      subPeerLeft.remove();
      subPeerLoc.remove();
      subMicroNav.remove();
      subOri.remove();
      subErr.remove();
    };
  }, []);

  const handleJoinRoom = async (
    serverUrl: string,
    targetRoom: string,
    targetPeer: string
  ) => {
    setIsLoading(true);
    setRoomCode(targetRoom);
    setPeerId(targetPeer);

    try {
      await CrowdPulseNative.connectRoom(serverUrl, targetRoom, targetPeer);
      setIsLoading(false);
    } catch (e: any) {
      setIsLoading(false);
      Alert.alert('Error', e.message || 'Failed to connect');
    }
  };

  const handleDisconnect = () => {
    CrowdPulseNative.stopAll();
    setStage('DISCONNECTED');
    setOtherPeerId(null);
    setPeerLocation(null);
    setMicroNav(null);
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#050B14" />

      {stage === 'DISCONNECTED' ? (
        <RoomSelector onJoinRoom={handleJoinRoom} isLoading={isLoading} />
      ) : (
        <View style={styles.activeContainer}>
          {/* Top Bar */}
          <View style={styles.topBar}>
            <View>
              <Text style={styles.stageIndicator}>
                {stage === 'MACRO_TRACKING'
                  ? 'STAGE 1: MACRO GPS'
                  : stage === 'TRANSITIONING_TO_P2P'
                  ? 'HANDOFF: WI-FI AWARE'
                  : 'STAGE 2: MICRO P2P (802.11mc)'}
              </Text>
              <Text style={styles.subTopText}>
                ROOM: {roomCode} {otherPeerId ? `| PEER: ${otherPeerId}` : ''}
              </Text>
            </View>

            <TouchableOpacity
              style={styles.disconnectBtn}
              onPress={handleDisconnect}
            >
              <Text style={styles.disconnectText}>EXIT</Text>
            </TouchableOpacity>
          </View>

          {/* Stage 1: Macro GPS Map View */}
          {stage === 'MACRO_TRACKING' && (
            <MacroMap
              userLocation={userLocation}
              peerLocation={peerLocation}
              roomCode={roomCode}
            />
          )}

          {/* Stage Transitioning HUD */}
          {stage === 'TRANSITIONING_TO_P2P' && (
            <View style={styles.transitionHud}>
              <Text style={styles.transitionTitle}>⚡ HANDOFF TO WI-FI AWARE</Text>
              <Text style={styles.transitionDesc}>
                Distance under 50m threshold. Establishing direct offline
                P2P radio link...
              </Text>
            </View>
          )}

          {/* Stage 2: Micro P2P Radar & Compass */}
          {stage === 'MICRO_P2P' && (
            <RadarCompass
              navData={microNav}
              userAzimuthDeg={orientation.azimuth}
            />
          )}
        </View>
      )}
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#050B14',
  },
  activeContainer: {
    flex: 1,
  },
  topBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 14,
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
    backgroundColor: '#0F172A',
  },
  stageIndicator: {
    color: '#00E5FF',
    fontSize: 13,
    fontWeight: '800',
    letterSpacing: 1,
  },
  subTopText: {
    color: '#64748B',
    fontSize: 11,
    fontWeight: '600',
    marginTop: 2,
  },
  disconnectBtn: {
    backgroundColor: '#EF444420',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#EF444460',
  },
  disconnectText: {
    color: '#EF4444',
    fontSize: 12,
    fontWeight: '800',
  },
  transitionHud: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  transitionTitle: {
    color: '#00E5FF',
    fontSize: 20,
    fontWeight: '900',
    marginBottom: 8,
    textAlign: 'center',
  },
  transitionDesc: {
    color: '#94A3B8',
    fontSize: 14,
    textAlign: 'center',
    lineHeight: 20,
  },
});
