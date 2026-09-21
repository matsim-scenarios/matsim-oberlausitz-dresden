package org.matsim.analysis;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.geometry.CoordUtils;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Base class for the network based travel time estimators (car, uam/drt). It routes on the subnetwork of the given
 * mode and determines the access/egress link and the (teleported) walk time to/from that link in the same way as
 * MATSim does during the simulation (see NetworkRoutingInclAccessEgressModule and MultimodalLinkChooserDefaultImpl).
 */
abstract class NetworkModeTravelTimeEstimator {

	protected final Network subNetwork;
	protected final TravelTime travelTime;

	private final LeastCostPathCalculator leastCostPathCalculator;
	private final double walkSpeed;
	private final double beelineDistanceFactor;

	protected NetworkModeTravelTimeEstimator(Network network, String mode, TravelTime travelTime, Config config) {
		this.subNetwork = NetworkUtils.createNetwork();
		new TransportModeNetworkFilter(network).filter(this.subNetwork, Set.of(mode));

		this.travelTime = travelTime;
		this.leastCostPathCalculator = new SpeedyALTFactory()
			.createPathCalculator(this.subNetwork, new OnlyTimeDependentTravelDisutility(travelTime), travelTime);

		RoutingConfigGroup.TeleportedModeParams walkParams = config.routing().getModeRoutingParams().get(TransportMode.walk);
		this.walkSpeed = walkParams.getTeleportedModeSpeed();
		this.beelineDistanceFactor = walkParams.getBeelineDistanceFactor();
	}

	/**
	 * The link of the mode specific subnetwork on which the trip starts / ends: the link of the activity, if that link
	 * belongs to the subnetwork, otherwise the nearest link of the subnetwork.
	 */
	protected Link decideOnLink(Activity activity) {
		Link link = activity.getLinkId() == null ? null : this.subNetwork.getLinks().get(activity.getLinkId());
		if (link == null) {
			link = NetworkUtils.getNearestLink(this.subNetwork, activity.getCoord());
		}
		return link;
	}

	/**
	 * Teleported walk time between the given coordinate and the nearest point on the given link.
	 */
	protected double calcWalkTime(Coord coord, Link link) {
		double beelineDistance = CoordUtils.calcEuclideanDistance(coord, NetworkUtils.findNearestPointOnLink(coord, link));
		return beelineDistance * this.beelineDistanceFactor / this.walkSpeed;
	}

	/**
	 * Least (travel) cost path from the end of the "from" link to the beginning of the "to" link, i.e. neither the
	 * "from" link nor the "to" link are part of the path. This is the same convention as the one used for the main
	 * mode leg in MATSim. Returns null if both activities are on the same link or if no path can be found.
	 */
	@Nullable
	protected LeastCostPathCalculator.Path calcPath(Link fromLink, Link toLink, double departureTime) {
		if (fromLink == toLink) {
			return null;
		}
		return this.leastCostPathCalculator.calcLeastCostPath(fromLink.getToNode(), toLink.getFromNode(), departureTime, null, null);
	}
}
