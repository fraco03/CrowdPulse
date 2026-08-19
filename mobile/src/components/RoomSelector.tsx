import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';

interface RoomSelectorProps {
  onJoinRoom: (serverUrl: string, roomCode: string, peerId: string) => void;
  isLoading: boolean;
}

export const RoomSelector: React.FC<RoomSelectorProps> = ({
  onJoinRoom,
  isLoading,
}) => {
  const [serverUrl, setServerUrl] = useState('ws://10.0.2.2:8000/ws');
  const [roomCode, setRoomCode] = useState('');
  const [peerId, setPeerId] = useState(
    `user_${Math.floor(1000 + Math.random() * 9000)}`
  );

  const generateRandomCode = () => {
    const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let code = '';
    for (let i = 0; i < 6; i++) {
      code += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    setRoomCode(code);
  };

  const handleConnect = () => {
    if (!roomCode.trim()) {
      return;
    }
    onJoinRoom(serverUrl, roomCode.trim().toUpperCase(), peerId.trim());
  };

  return (
    <View style={styles.container}>
      <View style={styles.card}>
        <Text style={styles.title}>CROWDPULSE</Text>
        <Text style={styles.subtitle}>
          Off-Grid Dual-Stage Peer Localization
        </Text>

        {/* Room Code Input */}
        <View style={styles.inputGroup}>
          <Text style={styles.label}>ROOM CODE</Text>
          <View style={styles.roomRow}>
            <TextInput
              style={[styles.input, styles.roomInput]}
              placeholder="e.g. FEST24"
              placeholderTextColor="#475569"
              value={roomCode}
              onChangeText={setRoomCode}
              autoCapitalize="characters"
              maxLength={6}
            />
            <TouchableOpacity
              style={styles.randomBtn}
              onPress={generateRandomCode}
            >
              <Text style={styles.randomBtnText}>🎲 GEN</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Peer Handle Input */}
        <View style={styles.inputGroup}>
          <Text style={styles.label}>YOUR HANDLE</Text>
          <TextInput
            style={styles.input}
            placeholder="e.g. Alice"
            placeholderTextColor="#475569"
            value={peerId}
            onChangeText={setPeerId}
          />
        </View>

        {/* Server WS URL */}
        <View style={styles.inputGroup}>
          <Text style={styles.label}>SERVER WEBSOCKET URL</Text>
          <TextInput
            style={styles.input}
            placeholder="ws://..."
            placeholderTextColor="#475569"
            value={serverUrl}
            onChangeText={setServerUrl}
            autoCapitalize="none"
          />
        </View>

        {/* Join Button */}
        <TouchableOpacity
          style={[
            styles.joinButton,
            (!roomCode.trim() || isLoading) && styles.joinButtonDisabled,
          ]}
          onPress={handleConnect}
          disabled={!roomCode.trim() || isLoading}
        >
          {isLoading ? (
            <ActivityIndicator color="#0F172A" />
          ) : (
            <Text style={styles.joinButtonText}>ENTER RADAR ROOM</Text>
          )}
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    padding: 20,
    backgroundColor: '#050B14',
  },
  card: {
    backgroundColor: '#0F172A',
    borderRadius: 24,
    padding: 24,
    borderWidth: 1,
    borderColor: '#1E293B',
  },
  title: {
    fontSize: 28,
    fontWeight: '900',
    color: '#00E5FF',
    letterSpacing: 2,
    textAlign: 'center',
  },
  subtitle: {
    fontSize: 13,
    color: '#94A3B8',
    textAlign: 'center',
    marginTop: 4,
    marginBottom: 28,
  },
  inputGroup: {
    marginBottom: 18,
  },
  label: {
    fontSize: 11,
    fontWeight: '700',
    color: '#64748B',
    letterSpacing: 1,
    marginBottom: 6,
  },
  input: {
    backgroundColor: '#1E293B',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    color: '#F8FAFC',
    fontSize: 15,
    borderWidth: 1,
    borderColor: '#334155',
  },
  roomRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  roomInput: {
    flex: 1,
    fontSize: 18,
    fontWeight: '800',
    letterSpacing: 3,
    marginRight: 10,
  },
  randomBtn: {
    backgroundColor: '#1E293B',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderWidth: 1,
    borderColor: '#00E5FF40',
  },
  randomBtnText: {
    color: '#00E5FF',
    fontWeight: '700',
    fontSize: 13,
  },
  joinButton: {
    backgroundColor: '#00E5FF',
    borderRadius: 14,
    paddingVertical: 16,
    alignItems: 'center',
    marginTop: 12,
  },
  joinButtonDisabled: {
    opacity: 0.5,
  },
  joinButtonText: {
    color: '#050B14',
    fontSize: 15,
    fontWeight: '900',
    letterSpacing: 1.5,
  },
});
