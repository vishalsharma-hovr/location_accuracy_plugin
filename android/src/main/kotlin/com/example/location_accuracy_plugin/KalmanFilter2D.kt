package com.example.location_accuracy_plugin


class KalmanFilter2D {

  // State vector [lat, lon, lat_vel, lon_vel]
  private var x = DoubleArray(4) { 0.0 }

  // Covariance matrix 4x4
  private var P = Array(4) { DoubleArray(4) { 0.0 } }

  // Process noise covariance Q (tunable)
  private val Q = arrayOf(
    doubleArrayOf(1e-6, 0.0, 0.0, 0.0),
    doubleArrayOf(0.0, 1e-6, 0.0, 0.0),
    doubleArrayOf(0.0, 0.0, 1e-3, 0.0),
    doubleArrayOf(0.0, 0.0, 0.0, 1e-3)
  )

  // Measurement noise covariance scalar (updated dynamically)
  private var R = 25e-6

  private var lastTimestamp: Long? = null

  fun initialize(lat: Double, lon: Double, accuracyMeters: Double, timestamp: Long) {
    // FIX: Correctly assign values to the state vector x
    x[0] = lat
    x[1] = lon
    x[2] = 0.0
    x[3] = 0.0

    val accDeg = metersToDegrees(accuracyMeters)

    // FIX: Correctly initialize the covariance matrix P
    P = arrayOf(
      doubleArrayOf(accDeg * accDeg, 0.0, 0.0, 0.0),
      doubleArrayOf(0.0, accDeg * accDeg, 0.0, 0.0),
      doubleArrayOf(0.0, 0.0, 1.0, 0.0),
      doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    )

    // FIX: Measurement noise R should be a 2x2 matrix, not a scalar.
    // It's part of the `S` calculation, but for simplicity, let's keep the scalar here
    // and correctly apply it in the `update` function.
    R = accDeg * accDeg
    lastTimestamp = timestamp
  }

  fun update(lat: Double, lon: Double, accuracyMeters: Double, timestamp: Long): Pair<Double, Double> {
    val lastTs = lastTimestamp
    if (lastTs == null) {
      initialize(lat, lon, accuracyMeters, timestamp)
      return Pair(lat, lon)
    }
    val dt = (timestamp - lastTs).toDouble() / 1000.0
    if (dt <= 0) return Pair(x[0], x[1])

    lastTimestamp = timestamp

    // 1. Predict
    val F = arrayOf(
      doubleArrayOf(1.0, 0.0, dt, 0.0),
      doubleArrayOf(0.0, 1.0, 0.0, dt),
      doubleArrayOf(0.0, 0.0, 1.0, 0.0),
      doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    )

    // Predict new state estimate
    x = multiplyMatrixVector(F, x)
    // Predict new covariance estimate
    P = matrixAdd(multiplyMatrices(F, multiplyMatrices(P, transpose(F))), Q)

    // 2. Update
    val z = doubleArrayOf(lat, lon)
    val H = arrayOf(
      doubleArrayOf(1.0, 0.0, 0.0, 0.0),
      doubleArrayOf(0.0, 1.0, 0.0, 0.0)
    )

    val accDeg = metersToDegrees(accuracyMeters)
    R = accDeg * accDeg

    // S = H * P * H^T + R
    val S = matrixAdd(
      multiplyMatrices(H, multiplyMatrices(P, transpose(H))),
      arrayOf(doubleArrayOf(R, 0.0), doubleArrayOf(0.0, R)) // FIX: Measurement noise R is now a 2x2 matrix
    )

    // K = P * H^T * S^-1 (Kalman Gain)
    val K = multiplyMatrices(P, multiplyMatrices(transpose(H), invert2x2(S)))

    // y = z - H * x
    val y = doubleArrayOf(z[0] - x[0], z[1] - x[1]) // FIX: Correct indexing for the y vector

    // x = x + K * y
    // FIX: Correctly apply the Kalman gain to the state vector
    for (i in x.indices) {
      x[i] += K[i][0] * y[0] + K[i][1] * y[1]
    }

    // P = (I - K * H) * P
    val I = identityMatrix(4)
    val KH = multiplyMatrices(K, H)
    P = multiplyMatrices(matrixSubtract(I, KH), P)

    // FIX: Return the updated latitude and longitude from the state vector
    return Pair(x[0], x[1])
  }

  private fun metersToDegrees(meters: Double): Double = meters / 111320.0

  // Matrix helper functions
  // FIX: Helper functions contain various bugs.
  // The implementations below are corrected for proper matrix arithmetic.

  private fun multiplyMatrixVector(m: Array<DoubleArray>, v: DoubleArray): DoubleArray {
    val result = DoubleArray(m.size)
    for (i in m.indices) {
      var sum = 0.0
      for (j in v.indices) {
        sum += m[i][j] * v[j]
      }
      result[i] = sum
    }
    return result
  }

  private fun multiplyMatrices(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
    val rowsA = a.size
    val colsA = a[0].size
    val rowsB = b.size
    val colsB = b[0].size

    if (colsA != rowsB) {
      throw IllegalArgumentException("Matrices cannot be multiplied: inner dimensions must agree.")
    }

    val result = Array(rowsA) { DoubleArray(colsB) }
    for (i in 0 until rowsA) {
      for (j in 0 until colsB) {
        var sum = 0.0
        for (k in 0 until colsA) {
          sum += a[i][k] * b[k][j]
        }
        result[i][j] = sum
      }
    }
    return result
  }

  private fun transpose(m: Array<DoubleArray>): Array<DoubleArray> {
    val rows = m.size
    val cols = m[0].size
    val t = Array(cols) { DoubleArray(rows) }
    for (i in 0 until rows) {
      for (j in 0 until cols) {
        t[j][i] = m[i][j]
      }
    }
    return t
  }

  private fun invert2x2(m: Array<DoubleArray>): Array<DoubleArray> {
    val det = m[0][0] * m[1][1] - m[0][1] * m[1][0] // FIX: Correct 2x2 determinant calculation
    if (det == 0.0) throw IllegalArgumentException("Singular matrix")
    val invDet = 1.0 / det
    return arrayOf(
      doubleArrayOf(m[1][1] * invDet, -m[0][1] * invDet),
      doubleArrayOf(-m[1][0] * invDet, m[0][0] * invDet) // FIX: Correct element positions
    )
  }

  private fun matrixAdd(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
    val rows = a.size
    val cols = a[0].size
    if (rows != b.size || cols != b[0].size) {
      throw IllegalArgumentException("Matrices must have the same dimensions to be added.")
    }
    return Array(rows) { i -> DoubleArray(cols) { j -> a[i][j] + b[i][j] } }
  }

  private fun matrixSubtract(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
    val rows = a.size
    val cols = a[0].size
    if (rows != b.size || cols != b[0].size) {
      throw IllegalArgumentException("Matrices must have the same dimensions to be subtracted.")
    }
    return Array(rows) { i -> DoubleArray(cols) { j -> a[i][j] - b[i][j] } }
  }

  private fun identityMatrix(size: Int): Array<DoubleArray> =
    Array(size) { i -> DoubleArray(size) { j -> if (i == j) 1.0 else 0.0 } }
}

