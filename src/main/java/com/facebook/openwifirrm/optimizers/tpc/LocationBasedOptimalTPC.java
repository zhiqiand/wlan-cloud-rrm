/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifirrm.optimizers.tpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.openwifirrm.DeviceConfig;
import com.facebook.openwifirrm.DeviceDataManager;
import com.facebook.openwifirrm.modules.Modeler.DataModel;
import com.facebook.openwifirrm.modules.ModelerUtils;
import com.facebook.openwifirrm.ucentral.UCentralConstants;
import com.facebook.openwifirrm.ucentral.models.State;

/**
 * Location-based optimal TPC algorithm.
 * <p>
 * Assign tx power based on an exhaustive search algorithm given the AP location.
 */
public class LocationBasedOptimalTPC extends TPC {
	private static final Logger logger = LoggerFactory.getLogger(LocationBasedOptimalTPC.class);

	/** The RRM algorithm ID. */
	public static final String ALGORITHM_ID = "location_optimal";

	/** Constructor. */
	public LocationBasedOptimalTPC(
		DataModel model, String zone, DeviceDataManager deviceDataManager
	) {
		super(model, zone, deviceDataManager);
	}

	/**
	 * Iterative way to generate permutations with repetitions.
	 *
	 * @param choices all the choices to be considered
	 * @param n the number of items in a permutation
	 * @return the list of all the combinations
	 */
	protected static List<List<Integer>> getPermutationsWithRepetitions(
		List<Integer> choices,
		int n
	) {
		int choicesSize = choices.size();
		int permutationsSize = (int) Math.pow(choicesSize, n);
		List<List<Integer>> permutations = new ArrayList<>(permutationsSize);
		for (int index = 0; index < n; index++) {
			int choiceIndex = 0;
			int switchIndex = permutationsSize / (int) Math.pow(choicesSize, index + 1);
			for (int pIndex = 0; pIndex < permutationsSize; pIndex++) {
				if (index == 0) {
					permutations.add(new ArrayList<>(n));
				}
				if (pIndex != 0 && pIndex % switchIndex == 0) {
					choiceIndex = (choiceIndex + 1) % choicesSize;
				}
				permutations.get(pIndex).add(choices.get(choiceIndex));
			}
		}
		return permutations;
	}

	/**
	 * Get the optimal tx power for all the participant APs.
	 *
	 * @param sampleSpace the boundary of the space
	 * @param numOfAPs the number of APs
	 * @param apLocX the location x of the APs
	 * @param apLocY the location y of the APs
	 * @param txPowerChoices the tx power options in consideration
	 * @return the tx power of each device
	 */
	public static List<Integer> runLocationBasedOptimalTPC(
		int sampleSpace,
		int numOfAPs,
		List<Double> apLocX,
		List<Double> apLocY,
		List<Integer> txPowerChoices
	) {
		// Get all the permutations with repetition
		List<List<Integer>> permutations =
			getPermutationsWithRepetitions(txPowerChoices, numOfAPs);
		int optimalIndex = permutations.size();
		double optimalMetric = Double.POSITIVE_INFINITY;
		logger.info(
			"Number of tx power combinations: {}",
			permutations.size()
		);

		// Iterate all the combinations and get the metrics
		// Record the combination yielding the minimum metric (optimal)
		for (int pIndex = 0; pIndex < permutations.size(); pIndex++) {
			List<Double> txPowerTemp = permutations
				.get(pIndex)
				.stream()
				.mapToDouble(i->i)
				.boxed()
				.collect(Collectors.toList());
			double[][][] rxPower = ModelerUtils
				.generateRxPower(sampleSpace, numOfAPs, apLocX, apLocY, txPowerTemp);
			double[][] heatMap = ModelerUtils
				.generateHeatMap(sampleSpace, numOfAPs, rxPower);
			double[][] sinr = ModelerUtils
				.generateSinr(sampleSpace, numOfAPs, rxPower);
			double metric = ModelerUtils
				.calculateTPCMetrics(sampleSpace, heatMap, sinr);
			if (metric < optimalMetric) {
				optimalMetric = metric;
				optimalIndex = pIndex;
			}
		}
		if (optimalIndex == permutations.size()) {
			return Collections.nCopies(numOfAPs, 30);
		} else {
			return permutations.get(optimalIndex);
		}
	}

	/**
	 * Calculate new tx powers for the given band.
	 *
	 * @param band       band
	 * @param txPowerMap this map from serial number to band to new tx power
	 *                   (dBm) must be passed in empty, and it is filled in by
	 *                   this method with the new tx powers.
	 */
	private void buildTxPowerMapForBand(
		String band, Map<String, Map<String, Integer>> txPowerMap
	) {
		int numOfAPs = 0;
		int boundary = 100;
		Map<String, Integer> validAPs = new TreeMap<>();
		List<Double> apLocX = new ArrayList<>();
		List<Double> apLocY = new ArrayList<>();
		List<Integer> txPowerChoices = IntStream
			.rangeClosed(MIN_TX_POWER, MAX_TX_POWER)
			.boxed()
			.collect(Collectors.toList());

		// Filter out the invalid APs (e.g., no radio, no location data)
		// Update txPowerChoices, boundary, apLocX, apLocY for the optimization
		for (Map.Entry<String, State> e : model.latestState.entrySet()) {
			String serialNumber = e.getKey();
			State state = e.getValue();

			// Ignore the device if its radio is not active
			if (state.radios == null || state.radios.length == 0) {
				logger.debug(
					"Device {}: No radios found, skipping...", serialNumber
				);
				continue;
			}
			// Ignore the device if the location data is missing
			DeviceConfig deviceCfg = deviceConfigs.get(serialNumber);
			if (deviceCfg == null || deviceCfg.location == null) {
				logger.debug(
					"Device {}: No location data, skipping...", serialNumber
				);
				continue;
			}
			// (TODO) We currently only support 2D map. Need to support 3D later.
			// Generate the required location data for the optimization
			if (
				deviceCfg.location.size() == 2 &&
				deviceCfg.location.get(0) >= 0 &&
				deviceCfg.location.get(1) >= 0
			) {
				apLocX.add(deviceCfg.location.get(0).doubleValue());
				apLocY.add(deviceCfg.location.get(1).doubleValue());
				validAPs.put(serialNumber, numOfAPs);
				numOfAPs++;
			} else {
				logger.error(
					"Device {}: the location data is invalid, skipping...",
					serialNumber
				);
				continue;
			}

			// Update the txPowerChoices for the optimization
			Map<String, List<Integer>> allowedTxPowers = deviceCfg.allowedTxPowers;
			if (allowedTxPowers != null && allowedTxPowers.get(band) != null) {
				txPowerChoices.retainAll(allowedTxPowers.get(band));
			}

			// Update the boundary for the optimization
			if (deviceCfg.boundary != null) {
				boundary = Math.max(boundary, deviceCfg.boundary);
			}
		}

		// Report error if none of the APs has the location data or active
		if (apLocX.isEmpty()) {
			logger.error("No valid APs, missing location data or inactive APs!");
			return;
		}

		// Report error if the boundary is smaller than the given location
		if (
			Collections.max(apLocX).intValue() > boundary ||
			Collections.max(apLocY).intValue() > boundary
		) {
			logger.error("Invalid boundary: {}!", boundary);
			return;
		}

		// Report error if the size of the txPower choices is 0.
		if (txPowerChoices.isEmpty()) {
			logger.error("Invalid txPower choices! It is empty!");
			return;
		}

		// Report error if the number of combinations is too high (>1000).
		if (Math.pow(txPowerChoices.size(), numOfAPs) > 1000) {
			logger.error(
				"Invalid operation: complexity issue!! Number of combinations: {}",
				(int) Math.pow(txPowerChoices.size(), numOfAPs)
			);
			return;
		}

		// Run the optimal TPC algorithm
		List<Integer> txPowerList = LocationBasedOptimalTPC.runLocationBasedOptimalTPC(
			boundary,
			numOfAPs,
			apLocX,
			apLocY,
			txPowerChoices
		);

		// Apply the results from the optimal TPC algorithm to the config
		for (Map.Entry<String, Integer> e : validAPs.entrySet()) {
			String serialNumber = e.getKey();
			int txPower = txPowerList.get(e.getValue());
			txPowerMap.computeIfAbsent(serialNumber, k -> new TreeMap<>())
				.put(band, txPower);

			logger.info(
				"Device {}: Assigning tx power = {}",
				serialNumber,
				txPower
			);
		}
	}

	@Override
	public Map<String, Map<String, Integer>> computeTxPowerMap() {
		Map<String, Map<String, Integer>> txPowerMap = new TreeMap<>();
		for (String band : UCentralConstants.BANDS) {
			buildTxPowerMapForBand(band, txPowerMap);
		}
		return txPowerMap;
	}
}
