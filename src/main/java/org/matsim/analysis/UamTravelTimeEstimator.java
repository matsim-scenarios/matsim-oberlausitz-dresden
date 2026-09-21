package org.matsim.analysis;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.estimator.UamInfrastructure;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Estimates the travel time of a uam trip. The uam is imitated by drt, therefore the routing is done on the drt
 * subnetwork, which is prepared by {@link UamInfrastructure#prepareDresdenOberlaustizUamNetwork}: it consists of the
 * ground network (i.e. the car links within the service area, on which drt is added as an allowed mode) and the
 * flying links (i.e. the links which connect the vertiports and on which drt is the only allowed mode).
 */
public class UamTravelTimeEstimator extends NetworkModeTravelTimeEstimator {

	private final Set<Id<Link>> flyingLinks;

	/**
	 * Travel time components of one uam trip. All values are in seconds. Waiting time for the uam vehicle is not
	 * included (it is estimated by the drt estimator during the simulation).
	 *
	 * @param accessWalkTime   walk time from the origin activity to the link on which the uam trip starts
	 * @param groundAccessTime driving time from the start link to the first flying link
	 * @param flyingTime       travel time on the flying links (if the flight consists of several flying links, the
	 *                         ground legs in between are included here as well)
	 * @param groundEgressTime driving time from the last flying link to the end link
	 * @param egressWalkTime   walk time from the link on which the uam trip ends to the destination activity
	 */
	public record UamTravelTime(double accessWalkTime, double groundAccessTime, double flyingTime,
								double groundEgressTime, double egressWalkTime) {
		public double totalTravelTime() {
			return accessWalkTime + groundAccessTime + flyingTime + groundEgressTime + egressWalkTime;
		}

		/**
		 * @return whether the trip actually uses a flying link. If not, the whole ride is reported as ground access
		 * time, i.e. driving on the ground was faster than flying.
		 */
		public boolean isFlying() {
			return flyingTime > 0.;
		}
	}

	/**
	 * @param network the network including the uam links, i.e.
	 *                {@link UamInfrastructure#prepareDresdenOberlaustizUamNetwork} must have been applied before
	 */
	public UamTravelTimeEstimator(Network network, Config config) {
		this(network, config, new FreeSpeedTravelTime());
	}

	/**
	 * @param network    the network including the uam links, i.e.
	 *                   {@link UamInfrastructure#prepareDresdenOberlaustizUamNetwork} must have been applied before
	 * @param travelTime travel times to be used for the routing, e.g. free speed travel times or travel times
	 *                   derived from the events of a simulation run
	 */
	public UamTravelTimeEstimator(Network network, Config config, TravelTime travelTime) {
		super(network, TransportMode.drt, travelTime, config);

		// the flying links have to be identified on the full network: on the drt subnetwork drt is the only allowed
		// mode on every link, as the network filter reduces the allowed modes to the filtered mode
		this.flyingLinks = new HashSet<>();
		for (Link link : network.getLinks().values()) {
			if (link.getAllowedModes().size() == 1 && link.getAllowedModes().contains(TransportMode.drt)) {
				this.flyingLinks.add(link.getId());
			}
		}
	}

	/**
	 * Builds the estimator from a MATSim config and prepares the uam network. The network file of the config can be
	 * overwritten (pass null to keep the value from the config).
	 */
	public static UamTravelTimeEstimator create(String configPath, @Nullable String networkFile, String serviceAreaShp) {
		Config config = ConfigUtils.loadConfig(configPath);
		Network network = NetworkUtils.readNetwork(networkFile != null ? networkFile : config.network().getInputFile());
		UamInfrastructure.prepareDresdenOberlaustizUamNetwork(network, serviceAreaShp);
		return new UamTravelTimeEstimator(network, config);
	}

	/**
	 * @return the travel time components of the uam trip, or null if there is no uam connection
	 */
	@Nullable
	public UamTravelTime estimate(Activity fromActivity, Activity toActivity, double departureTime) {
		Link fromLink = decideOnLink(fromActivity);
		Link toLink = decideOnLink(toActivity);

		double accessWalkTime = calcWalkTime(fromActivity.getCoord(), fromLink);
		double egressWalkTime = calcWalkTime(toActivity.getCoord(), toLink);

		if (fromLink == toLink) {
			return new UamTravelTime(accessWalkTime, 0., 0., 0., egressWalkTime);
		}

		double now = departureTime + accessWalkTime;
		LeastCostPathCalculator.Path path = calcPath(fromLink, toLink, now);
		if (path == null) {
			return null;
		}

		double groundAccessTime = 0.;
		double flyingTime = 0.;
		double groundEgressTime = 0.;
		boolean flying = false;

		for (Link link : path.links) {
			double linkTravelTime = this.travelTime.getLinkTravelTime(link, now, null, null);
			now += linkTravelTime;

			if (this.flyingLinks.contains(link.getId())) {
				if (flying) {
					// a ground leg between two flights (e.g. a transfer between two vertiports): count it as flying,
					// as it is neither ground access nor ground egress
					flyingTime += groundEgressTime;
				}
				flyingTime += linkTravelTime;
				groundEgressTime = 0.;
				flying = true;
			} else if (flying) {
				groundEgressTime += linkTravelTime;
			} else {
				groundAccessTime += linkTravelTime;
			}
		}

		return new UamTravelTime(accessWalkTime, groundAccessTime, flyingTime, groundEgressTime, egressWalkTime);
	}

	/**
	 * @return the travel time components of the uam trip, or null if there is no uam connection
	 */
	@Nullable
	public UamTravelTime estimate(Coord fromCoord, Coord toCoord, double departureTime) {
		return estimate(PopulationUtils.createActivityFromCoord("dummy", fromCoord),
			PopulationUtils.createActivityFromCoord("dummy", toCoord), departureTime);
	}
}
