pub mod kalman;
pub mod kinematics;

use kalman::{FilterOutput, RelativeKalmanFilter};
use kinematics::{KinematicTrilaterator, TrilaterationResult};
use serde::{Deserialize, Serialize};
use std::os::raw::c_void;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrackerResult {
    pub filtered_distance: f64,
    pub filtered_bearing_deg: f64,
    pub filtered_bearing_rad: f64,
    pub confidence: f64,
    pub is_converged: bool,
    pub relative_speed: f64,
    pub raw_trilat_x: f64,
    pub raw_trilat_y: f64,
}

pub struct CrowdPulseTracker {
    trilaterator: KinematicTrilaterator,
    kalman_filter: RelativeKalmanFilter,
    last_timestamp: f64,
}

impl CrowdPulseTracker {
    pub fn new() -> Self {
        Self {
            trilaterator: KinematicTrilaterator::new(30, 1.5),
            kalman_filter: RelativeKalmanFilter::new(),
            last_timestamp: 0.0,
        }
    }

    pub fn reset(&mut self) {
        self.trilaterator.reset();
        self.kalman_filter = RelativeKalmanFilter::new();
        self.last_timestamp = 0.0;
    }

    pub fn update(
        &mut self,
        dx_user: f64,
        dy_user: f64,
        rtt_distance: f64,
        timestamp: f64,
    ) -> TrackerResult {
        let dt = if self.last_timestamp > 0.0 && timestamp > self.last_timestamp {
            (timestamp - self.last_timestamp).min(1.0)
        } else {
            0.05
        };
        self.last_timestamp = timestamp;

        // 1. Predict Kalman filter with user motion
        self.kalman_filter.predict(dt, dx_user, dy_user);

        // 2. Kinematic Trilateration update with new step
        let trilat_res = self.trilaterator.add_sample(dx_user, dy_user, rtt_distance);

        // 3. Update Kalman filter with RTT distance
        if rtt_distance > 0.0 {
            self.kalman_filter.update_rtt(rtt_distance);
        }

        // 4. If trilateration produced a valid position, update Kalman filter
        let mut raw_trilat_x = 0.0;
        let mut raw_trilat_y = 0.0;
        let mut confidence = 0.0;
        let mut is_converged = false;

        if let Some(res) = trilat_res {
            raw_trilat_x = res.target_rel_x;
            raw_trilat_y = res.target_rel_y;
            confidence = res.confidence;
            is_converged = res.is_converged;

            if res.is_converged {
                self.kalman_filter.update_trilateration_pos(
                    res.target_rel_x,
                    res.target_rel_y,
                    res.confidence,
                );
            }
        }

        let k_out = self.kalman_filter.output();

        TrackerResult {
            filtered_distance: k_out.distance_meters,
            filtered_bearing_deg: k_out.bearing_degrees,
            filtered_bearing_rad: k_out.bearing_radians,
            confidence,
            is_converged,
            relative_speed: k_out.relative_speed,
            raw_trilat_x,
            raw_trilat_y,
        }
    }
}

// ==========================================
// C FFI Exports
// ==========================================

#[no_mangle]
pub extern "C" fn crowdpulse_tracker_create() -> *mut c_void {
    let tracker = Box::new(CrowdPulseTracker::new());
    Box::into_raw(tracker) as *mut c_void
}

#[no_mangle]
pub extern "C" fn crowdpulse_tracker_destroy(ptr: *mut c_void) {
    if !ptr.is_null() {
        unsafe {
            let _ = Box::from_raw(ptr as *mut CrowdPulseTracker);
        }
    }
}

#[no_mangle]
pub extern "C" fn crowdpulse_tracker_reset(ptr: *mut c_void) {
    if !ptr.is_null() {
        unsafe {
            let tracker = &mut *(ptr as *mut CrowdPulseTracker);
            tracker.reset();
        }
    }
}

#[repr(C)]
pub struct CTrackerResult {
    pub filtered_distance: f64,
    pub filtered_bearing_deg: f64,
    pub filtered_bearing_rad: f64,
    pub confidence: f64,
    pub is_converged: i32,
    pub relative_speed: f64,
}

#[no_mangle]
pub extern "C" fn crowdpulse_tracker_update(
    ptr: *mut c_void,
    dx_user: f64,
    dy_user: f64,
    rtt_distance: f64,
    timestamp: f64,
    out_result: *mut CTrackerResult,
) -> i32 {
    if ptr.is_null() || out_result.is_null() {
        return -1;
    }

    unsafe {
        let tracker = &mut *(ptr as *mut CrowdPulseTracker);
        let res = tracker.update(dx_user, dy_user, rtt_distance, timestamp);
        (*out_result).filtered_distance = res.filtered_distance;
        (*out_result).filtered_bearing_deg = res.filtered_bearing_deg;
        (*out_result).filtered_bearing_rad = res.filtered_bearing_rad;
        (*out_result).confidence = res.confidence;
        (*out_result).is_converged = if res.is_converged { 1 } else { 0 };
        (*out_result).relative_speed = res.relative_speed;
    }

    0
}

// ==========================================
// Android JNI Exports
// Package: com.crowdpulse.nativebridge.NativeMathEngine
// ==========================================

#[cfg(feature = "jni")]
use jni::objects::{JClass, JObject, JString};
#[cfg(feature = "jni")]
use jni::sys::{jboolean, jdouble, jint, jlong};
#[cfg(feature = "jni")]
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_crowdpulse_nativebridge_NativeMathEngine_nativeCreateTracker(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jlong {
    let tracker = Box::new(CrowdPulseTracker::new());
    Box::into_raw(tracker) as jni::sys::jlong
}

#[no_mangle]
pub extern "system" fn Java_com_crowdpulse_nativebridge_NativeMathEngine_nativeDestroyTracker(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    tracker_ptr: jni::sys::jlong,
) {
    if tracker_ptr != 0 {
        unsafe {
            let _ = Box::from_raw(tracker_ptr as *mut CrowdPulseTracker);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crowdpulse_nativebridge_NativeMathEngine_nativeResetTracker(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    tracker_ptr: jni::sys::jlong,
) {
    if tracker_ptr != 0 {
        unsafe {
            let tracker = &mut *(tracker_ptr as *mut CrowdPulseTracker);
            tracker.reset();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_crowdpulse_nativebridge_NativeMathEngine_nativeUpdateTracker(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    tracker_ptr: jni::sys::jlong,
    dx: jni::sys::jdouble,
    dy: jni::sys::jdouble,
    rtt_distance: jni::sys::jdouble,
    timestamp: jni::sys::jdouble,
) -> jni::sys::jstring {
    if tracker_ptr == 0 {
        let empty_json = env.new_string("{}").unwrap();
        return empty_json.into_raw();
    }

    let tracker = unsafe { &mut *(tracker_ptr as *mut CrowdPulseTracker) };
    let res = tracker.update(dx, dy, rtt_distance, timestamp);
    let json_str = serde_json::to_string(&res).unwrap_or_else(|_| "{}".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tracker_end_to_end() {
        let mut tracker = CrowdPulseTracker::new();

        // Target is at (5.0, 12.0) -> distance 13.0m
        let target_x = 5.0;
        let target_y = 12.0;

        let mut user_x = 0.0;
        let mut user_y = 0.0;

        let mut final_res = None;

        // Walk 10 steps East
        for step in 0..10 {
            user_x += 0.7;
            let dist = ((target_x - user_x).powi(2) + (target_y - user_y).powi(2)).sqrt();
            let res = tracker.update(0.7, 0.0, dist, step as f64 * 0.5);
            final_res = Some(res);
        }

        // Walk 10 steps North
        for step in 10..20 {
            user_y += 0.7;
            let dist = ((target_x - user_x).powi(2) + (target_y - user_y).powi(2)).sqrt();
            let res = tracker.update(0.0, 0.7, dist, step as f64 * 0.5);
            final_res = Some(res);
        }

        let res = final_res.unwrap();
        let expected_dx = target_x - user_x;
        let expected_dy = target_y - user_y;
        let expected_dist = (expected_dx * expected_dx + expected_dy * expected_dy).sqrt();

        assert!((res.filtered_distance - expected_dist).abs() < 1.0);
    }
}
