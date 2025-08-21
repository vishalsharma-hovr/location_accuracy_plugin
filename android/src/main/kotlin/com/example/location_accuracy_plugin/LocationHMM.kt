package com.example.location_accuracy_plugin

import java.lang.Math.toRadians
import kotlin.math.*

class LocationHMM(
    private val states: List<State>,
    private val transitionMatrix: Array<DoubleArray>,
    private val sigma: Double // standard deviation for emission likelihood (meters)
) {
    private val numStates = states.size

    fun viterbi(observations: List<Observation>, initialProbs: DoubleArray): List<State> {
        val T = observations.size
        // Handle empty observation list case.
        if (T == 0) return emptyList()

        // DP table for log probabilities. Using log probabilities avoids underflow.
        val dp = Array(T) { DoubleArray(numStates) { Double.NEGATIVE_INFINITY } }
        // Backpointer table to reconstruct the most likely path.
        val backpointer = Array(T) { IntArray(numStates) { -1 } }

        // Initialization Step
        for (s in states.indices) {
            // FIX: Use observations[0] for the first observation
            val emisProbLog = ln(emissionProbability(states[s], observations[0]) + 1e-12)
            dp[0][s] = ln(initialProbs[s] + 1e-12) + emisProbLog
        }

        // Recursion Step
        for (t in 1 until T) {
            for (s in states.indices) {
                val emisProbLog = ln(emissionProbability(states[s], observations[t]) + 1e-12)
                var maxProb = Double.NEGATIVE_INFINITY
                var maxState = -1
                for (prev in states.indices) {
                    val prob = dp[t - 1][prev] + ln(transitionMatrix[prev][s] + 1e-12) + emisProbLog
                    if (prob > maxProb) {
                        maxProb = prob
                        maxState = prev
                    }
                }
                dp[t][s] = maxProb
                backpointer[t][s] = maxState
            }
        }

        // Find best final state
        var bestProb = Double.NEGATIVE_INFINITY
        var bestState = -1
        for (s in states.indices) {
            if (dp[T - 1][s] > bestProb) {
                bestProb = dp[T - 1][s]
                bestState = s
            }
        }

        // Backtrack
        val path = MutableList(T) { states[0] }
        var currState = bestState
        path[T - 1] = states[currState]
        for (t in T - 2 downTo 0) {
            currState = backpointer[t + 1][currState]
            path[t] = states[currState]
        }
        return path
    }

    private fun emissionProbability(state: State, obs: Observation): Double {
        val dist = haversineDistance(state.lat, state.lon, obs.lat, obs.lon)
        // This is a Gaussian probability density function.
        return (1.0 / (sqrt(2 * PI) * sigma)) * exp(-0.5 * (dist / sigma).pow(2))
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth's radius in meters
        val dLat = toRadians(lat2 - lat1)
        val dLon = toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(toRadians(lat1)) * cos(toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}

data class State(val id: Int, val lat: Double, val lon: Double)
data class Observation(val lat: Double, val lon: Double)