package org.matsim.estimator;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.estimator.impl.trip_estimation.RideDurationEstimator;
import org.matsim.core.utils.misc.OptionalTime;

import javax.inject.Inject;

public class UamTravelTimeEstimator implements RideDurationEstimator {
	private final Network network;

	public UamTravelTimeEstimator(Network network) {
		this.network = network;
	}

	@Override
	public double getEstimatedRideDuration(Id<Link> fromLinkId, Id<Link> toLinkId, OptionalTime departureTime, double directTripDuration) {



		return 0;
	}
}
