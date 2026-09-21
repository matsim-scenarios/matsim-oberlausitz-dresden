package org.matsim.analysis;

import org.matsim.api.core.v01.Coord;
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

import javax.annotation.Nullable;

/**
 * Estimates the travel time of a car trip on the car subnetwork.
 */
public class CarTravelTimeEstimator extends NetworkModeTravelTimeEstimator {

	/**
	 * Travel time components of one car trip. All values are in seconds.
	 *
	 * @param accessTime     walk time from the origin activity to the link on which the car trip starts
	 * @param mainTravelTime driving time on the car network
	 * @param egressTime     walk time from the link on which the car trip ends to the destination activity
	 */
	public record CarTravelTime(double accessTime, double mainTravelTime, double egressTime) {
		public double totalTravelTime() {
			return accessTime + mainTravelTime + egressTime;
		}
	}

	/**
	 * Uses free speed travel times.
	 */
	public CarTravelTimeEstimator(Network network, Config config) {
		this(network, config, new FreeSpeedTravelTime());
	}

	/**
	 * @param travelTime travel times to be used for the routing, e.g. free speed travel times or travel times
	 *                   derived from the events of a simulation run
	 */
	public CarTravelTimeEstimator(Network network, Config config, TravelTime travelTime) {
		super(network, TransportMode.car, travelTime, config);
	}

	/**
	 * Builds the estimator from a MATSim config. The network file of the config can be overwritten (pass null to keep
	 * the value from the config).
	 */
	public static CarTravelTimeEstimator create(String configPath, @Nullable String networkFile) {
		Config config = ConfigUtils.loadConfig(configPath);
		Network network = NetworkUtils.readNetwork(networkFile != null ? networkFile : config.network().getInputFile());
		return new CarTravelTimeEstimator(network, config);
	}

	/**
	 * @return the travel time components of the car trip, or null if there is no car connection
	 */
	@Nullable
	public CarTravelTime estimate(Activity fromActivity, Activity toActivity, double departureTime) {
		Link fromLink = decideOnLink(fromActivity);
		Link toLink = decideOnLink(toActivity);

		double accessTime = calcWalkTime(fromActivity.getCoord(), fromLink);
		double egressTime = calcWalkTime(toActivity.getCoord(), toLink);

		if (fromLink == toLink) {
			return new CarTravelTime(accessTime, 0., egressTime);
		}

		LeastCostPathCalculator.Path path = calcPath(fromLink, toLink, departureTime + accessTime);
		if (path == null) {
			return null;
		}

		return new CarTravelTime(accessTime, path.travelTime, egressTime);
	}

	/**
	 * @return the travel time components of the car trip, or null if there is no car connection
	 */
	@Nullable
	public CarTravelTime estimate(Coord fromCoord, Coord toCoord, double departureTime) {
		return estimate(PopulationUtils.createActivityFromCoord("dummy", fromCoord),
			PopulationUtils.createActivityFromCoord("dummy", toCoord), departureTime);
	}
}
