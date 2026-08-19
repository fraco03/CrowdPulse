/// 2D Extended Kalman Filter for Sensor Fusion
///
/// Fuses high-frequency IMU dead-reckoning displacements (step vectors)
/// with Wi-Fi RTT scalar ranging and Kinematic Trilateration updates.
/// Provides smooth 60 FPS directional tracking free from multipath jitter.

#[derive(Debug, Clone)]
pub struct KalmanState {
    /// Relative target position X (meters)
    pub px: f64,
    /// Relative target position Y (meters)
    pub py: f64,
    /// Relative velocity Vx (m/s)
    pub vx: f64,
    /// Relative velocity Vy (m/s)
    pub vy: f64,
}

#[derive(Debug, Clone)]
pub struct FilterOutput {
    pub distance_meters: f64,
    pub bearing_radians: f64,
    pub bearing_degrees: f64,
    pub relative_speed: f64,
    pub position_uncertainty: f64,
}

pub struct RelativeKalmanFilter {
    /// State: [px, py, vx, vy]
    state: [f64; 4],
    /// Covariance matrix P (4x4 flattened)
    cov: [f64; 16],
    /// Process noise spectral density Q_pos, Q_vel
    q_pos: f64,
    q_vel: f64,
    /// Measurement noise variance for RTT ranging
    r_rtt: f64,
    /// Base measurement noise variance for Trilateration
    r_pos_base: f64,
    /// Initialization flag
    is_initialized: bool,
}

impl RelativeKalmanFilter {
    pub fn new() -> Self {
        let mut cov = [0.0; 16];
        // Initial high uncertainty
        cov[0] = 25.0;  // P_xx
        cov[5] = 25.0;  // P_yy
        cov[10] = 4.0;  // P_vx_vx
        cov[15] = 4.0;  // P_vy_vy

        Self {
            state: [0.0, 0.0, 0.0, 0.0],
            cov,
            q_pos: 0.05,
            q_vel: 0.2,
            r_rtt: 1.5,       // Typical Wi-Fi RTT ranging noise (1.0 - 2.0m std dev)
            r_pos_base: 2.0,
            is_initialized: false,
        }
    }

    /// Predicts state forward by dt and shifts frame by user displacement (dx_user, dy_user)
    pub fn predict(&mut self, dt: f64, dx_user: f64, dy_user: f64) {
        if !self.is_initialized {
            return;
        }

        // 1. State Extrapolation: x = F*x - B*u
        self.state[0] += self.state[2] * dt - dx_user;
        self.state[1] += self.state[3] * dt - dy_user;
        // Velocities stay constant under random-walk model

        // 2. Covariance Extrapolation: P = F * P * F^T + Q
        // F = [1 0 dt 0; 0 1 0 dt; 0 0 1 0; 0 0 0 1]
        let p = self.cov;
        let mut p_next = [0.0; 16];

        p_next[0] = p[0] + dt * (p[8] + p[2]) + dt * dt * p[10] + self.q_pos * dt;
        p_next[1] = p[1] + dt * (p[9] + p[3]) + dt * dt * p[11];
        p_next[2] = p[2] + dt * p[10];
        p_next[3] = p[3] + dt * p[11];

        p_next[4] = p[4] + dt * (p[12] + p[6]) + dt * dt * p[14];
        p_next[5] = p[5] + dt * (p[13] + p[7]) + dt * dt * p[15] + self.q_pos * dt;
        p_next[6] = p[6] + dt * p[14];
        p_next[7] = p[7] + dt * p[15];

        p_next[8] = p[8] + dt * p[10];
        p_next[9] = p[9] + dt * p[11];
        p_next[10] = p[10] + self.q_vel * dt;
        p_next[11] = p[11];

        p_next[12] = p[12] + dt * p[14];
        p_next[13] = p[13] + dt * p[15];
        p_next[14] = p[14];
        p_next[15] = p[15] + self.q_vel * dt;

        self.cov = p_next;
    }

    /// Extended Kalman Measurement Update for Scalar Wi-Fi RTT Distance
    pub fn update_rtt(&mut self, rtt_distance: f64) {
        if rtt_distance <= 0.0 {
            return;
        }

        if !self.is_initialized {
            // Initialize target in front with measured distance
            self.state[0] = 0.0;
            self.state[1] = rtt_distance;
            self.state[2] = 0.0;
            self.state[3] = 0.0;
            self.is_initialized = true;
            return;
        }

        let px = self.state[0];
        let py = self.state[1];
        let range_est = (px * px + py * py).sqrt();

        if range_est < 0.01 {
            return;
        }

        // Nonlinear measurement function h(x) = sqrt(px^2 + py^2)
        // Jacobian H = [px / range, py / range, 0, 0]
        let h0 = px / range_est;
        let h1 = py / range_est;

        // Innovation y = z - h(x)
        let y = rtt_distance - range_est;

        // S = H * P * H^T + R
        let p = self.cov;
        let s = h0 * (p[0] * h0 + p[1] * h1) + h1 * (p[4] * h0 + p[5] * h1) + self.r_rtt;

        if s.abs() < 1e-9 {
            return;
        }

        // Kalman Gain K = P * H^T / S (4x1 vector)
        let k0 = (p[0] * h0 + p[1] * h1) / s;
        let k1 = (p[4] * h0 + p[5] * h1) / s;
        let k2 = (p[8] * h0 + p[9] * h1) / s;
        let k3 = (p[12] * h0 + p[13] * h1) / s;

        // State update x = x + K * y
        self.state[0] += k0 * y;
        self.state[1] += k1 * y;
        self.state[2] += k2 * y;
        self.state[3] += k3 * y;

        // Covariance update: P = (I - K * H) * P
        let k = [k0, k1, k2, k3];
        let h = [h0, h1, 0.0, 0.0];

        let mut p_new = [0.0; 16];
        for i in 0..4 {
            for j in 0..4 {
                let mut sum = 0.0;
                for m in 0..4 {
                    let i_km = if i == m { 1.0 } else { 0.0 } - k[i] * h[m];
                    sum += i_km * p[m * 4 + j];
                }
                p_new[i * 4 + j] = sum;
            }
        }
        self.cov = p_new;
    }

    /// Linear Measurement Update for 2D Position from Kinematic Trilateration
    pub fn update_trilateration_pos(&mut self, pos_x: f64, pos_y: f64, confidence: f64) {
        if !self.is_initialized {
            self.state[0] = pos_x;
            self.state[1] = pos_y;
            self.state[2] = 0.0;
            self.state[3] = 0.0;
            self.is_initialized = true;
            return;
        }

        let conf = confidence.clamp(0.1, 1.0);
        let r_val = self.r_pos_base / (conf * conf);

        // H = [1 0 0 0; 0 1 0 0]
        let p = self.cov;

        // S = H * P * H^T + R = [P00+R, P01; P10, P11+R]
        let s00 = p[0] + r_val;
        let s01 = p[1];
        let s10 = p[4];
        let s11 = p[5] + r_val;

        let det_s = s00 * s11 - s01 * s10;
        if det_s.abs() < 1e-9 {
            return;
        }

        // S_inv
        let sinv00 = s11 / det_s;
        let sinv01 = -s01 / det_s;
        let sinv10 = -s10 / det_s;
        let sinv11 = s00 / det_s;

        // Innovation y = [pos_x - px, pos_y - py]
        let y0 = pos_x - self.state[0];
        let y1 = pos_y - self.state[1];

        // K = P * H^T * S_inv (4x2 matrix)
        let mut k = [[0.0; 2]; 4];
        for i in 0..4 {
            let p_i0 = p[i * 4 + 0];
            let p_i1 = p[i * 4 + 1];
            k[i][0] = p_i0 * sinv00 + p_i1 * sinv10;
            k[i][1] = p_i0 * sinv01 + p_i1 * sinv11;
        }

        // State update
        for i in 0..4 {
            self.state[i] += k[i][0] * y0 + k[i][1] * y1;
        }

        // Covariance update: P = (I - K*H) * P
        let mut p_new = [0.0; 16];
        for i in 0..4 {
            for j in 0..4 {
                let mut sum = 0.0;
                for m in 0..4 {
                    let kh_im = if m == 0 { k[i][0] } else if m == 1 { k[i][1] } else { 0.0 };
                    let i_km = (if i == m { 1.0 } else { 0.0 }) - kh_im;
                    sum += i_km * p[m * 4 + j];
                }
                p_new[i * 4 + j] = sum;
            }
        }
        self.cov = p_new;
    }

    /// Returns the filtered relative polar coordinates and distance
    pub fn output(&self) -> FilterOutput {
        let px = self.state[0];
        let py = self.state[1];
        let vx = self.state[2];
        let vy = self.state[3];

        let distance_meters = (px * px + py * py).sqrt();
        let bearing_radians = py.atan2(px);
        let mut bearing_degrees = bearing_radians.to_degrees();
        if bearing_degrees < 0.0 {
            bearing_degrees += 360.0;
        }

        let relative_speed = (vx * vx + vy * vy).sqrt();
        let position_uncertainty = (self.cov[0] + self.cov[5]).sqrt();

        FilterOutput {
            distance_meters,
            bearing_radians,
            bearing_degrees,
            relative_speed,
            position_uncertainty,
        }
    }
}
