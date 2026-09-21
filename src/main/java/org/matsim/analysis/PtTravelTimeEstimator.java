package org.matsim.analysis;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.OccupancyData;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;
import org.matsim.pt.routes.TransitPassengerRoute;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Stand-alone SBB (SwissRail) Raptor pt router. It routes a single origin-destination pair at a given departure time
 * and returns the travel time components of the resulting pt trip.
 */
public class PtTravelTimeEstimator {

	private final SwissRailRaptor raptor;
	private final Scenario scenario;

	/**
	 * Travel time components of one pt trip. All values are in seconds.
	 *
	 * @param accessTime        walk time from the origin to the first pt stop
	 * @param waitingTime       total time spent waiting at stops (initial wait + waiting during transfers)
	 * @param inVehicleTime     total time spent inside pt vehicles
	 * @param transferWalkTime  total walk time between stops when transferring
	 * @param egressTime        walk time from the last pt stop to the destination
	 * @param numberOfTransfers number of transfers (i.e. number of pt legs - 1)
	 */
	public record PtTravelTime(double accessTime, double waitingTime, double inVehicleTime, double transferWalkTime,
							   double egressTime, int numberOfTransfers) {
		public double totalTravelTime() {
			return accessTime + waitingTime + inVehicleTime + transferWalkTime + egressTime;
		}
	}

	public PtTravelTimeEstimator(Scenario scenario) {
		this.scenario = scenario;
		Config config = scenario.getConfig();
		RaptorStaticConfig raptorStaticConfig = RaptorUtils.createStaticConfig(config);
		SwissRailRaptorData data = SwissRailRaptorData.create(scenario.getTransitSchedule(), scenario.getTransitVehicles(),
			raptorStaticConfig, scenario.getNetwork(), new OccupancyData());
		this.raptor = new SwissRailRaptor.Builder(data, config).build();
	}

	/**
	 * Builds the router from a MATSim config. Only network, transit schedule and transit vehicles are read;
	 * plans, facilities and counts are skipped. The file paths in the config can be overwritten (pass null to keep
	 * the value from the config).
	 */
	public static PtTravelTimeEstimator create(String configPath, @Nullable String networkFile,
											   @Nullable String transitScheduleFile, @Nullable String transitVehiclesFile) {
		Config config = ConfigUtils.loadConfig(configPath, new SwissRailRaptorConfigGroup());

		if (networkFile != null) {
			config.network().setInputFile(networkFile);
		}
		if (transitScheduleFile != null) {
			config.transit().setTransitScheduleFile(transitScheduleFile);
		}
		if (transitVehiclesFile != null) {
			config.transit().setVehiclesFile(transitVehiclesFile);
		}

		// not needed for routing, and reading them would take a lot of time
		config.plans().setInputFile(null);
		config.facilities().setInputFile(null);
		config.counts().setInputFile(null);

		return new PtTravelTimeEstimator(ScenarioUtils.loadScenario(config));
	}

	/**
	 * @return the scenario the router is based on, e.g. to reuse the network and the config for the other estimators
	 */
	public Scenario getScenario() {
		return scenario;
	}

	public static PtTravelTimeEstimator create(String configPath) {
		return create(configPath, null, null, null);
	}

	/**
	 * @return the travel time components of the fastest (i.e. least cost) pt trip, or null if no pt connection is found
	 */
	@Nullable
	public PtTravelTime estimate(Activity fromActivity, Activity toActivity, double departureTime) {
		return estimate(FacilitiesUtils.wrapActivity(fromActivity), FacilitiesUtils.wrapActivity(toActivity), departureTime);
	}

	/**
	 * @return the travel time components of the fastest (i.e. least cost) pt trip, or null if no pt connection is found
	 */
	@Nullable
	public PtTravelTime estimate(Coord fromCoord, Coord toCoord, double departureTime) {
		return estimate(PopulationUtils.createActivityFromCoord("dummy", fromCoord),
			PopulationUtils.createActivityFromCoord("dummy", toCoord), departureTime);
	}

	/**
	 * @return the travel time components of the fastest (i.e. least cost) pt trip, or null if no pt connection is found
	 */
	@Nullable
	public PtTravelTime estimate(Facility fromFacility, Facility toFacility, double departureTime) {
		Person person = null;
		List<? extends PlanElement> planElements = raptor.calcRoute(
			DefaultRoutingRequest.withoutAttributes(fromFacility, toFacility, departureTime, person));

		if (planElements == null) {
			return null;
		}

		double accessTime = 0.;
		double waitingTime = 0.;
		double inVehicleTime = 0.;
		double transferWalkTime = 0.;
		int numberOfPtLegs = 0;
		// walk time accumulated since the last pt leg: it is either access, transfer or egress walk, depending on
		// whether another pt leg follows
		double walkTime = 0.;

		for (PlanElement planElement : planElements) {
			if (!(planElement instanceof Leg leg)) {
				// pt interaction activities have no duration
				continue;
			}

			if (leg.getRoute() instanceof TransitPassengerRoute ptRoute) {
				double departureTimeAtStop = leg.getDepartureTime().seconds();
				double arrivalTimeAtStop = departureTimeAtStop + leg.getTravelTime().seconds();
				// the leg starts when the agent arrives at the stop, therefore waiting time is included in the leg
				double boardingTime = ptRoute.getBoardingTime().orElse(departureTimeAtStop);

				waitingTime += boardingTime - departureTimeAtStop;
				inVehicleTime += arrivalTimeAtStop - boardingTime;

				if (numberOfPtLegs == 0) {
					accessTime = walkTime;
				} else {
					transferWalkTime += walkTime;
				}
				walkTime = 0.;
				numberOfPtLegs++;
			} else {
				walkTime += leg.getTravelTime().seconds();
			}
		}

		if (numberOfPtLegs == 0) {
			// raptor returned a direct walk (i.e. there is no reasonable pt connection)
			return null;
		}

		return new PtTravelTime(accessTime, waitingTime, inVehicleTime, transferWalkTime, walkTime, numberOfPtLegs - 1);
	}
}
