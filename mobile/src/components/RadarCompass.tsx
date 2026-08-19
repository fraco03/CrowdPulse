import React, { useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Animated,
  Dimensions,
  Vibration,
} from 'react-native';
import { MicroNavigationData } from '../types';

interface RadarCompassProps {
  navData: MicroNavigationData | null;
  userAzimuthDeg: number;
}

const { width } = Dimensions.get('window');
const RADAR_SIZE = width * 0.78;

export const RadarCompass: React.FC<RadarCompassProps> = ({
  navData,
  userAzimuthDeg,
}) => {
  const rotationAnim = useRef(new Animated.Value(0)).current;
  const pulseAnim = useRef(new Animated.Value(1)).current;
  const lastVibratedRef = useRef<number>(0);

  const relativeBearing = navData ? navData.relativeBearingDeg : 0;
  const distance = navData ? navData.distance : 0;
  const confidence = navData ? navData.confidence : 0;
  const isConverged = navData ? navData.isConverged : false;

  // Check if user is pointing directly at target (within +/- 15 degrees)
  const isTargetInFront = Math.abs(relativeBearing) <= 15 && distance > 0.5;

  // Pulse animation for active radar
  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1.08,
          duration: 1200,
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 1.0,
          duration: 1200,
          useNativeDriver: true,
        }),
      ])
    );
    loop.start();
    return () => loop.stop();
  }, [pulseAnim]);

  // Smooth rotation animation for bearing needle
  useEffect(() => {
    Animated.spring(rotationAnim, {
      toValue: relativeBearing,
      friction: 7,
      tension: 40,
      useNativeDriver: true,
    }).start();

    // Haptic feedback when aiming directly at peer
    if (isTargetInFront && Date.now() - lastVibratedRef.current > 1500) {
      lastVibratedRef.current = Date.now();
      Vibration.vibrate(40);
    }
  }, [relativeBearing, isTargetInFront]);

  const rotateInterpolate = rotationAnim.interpolate({
    inputRange: [-360, 360],
    outputRange: ['-360deg', '360deg'],
  });

  return (
    <View style={styles.container}>
      {/* Target Status Header */}
      <View style={styles.headerBadge}>
        <View
          style={[
            styles.statusDot,
            { backgroundColor: isTargetInFront ? '#00FF66' : '#00E5FF' },
          ]}
        />
        <Text style={styles.statusText}>
          {isTargetInFront
            ? '🎯 IN LINE OF SIGHT'
            : isConverged
            ? '⚡ WI-FI RTT LOCKED (P2P)'
            : '🔄 WALKING REFINEMENT'}
        </Text>
      </View>

      {/* Radar Dial */}
      <View style={styles.radarContainer}>
        {/* Pulsing Outer Range Ring */}
        <Animated.View
          style={[
            styles.pulseRing,
            {
              transform: [{ scale: pulseAnim }],
              borderColor: isTargetInFront ? '#00FF6640' : '#00E5FF30',
            },
          ]}
        />

        {/* Inner Radar Rings */}
        <View style={styles.radarDial}>
          <View style={[styles.radarGridRing, { width: '80%', height: '80%' }]} />
          <View style={[styles.radarGridRing, { width: '55%', height: '55%' }]} />
          <View style={[styles.radarGridRing, { width: '30%', height: '30%' }]} />

          {/* Crosshairs */}
          <View style={styles.crosshairV} />
          <View style={styles.crosshairH} />

          {/* Rotating Bearing Arrow */}
          <Animated.View
            style={[
              styles.arrowWrapper,
              { transform: [{ rotate: rotateInterpolate }] },
            ]}
          >
            <View
              style={[
                styles.arrowHead,
                {
                  borderBottomColor: isTargetInFront ? '#00FF66' : '#00E5FF',
                },
              ]}
            />
            <View
              style={[
                styles.arrowStem,
                { backgroundColor: isTargetInFront ? '#00FF66' : '#00E5FF' },
              ]}
            />
          </Animated.View>

          {/* Center User Dot */}
          <View style={styles.centerUserDot} />
        </View>
      </View>

      {/* Distance Display */}
      <View style={styles.metricContainer}>
        <Text style={styles.distanceNumber}>
          {distance > 0 ? distance.toFixed(1) : '--'}
        </Text>
        <Text style={styles.distanceUnit}>METERS</Text>
      </View>

      {/* Observability / Confidence Progress */}
      <View style={styles.confidenceContainer}>
        <View style={styles.confidenceBarBg}>
          <View
            style={[
              styles.confidenceBarFill,
              { width: `${Math.round(confidence * 100)}%` },
            ]}
          />
        </View>
        <Text style={styles.confidenceText}>
          Observability Confidence: {Math.round(confidence * 100)}%
          {!isConverged && ' (Take a few steps in an arc)'}
        </Text>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 20,
  },
  headerBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#0F172A',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#334155',
    marginBottom: 24,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 8,
  },
  statusText: {
    color: '#F8FAFC',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1,
  },
  radarContainer: {
    width: RADAR_SIZE,
    height: RADAR_SIZE,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pulseRing: {
    position: 'absolute',
    width: RADAR_SIZE,
    height: RADAR_SIZE,
    borderRadius: RADAR_SIZE / 2,
    borderWidth: 2,
  },
  radarDial: {
    width: RADAR_SIZE * 0.92,
    height: RADAR_SIZE * 0.92,
    borderRadius: (RADAR_SIZE * 0.92) / 2,
    backgroundColor: '#050B14',
    borderWidth: 2,
    borderColor: '#00E5FF60',
    alignItems: 'center',
    justifyContent: 'center',
  },
  radarGridRing: {
    position: 'absolute',
    borderRadius: 999,
    borderWidth: 1,
    borderColor: '#1E293B',
  },
  crosshairV: {
    position: 'absolute',
    width: 1,
    height: '100%',
    backgroundColor: '#1E293B',
  },
  crosshairH: {
    position: 'absolute',
    height: 1,
    width: '100%',
    backgroundColor: '#1E293B',
  },
  arrowWrapper: {
    position: 'absolute',
    width: 40,
    height: RADAR_SIZE * 0.75,
    alignItems: 'center',
  },
  arrowHead: {
    width: 0,
    height: 0,
    borderLeftWidth: 14,
    borderRightWidth: 14,
    borderBottomWidth: 24,
    borderLeftColor: 'transparent',
    borderRightColor: 'transparent',
  },
  arrowStem: {
    width: 4,
    height: RADAR_SIZE * 0.28,
    borderRadius: 2,
  },
  centerUserDot: {
    width: 14,
    height: 14,
    borderRadius: 7,
    backgroundColor: '#FFFFFF',
    borderWidth: 3,
    borderColor: '#00E5FF',
  },
  metricContainer: {
    alignItems: 'center',
    marginTop: 24,
  },
  distanceNumber: {
    fontSize: 54,
    fontWeight: '900',
    color: '#F8FAFC',
    letterSpacing: -1,
  },
  distanceUnit: {
    fontSize: 14,
    fontWeight: '800',
    color: '#00E5FF',
    letterSpacing: 2,
    marginTop: -4,
  },
  confidenceContainer: {
    width: '80%',
    marginTop: 16,
    alignItems: 'center',
  },
  confidenceBarBg: {
    width: '100%',
    height: 6,
    backgroundColor: '#1E293B',
    borderRadius: 3,
    overflow: 'hidden',
  },
  confidenceBarFill: {
    height: '100%',
    backgroundColor: '#00E5FF',
  },
  confidenceText: {
    color: '#94A3B8',
    fontSize: 11,
    marginTop: 6,
    textAlign: 'center',
  },
});
