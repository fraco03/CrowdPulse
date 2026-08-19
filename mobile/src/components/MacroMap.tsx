import React from 'react';
import { View, Text, StyleSheet, Dimensions } from 'react-native';
import { LocationData, PeerLocationEvent } from '../types';

interface MacroMapProps {
  userLocation: LocationData | null;
  peerLocation: PeerLocationEvent | null;
  roomCode: string;
}

const { width } = Dimensions.get('window');

function calculateHaversine(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const R = 6371000; // meters
  const toRad = (deg: number) => (deg * Math.PI) / 180.0;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) *
      Math.cos(toRad(lat2)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

export const MacroMap: React.FC<MacroMapProps> = ({
  userLocation,
  peerLocation,
  roomCode,
}) => {
  let distanceMeters: number | null = null;
  if (userLocation && peerLocation) {
    distanceMeters = calculateHaversine(
      userLocation.latitude,
      userLocation.longitude,
      peerLocation.latitude,
      peerLocation.longitude
    );
  }

  const p2pProgress = distanceMeters
    ? Math.min(100, Math.max(0, ((200 - distanceMeters) / 150) * 100))
    : 0;

  return (
    <View style={styles.container}>
      {/* Header Info */}
      <View style={styles.roomHeader}>
        <Text style={styles.roomLabel}>ROOM CODE</Text>
        <Text style={styles.roomCodeText}>{roomCode}</Text>
      </View>

      {/* Vector Visualization Canvas */}
      <View style={styles.mapCanvas}>
        <View style={styles.gridOverlay}>
          <View style={styles.gridLineH} />
          <View style={styles.gridLineV} />
        </View>

        {/* User Marker */}
        <View style={[styles.marker, styles.userMarker]}>
          <View style={styles.userDot} />
          <Text style={styles.markerLabel}>YOU</Text>
        </View>

        {/* Peer Marker */}
        {peerLocation ? (
          <View style={[styles.marker, styles.peerMarker]}>
            <View style={styles.peerDot} />
            <Text style={styles.markerLabel}>FRIEND</Text>
          </View>
        ) : (
          <View style={styles.waitingContainer}>
            <Text style={styles.waitingText}>Waiting for friend to join...</Text>
          </View>
        )}
      </View>

      {/* Geodesic Distance Panel */}
      <View style={styles.statsCard}>
        <Text style={styles.statsLabel}>MACRO GEODESIC DISTANCE</Text>
        <Text style={styles.statsValue}>
          {distanceMeters !== null
            ? distanceMeters > 1000
              ? `${(distanceMeters / 1000).toFixed(2)} km`
              : `${Math.round(distanceMeters)} m`
            : '--'}
        </Text>

        {/* Proximity Progress towards 50m trigger */}
        <View style={styles.progressBarWrapper}>
          <View style={[styles.progressBarFill, { width: `${p2pProgress}%` }]} />
        </View>
        <Text style={styles.progressSubtext}>
          {distanceMeters !== null && distanceMeters <= 50
            ? '🚀 Within 50m threshold! Switching to P2P...'
            : 'Get within 50m to trigger Wi-Fi Aware offline micro-tracking'}
        </Text>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    padding: 16,
    alignItems: 'center',
  },
  roomHeader: {
    alignItems: 'center',
    marginBottom: 16,
  },
  roomLabel: {
    color: '#64748B',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1.5,
  },
  roomCodeText: {
    color: '#38BDF8',
    fontSize: 28,
    fontWeight: '900',
    letterSpacing: 4,
  },
  mapCanvas: {
    width: width - 32,
    height: 260,
    backgroundColor: '#0F172A',
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#1E293B',
    overflow: 'hidden',
    position: 'relative',
    justifyContent: 'center',
    alignItems: 'center',
  },
  gridOverlay: {
    position: 'absolute',
    width: '100%',
    height: '100%',
  },
  gridLineH: {
    position: 'absolute',
    top: '50%',
    width: '100%',
    height: 1,
    backgroundColor: '#33415540',
  },
  gridLineV: {
    position: 'absolute',
    left: '50%',
    height: '100%',
    width: 1,
    backgroundColor: '#33415540',
  },
  marker: {
    position: 'absolute',
    alignItems: 'center',
  },
  userMarker: {
    left: '35%',
    top: '60%',
  },
  peerMarker: {
    right: '30%',
    top: '25%',
  },
  userDot: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: '#38BDF8',
    borderWidth: 3,
    borderColor: '#FFFFFF',
  },
  peerDot: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: '#F43F5E',
    borderWidth: 3,
    borderColor: '#FFFFFF',
  },
  markerLabel: {
    color: '#F8FAFC',
    fontSize: 10,
    fontWeight: '800',
    marginTop: 4,
  },
  waitingContainer: {
    backgroundColor: '#1E293B80',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 12,
  },
  waitingText: {
    color: '#94A3B8',
    fontSize: 13,
  },
  statsCard: {
    width: '100%',
    backgroundColor: '#1E293B',
    borderRadius: 16,
    padding: 20,
    marginTop: 16,
    alignItems: 'center',
  },
  statsLabel: {
    color: '#94A3B8',
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1,
  },
  statsValue: {
    color: '#F8FAFC',
    fontSize: 36,
    fontWeight: '900',
    marginTop: 4,
  },
  progressBarWrapper: {
    width: '100%',
    height: 8,
    backgroundColor: '#0F172A',
    borderRadius: 4,
    overflow: 'hidden',
    marginTop: 14,
  },
  progressBarFill: {
    height: '100%',
    backgroundColor: '#38BDF8',
  },
  progressSubtext: {
    color: '#64748B',
    fontSize: 11,
    marginTop: 8,
    textAlign: 'center',
  },
});
