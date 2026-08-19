/// Kinematic Trilateration Engine
///
/// Resolves peer position and polar angle ambiguity in dense/noisy environments
/// by correlating user step displacements (IMU dead reckoning) with Wi-Fi RTT
/// scalar distance measurements over a sliding window.

use std::collections::VecDeque;

#[derive(Debug, Clone, Copy)]
pub struct StepSample {
    /// Incremental displacement dx along user's reference frame (East/X in meters)
    pub dx: f64,
    /// Incremental displacement dy along user's reference frame (North/Y in meters)
    pub dy: f64,
    /// Wi-Fi RTT measured distance in meters
    pub rtt_distance: f64,
    /// Timestamp of sample in seconds
    pub timestamp: f64,
}

#[derive(Debug, Clone)]
pub struct TrilaterationResult {
    /// Estimated target X position relative to user's current position (meters)
    pub target_rel_x: f64,
    /// Estimated target Y position relative to user's current position (meters)
    pub target_rel_y: f64,
    /// Estimated distance to target (meters)
    pub distance_meters: f64,
    /// Bearing angle to target in radians [-PI, PI], 0 = along +X (East), PI/2 = +Y (North)
    pub bearing_radians: f64,
    /// Bearing angle in degrees [0, 360)
    pub bearing_degrees: f64,
    /// Observability / Confidence score [0.0, 1.0].
    /// High score (>0.7) means sufficient non-collinear motion to resolve 180° ambiguity.
    pub confidence: f64,
    /// True if the system has accumulated enough independent motion to resolve position
    pub is_converged: bool,
}

pub struct KinematicTrilaterator {
    /// Maximum sliding window history size (e.g. 20-30 steps)
    max_history: usize,
    /// History of relative trajectory points and distance samples
    history: VecDeque<(f64, f64, f64)>, // (cum_x, cum_y, rtt_distance)
    /// Cumulative user position in local world frame
    cum_x: f64,
    cum_y: f64,
    /// Minimum required baseline motion (in meters) to attempt trilateration
    min_baseline_meters: f64,
}

impl KinematicTrilaterator {
    pub fn new(max_history: usize, min_baseline_meters: f64) -> Self {
        Self {
            max_history,
            history: VecDeque::with_capacity(max_history),
            cum_x: 0.0,
            cum_y: 0.0,
            min_baseline_meters,
        }
    }

    /// Resets the trajectory history
    pub fn reset(&mut self) {
        self.history.clear();
        self.cum_x = 0.0;
        self.cum_y = 0.0;
    }

    /// Ingests a new step and RTT measurement.
    ///
    /// # Arguments
    /// * `dx` - Displacement in meters along X
    /// * `dy` - Displacement in meters along Y
    /// * `rtt_distance` - Wi-Fi RTT distance in meters
    pub fn add_sample(&mut self, dx: f64, dy: f64, rtt_distance: f64) -> Option<TrilaterationResult> {
        self.cum_x += dx;
        self.cum_y += dy;

        self.history.push_back((self.cum_x, self.cum_y, rtt_distance));
        if self.history.len() > self.max_history {
            self.history.pop_front();
        }

        self.solve()
    }

    /// Solves the linear least-squares system formed by subtracting the initial point:
    ///
    /// (x_k - x_T)^2 + (y_k - y_T)^2 = r_k^2
    /// => 2 x_k x_T + 2 y_k y_T = (x_k^2 + y_k^2) + (r_0^2 - r_k^2)
    pub fn solve(&self) -> Option<TrilaterationResult> {
        let n = self.history.len();
        if n < 4 {
            return None;
        }

        // Anchor at first point in the sliding window
        let (x0, y0, r0) = self.history[0];
        let mut sum_a11 = 0.0;
        let mut sum_a12 = 0.0;
        let mut sum_a22 = 0.0;
        let mut sum_b1 = 0.0;
        let mut sum_b2 = 0.0;

        let mut max_disp_sq = 0.0;

        for i in 1..n {
            let (xi, yi, ri) = self.history[i];
            // Shift coordinates relative to window origin
            let u_i = xi - x0;
            let v_i = yi - y0;

            let disp_sq = u_i * u_i + v_i * v_i;
            if disp_sq > max_disp_sq {
                max_disp_sq = disp_sq;
            }

            let a_i1 = 2.0 * u_i;
            let a_i2 = 2.0 * v_i;
            let b_i = disp_sq + (r0 * r0 - ri * ri);

            sum_a11 += a_i1 * a_i1;
            sum_a12 += a_i1 * a_i2;
            sum_a22 += a_i2 * a_i2;

            sum_b1 += a_i1 * b_i;
            sum_b2 += a_i2 * b_i;
        }

        let max_displacement = max_disp_sq.sqrt();
        if max_displacement < self.min_baseline_meters {
            // Not enough motion yet to resolve triangulation
            return None;
        }

        // Determinant of normal equations matrix (A^T * A)
        let det = sum_a11 * sum_a22 - sum_a12 * sum_a12;

        // Trace of matrix
        let trace = sum_a11 + sum_a22;

        if det <= 1e-6 || trace <= 1e-6 {
            // Collinear motion (walking in a pure straight line doesn't resolve bilateral symmetry)
            return None;
        }

        // Condition / Observability metric: 4 * det / (trace^2) in range (0, 1]
        // 1.0 = perfectly orthogonal trajectories, 0.0 = completely degenerate collinear motion
        let condition = (4.0 * det) / (trace * trace);
        let baseline_factor = (max_displacement / 10.0).min(1.0);
        let confidence = (condition * baseline_factor).clamp(0.0, 1.0);

        // Solve (A^T A) * [x_T; y_T] = A^T b using 2x2 matrix inversion
        let x_t_origin = (sum_a22 * sum_b1 - sum_a12 * sum_b2) / det;
        let y_t_origin = (sum_a11 * sum_b2 - sum_a12 * sum_b1) / det;

        // Convert target position to world frame
        let target_world_x = x0 + x_t_origin;
        let target_world_y = y0 + y_t_origin;

        // Vector from user's CURRENT position (cum_x, cum_y) to target
        let target_rel_x = target_world_x - self.cum_x;
        let target_rel_y = target_world_y - self.cum_y;

        let distance_meters = (target_rel_x * target_rel_x + target_rel_y * target_rel_y).sqrt();
        let bearing_radians = target_rel_y.atan2(target_rel_x);
        let mut bearing_degrees = bearing_radians.to_degrees();
        if bearing_degrees < 0.0 {
            bearing_degrees += 360.0;
        }

        let is_converged = confidence > 0.6 && distance_meters > 0.5;

        Some(TrilaterationResult {
            target_rel_x,
            target_rel_y,
            distance_meters,
            bearing_radians,
            bearing_degrees,
            confidence,
            is_converged,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_kinematic_trilateration_convergence() {
        // Peer Target is located at (10.0, 15.0) relative to user start (0, 0)
        let target_x = 10.0;
        let target_y = 15.0;

        let mut trilat = KinematicTrilaterator::new(30, 2.0);

        // Simulate L-shaped trajectory (e.g. 5 steps East, then 5 steps North)
        // to break collinearity and observe both axes
        let mut cur_x = 0.0;
        let mut cur_y = 0.0;
        let mut last_res = None;

        // Initial sample at (0, 0)
        let dist0 = (target_x * target_x + target_y * target_y).sqrt();
        trilat.add_sample(0.0, 0.0, dist0);

        // Walk 6 steps East (+X, dx = 0.8m)
        for _ in 0..6 {
            cur_x += 0.8;
            let dist = ((target_x - cur_x).powi(2) + (target_y - cur_y).powi(2)).sqrt();
            last_res = trilat.add_sample(0.8, 0.0, dist);
        }

        // Walk 6 steps North (+Y, dy = 0.8m)
        for _ in 0..6 {
            cur_y += 0.8;
            let dist = ((target_x - cur_x).powi(2) + (target_y - cur_y).powi(2)).sqrt();
            last_res = trilat.add_sample(0.0, 0.8, dist);
        }

        assert!(last_res.is_some(), "Trilateration should produce result after non-collinear motion");
        let res = last_res.unwrap();
        assert!(res.is_converged, "Trilateration should converge with L-shaped trajectory");

        let expected_rel_x = target_x - cur_x;
        let expected_rel_y = target_y - cur_y;
        let expected_dist = (expected_rel_x * expected_rel_x + expected_rel_y * expected_rel_y).sqrt();

        assert!((res.target_rel_x - expected_rel_x).abs() < 0.2);
        assert!((res.target_rel_y - expected_rel_y).abs() < 0.2);
        assert!((res.distance_meters - expected_dist).abs() < 0.2);
    }
}
