/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifirrm.optimizers.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.facebook.openwifirrm.DeviceDataManager;
import com.facebook.openwifirrm.modules.Modeler.DataModel;
import com.facebook.openwifirrm.optimizers.TestUtils;
import com.facebook.openwifirrm.ucentral.UCentralConstants;

@TestMethodOrder(OrderAnnotation.class)
public class RandomChannelInitializerTest {
	/** Test zone name. */
	private static final String TEST_ZONE = "test-zone";

	@Test
	@Order(1)
	void test1() throws Exception {
		final String band = UCentralConstants.BAND_2G;
		final String deviceA = "aaaaaaaaaaaa";
		final String deviceB = "bbbbbbbbbbbb";
		final int channelWidth = 20;

		DeviceDataManager deviceDataManager = new DeviceDataManager();
		deviceDataManager.setTopology(
			TestUtils.createTopology(TEST_ZONE, deviceA, deviceB)
		);

		// A and B will be assigned to the same channel
		DataModel dataModel = new DataModel();
		dataModel.latestState.put(
			deviceA, TestUtils.createState(6, channelWidth, "ddd")
		);
		dataModel.latestState.put(
			deviceB, TestUtils.createState(11, channelWidth, "eee")
		);
		dataModel.latestDeviceStatus.put(
			deviceA, TestUtils.createDeviceStatus(band, 7)
		);
		dataModel.latestDeviceStatus.put(
			deviceB, TestUtils.createDeviceStatus(band, 8)
		);

		ChannelOptimizer optimizer = new RandomChannelInitializer(
			dataModel, TEST_ZONE, deviceDataManager
		);
		Map<String, Map<String, Integer>> channelMap =
			optimizer.computeChannelMap();

		assertEquals(channelMap.get(deviceA), channelMap.get(deviceB));
	}
}
